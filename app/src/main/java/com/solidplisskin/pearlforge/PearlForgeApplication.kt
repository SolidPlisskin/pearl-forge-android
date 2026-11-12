package com.solidplisskin.pearlforge

import android.app.Application
import android.os.Build
import android.os.Looper
import android.view.textclassifier.TextClassificationManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.solidplisskin.pearlforge.audio.AudioOutput
import com.solidplisskin.pearlforge.pipeline.Forge
import com.solidplisskin.pearlforge.recordings.RecordingLibrary
import com.solidplisskin.pearlforge.recordings.TextLanguage
import com.solidplisskin.pearlforge.settings.CredentialStore
import com.solidplisskin.pearlforge.settings.PolishersModel
import com.solidplisskin.pearlforge.settings.SettingsModel
import com.solidplisskin.pearlforge.ui.CreateModel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app's long-lived state: keys, settings, AI choices and the recording library. */
class AppContainer(application: Application) {
    val credentials = CredentialStore(application)
    val settings = SettingsModel(application, credentials)
    val polishers = PolishersModel(application, credentials)
    /** Work that should finish even if you switch tabs or turn the tablet: forging, lectures, searches, videos. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val library = RecordingLibrary(application, scope)
    val create = CreateModel(application, library, scope)
    val audioOutput = AudioOutput(application)
    /** A recording another app shared with Pearl Forge (Share › Pearl Forge), copied here and waiting to be brought in. */
    var sharedAudio by mutableStateOf<File?>(null)
}

class PearlForgeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Forge.credentials = container.credentials::read
        installLanguageDetector()
    }

    /**
     * Lets the library ask Android's own language detector about text its word lists can't settle.
     * It runs on the tablet and costs nothing, but mustn't be asked on the main thread.
     */
    private fun installLanguageDetector() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = getSystemService(TextClassificationManager::class.java) ?: return
        TextLanguage.systemDetector = detector@{ text ->
            if (Looper.myLooper() == Looper.getMainLooper()) return@detector null
            runCatching {
                val result = manager.textClassifier.detectLanguage(android.view.textclassifier.TextLanguage.Request.Builder(text).build())
                if (result.localeHypothesisCount == 0) return@runCatching ""
                val locale = result.getLocale(0)
                if (result.getConfidenceScore(locale) >= 0.6f) locale.language else ""
            }.getOrDefault("")
        }
    }
}
