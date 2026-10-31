package org.jellyfin.mobile.update

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.mobile.BuildConfig
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class Downloading(val release: UpdateRelease, val downloaded: Long, val total: Long) : UpdateState
    data class Ready(val release: UpdateRelease, val apk: File) : UpdateState
    data class Failed(val release: UpdateRelease, val message: String) : UpdateState
}

data class UpdateRelease(
    val tag: String,
    val versionName: String,
    val versionCode: Int?,
    val notes: String,
    val publishedAt: String?,
    val assetName: String,
    val assetUrl: String,
    val assetSize: Long,
)

@Serializable
private data class GitHubRelease(
    val tag_name: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val published_at: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String? = null,
    val browser_download_url: String? = null,
    val size: Long = 0,
    val content_type: String? = null,
)

class UpdateManager(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var checkJob: Job? = null
    private var checkedThisProcess = false
    private var dismissedTag: String? = null
    private var pendingInstall: File? = null
    private var downloadJob: Job? = null

    fun checkForUpdates() {
        if (checkedThisProcess || checkJob?.isActive == true) return
        checkedThisProcess = true
        checkJob = scope.launch {
            runCatching { fetchLatestRelease() }
                .onSuccess { release ->
                    if (release != null && release.tag != dismissedTag && isNewer(release)) {
                        _state.value = UpdateState.Available(release)
                    }
                }
                .onFailure { error ->
                    if (error !is CancellationException) Timber.i("GitHub update check skipped reason=%s", error::class.simpleName)
                }
        }
    }

    fun dismiss(release: UpdateRelease) {
        dismissedTag = release.tag
        if (_state.value is UpdateState.Available) _state.value = UpdateState.Idle
    }

    fun download(release: UpdateRelease) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            try {
                require(isTrustedAssetUrl(release.assetUrl))
                val directory = File(context.filesDir, "updates").apply { mkdirs() }
                val partial = File(directory, "${safeName(release.assetName)}.part")
                val apk = File(directory, safeName(release.assetName))
                apk.delete()
                downloadAsset(release, partial)
                verifyApk(partial)
                if (!partial.renameTo(apk)) error("Unable to finalize downloaded APK")
                _state.value = UpdateState.Ready(release, apk)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val reason = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "Unknown error"
                Timber.w("GitHub update download rejected reason=%s", error::class.simpleName)
                _state.value = UpdateState.Failed(release, reason)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        (_state.value as? UpdateState.Downloading)?.let { _state.value = UpdateState.Available(it.release) }
    }

    fun retryDownload(release: UpdateRelease) {
        _state.value = UpdateState.Available(release)
        download(release)
    }

    fun install(context: Context, apk: File) {
        if (!apk.exists()) return
        val release = (_state.value as? UpdateState.Ready)?.release ?: return
        try {
            pendingInstall = apk
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
                return
            }
            pendingInstall = null
            launchInstaller(context, apk)
        } catch (error: Exception) {
            pendingInstall = null
            _state.value = UpdateState.Failed(release, error.message ?: "Unable to open Android installer")
        }
    }

    fun resumeInstallIfAllowed(context: Context) {
        val apk = pendingInstall ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) {
            pendingInstall = null
            val release = (_state.value as? UpdateState.Ready)?.release
            if (release == null) return
            try {
                launchInstaller(context, apk)
            } catch (error: Exception) {
                _state.value = UpdateState.Failed(release, error.message ?: "Unable to open Android installer")
            }
        }
    }

    private suspend fun fetchLatestRelease(): UpdateRelease? = withContext(Dispatchers.IO) {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = API_TIMEOUT_MS
            readTimeout = API_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "XPlay/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val release = connection.inputStream.bufferedReader().use { json.decodeFromString<GitHubRelease>(it.readText()) }
            if (release.draft || release.prerelease) return@withContext null
            val tag = release.tag_name?.takeIf { it.isNotBlank() } ?: return@withContext null
            val asset = release.assets.firstOrNull { candidate ->
                val name = candidate.name.orEmpty()
                name.endsWith(".apk", ignoreCase = true) && !name.contains("unsigned", ignoreCase = true)
            } ?: return@withContext null
            val url = asset.browser_download_url ?: return@withContext null
            UpdateRelease(
                tag = tag,
                versionName = parseVersionName(release.name ?: tag),
                versionCode = parseVersionCode(tag),
                notes = release.body.orEmpty().trim(),
                publishedAt = release.published_at,
                assetName = asset.name ?: "xplay-update.apk",
                assetUrl = url,
                assetSize = asset.size,
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadAsset(release: UpdateRelease, partial: File) = withContext(Dispatchers.IO) {
        var currentUrl = release.assetUrl
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            require(isTrustedAssetUrl(currentUrl))
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = DOWNLOAD_TIMEOUT_MS
                readTimeout = DOWNLOAD_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "XPlay/${BuildConfig.VERSION_NAME}")
            }
            try {
                if (connection.responseCode in 300..399) {
                    require(redirectIndex < MAX_REDIRECTS)
                    currentUrl = URL(URL(currentUrl), connection.getHeaderField("Location")).toString()
                    return@repeat
                }
                require(connection.responseCode in 200..299)
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.assetSize
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            _state.value = UpdateState.Downloading(release, downloaded, total)
                        }
                    }
                }
                require(release.assetSize <= 0L || partial.length() == release.assetSize) { "Incomplete APK download" }
                return@withContext
            }
            finally {
                connection.disconnect()
            }
        }
        error("Too many redirects")
    }

    private fun verifyApk(file: File) {
        require(file.length() > 0) { "Empty APK" }
        val packageInfo = context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
        ) ?: error("Invalid APK")
        require(packageInfo.packageName == context.packageName) {
            "APK package is ${packageInfo.packageName}; expected ${context.packageName}"
        }
        val downloadedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION") packageInfo.signatures.orEmpty()
        }
        val installedInfo = context.packageManager.getPackageInfo(
            context.packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
        )
        val downloadedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
        val installedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installedInfo.longVersionCode else installedInfo.versionCode.toLong()
        require(downloadedVersionCode > installedVersionCode) {
            "APK version $downloadedVersionCode is not newer than installed version $installedVersionCode"
        }
        val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installedInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION") installedInfo.signatures.orEmpty()
        }
        require(downloadedSignatures.isNotEmpty() && installedSignatures.isNotEmpty()) { "Missing APK signature" }
        val downloadedDigests = downloadedSignatures.map(::certificateDigest).toSet()
        val installedDigests = installedSignatures.map(::certificateDigest).toSet()
        require(downloadedDigests == installedDigests) { "APK signature mismatch" }
    }

    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-file-provider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("XPlay update", uri)
        })
    }

    private fun isNewer(release: UpdateRelease): Boolean = release.versionCode?.let { it > BuildConfig.VERSION_CODE }
        ?: (compareVersions(release.versionName, BuildConfig.VERSION_NAME) > 0)

    private fun certificateDigest(signature: android.content.pm.Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun isTrustedAssetUrl(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme == "https" && (uri.host == "github.com" || uri.host?.endsWith(".github.com") == true || uri.host == "githubusercontent.com" || uri.host?.endsWith(".githubusercontent.com") == true)
    }.getOrDefault(false)

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)

    private fun parseVersionName(value: String): String = Regex("\\d+(?:\\.\\d+){1,3}").find(value)?.value ?: value.removePrefix("v")

    private fun parseVersionCode(value: String): Int? = Regex("(?:versionCode|code)[-_]?(\\d+)", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.toIntOrNull()

    private fun compareVersions(left: String, right: String): Int {
        val a = left.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val b = right.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val difference = a.getOrElse(index) { 0 } - b.getOrElse(index) { 0 }
            if (difference != 0) return difference
        }
        return 0
    }

    private companion object {
        const val API_URL = "https://api.github.com/repos/Expuz66/XPlay/releases/latest"
        const val API_TIMEOUT_MS = 8_000
        const val DOWNLOAD_TIMEOUT_MS = 20_000
        const val BUFFER_SIZE = 32 * 1024
        const val MAX_REDIRECTS = 3
    }
}
