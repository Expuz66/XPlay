package org.jellyfin.mobile.update

import android.app.Dialog
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import org.jellyfin.mobile.BuildConfig
import org.koin.android.ext.android.inject

class UpdateDialogFragment : DialogFragment() {
    private val updateManager: UpdateManager by inject()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext()).apply {
            setTitle("Update Available")
            setContentView(ComposeView(context).apply {
                setContent {
                    val state by updateManager.state.collectAsState()
                    UpdateContent(state, updateManager, requireContext())
                }
            })
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), -2)
    }
}

@androidx.compose.runtime.Composable
private fun UpdateContent(state: UpdateState, manager: UpdateManager, context: android.content.Context) {
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Ready -> state.release
        UpdateState.Idle -> return
    }
    Surface(color = Color(0xFF171B24), shape = MaterialTheme.shapes.medium) {
        Column(
            modifier = Modifier.padding(22.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Update Available", color = Color.White, style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
            Text("A new version is ready", color = Color(0xFF9DB9FF))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text("v${BuildConfig.VERSION_NAME}", color = Color.LightGray)
                Spacer(Modifier.width(28.dp))
                Text("v${release.versionName}", color = Color(0xFF70A7FF), fontWeight = FontWeight.Bold)
            }
            Divider(color = Color(0xFF353B49))
            if (release.notes.isNotBlank()) {
                Text("What's new", color = Color.White, fontWeight = FontWeight.Bold)
                Text(release.notes, color = Color(0xFFD3D7E0), maxLines = 8, modifier = Modifier.verticalScroll(rememberScrollState()))
            }
            when (state) {
                is UpdateState.Downloading -> {
                    val percent = if (state.total > 0) (state.downloaded * 100 / state.total).toInt() else 0
                    CircularProgressIndicator(progress = if (state.total > 0) state.downloaded.toFloat() / state.total else 0f, color = Color(0xFF70A7FF))
                    Text("${formatBytes(state.downloaded)} / ${formatBytes(state.total)}  $percent%", color = Color.White)
                    Button(onClick = manager::cancelDownload, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF303746))) { Text("Cancel") }
                }
                is UpdateState.Ready -> {
                    Text("Download complete", color = Color(0xFF9FE3B1))
                    Button(onClick = { manager.install(context, state.apk) }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3976D8))) { Text("Install") }
                }
                is UpdateState.Available -> {
                    Row {
                        Button(onClick = { manager.download(release) }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3976D8))) { Text("Download") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { manager.dismiss(release) }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF303746))) { Text("Not now") }
                    }
                }
                UpdateState.Idle -> Unit
            }
        }
    }
}

private fun formatBytes(value: Long): String = when {
    value < 1024 -> "$value B"
    value < 1024 * 1024 -> "%.1f KB".format(value / 1024f)
    else -> "%.1f MB".format(value / (1024f * 1024f))
}
