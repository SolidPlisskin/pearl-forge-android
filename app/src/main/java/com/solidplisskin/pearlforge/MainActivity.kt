package com.solidplisskin.pearlforge

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.solidplisskin.pearlforge.theme.PearlForgeTheme
import com.solidplisskin.pearlforge.ui.PearlForgeApp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val container get() = (application as PearlForgeApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) takeSharedAudio(intent)
        setContent { PearlForgeTheme { PearlForgeApp(container) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSharedAudio(intent)
    }

    override fun onResume() {
        super.onResume()
        // Picking somewhere else to play in the system's list doesn't always announce itself.
        container.audioOutput.refresh()
    }

    /**
     * A recording sent from another app with Share › Pearl Forge (Plaud, a voice recorder, Files),
     * or opened with Pearl Forge. It's copied straight away, while Pearl Forge may still read it,
     * and the Create screen brings it in.
     */
    private fun takeSharedAudio(intent: Intent?) {
        if (intent == null) return
        val uri: Uri = when (intent.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        val type = intent.type ?: contentResolver.getType(uri)
        if (type != null && !type.startsWith("audio/") && type != "application/ogg") return
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment
        val extension = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 5 }
            ?: type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "m4a"
        val copy = File(File(cacheDir, "incoming").apply { mkdirs() }, "shared-recording-${System.currentTimeMillis()}.$extension")
        container.scope.launch {
            val copied = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } } }.isSuccess &&
                    copy.length() > 0
            }
            if (copied) {
                container.sharedAudio?.delete()
                container.sharedAudio = copy
            } else copy.delete()
        }
    }
}
