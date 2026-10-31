package org.jellyfin.mobile.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.io.use
import timber.log.Timber

data class Endpoint(val url: String, val isLocal: Boolean)

fun interface EndpointProbe {
    suspend fun isHealthy(endpoint: Endpoint): Boolean
}

class HttpEndpointProbe(
    private val timeoutMs: Int = 1500,
) : EndpointProbe {
    override suspend fun isHealthy(endpoint: Endpoint): Boolean = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs.toLong()) {
            Timber.i("Endpoint probe started endpoint=%s", endpoint.label())
            val connection = (URL(URI(endpoint.url.trimEnd('/') + "/System/Info/Public").toString()).openConnection() as HttpURLConnection)
            try {
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                Timber.d("Endpoint probe HTTP request started endpoint=%s", endpoint.label())
                val response = connection.responseCode
                val validResponse = response in 200..299 && connection.inputStream.bufferedReader().use { body ->
                    val text = body.readText()
                    "ProductName" in text || "ServerName" in text
                }
                Timber.i("Endpoint probe finished endpoint=%s http=%d jellyfin=%s", endpoint.label(), response, validResponse)
                validResponse
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Timber.i(
                    "Endpoint probe failed endpoint=%s reason=%s",
                    endpoint.label(),
                    exception::class.simpleName,
                )
                false
            } finally {
                connection.disconnect()
            }
        }
    }

}

class EndpointResolver(
    private val probe: EndpointProbe,
    private val localEndpoint: String,
    private val remoteEndpoint: String,
) {
    suspend fun select(): Endpoint? {
        Timber.i("Endpoint selection started order=LOCAL_then_REMOTE")
        configuredEndpoints().forEach { endpoint ->
            if (probe.isHealthy(endpoint)) {
                Timber.i("Selected %s endpoint", endpoint.label())
                return endpoint
            }
        }
        Timber.w("Endpoint selection finished result=OFFLINE")
        return null
    }

    suspend fun alternate(activeUrl: String): Endpoint? = configuredEndpoints()
        .firstOrNull { it.url != activeUrl && probe.isHealthy(it) }

    fun configuredEndpoints(): List<Endpoint> = buildList {
        if (localEndpoint.isNotBlank() && validScheme(localEndpoint, allowHttp = true)) {
            add(Endpoint(localEndpoint.trimEnd('/'), true))
        }
        if (remoteEndpoint.isNotBlank() && validScheme(remoteEndpoint, allowHttp = false)) {
            add(Endpoint(remoteEndpoint.trimEnd('/'), false))
        }
    }

    private fun validScheme(url: String, allowHttp: Boolean): Boolean = runCatching {
        val scheme = URI(url).scheme?.lowercase()
        scheme == "https" || (allowHttp && scheme == "http")
    }.getOrDefault(false)
}

private fun Endpoint.label(): String = if (isLocal) "LOCAL" else "REMOTE"
