package com.solidplisskin.pearlforge

import android.app.Application
import com.solidplisskin.pearlforge.pipeline.Forge
import com.solidplisskin.pearlforge.recordings.RecordingLibrary
import com.solidplisskin.pearlforge.settings.CredentialStore
import com.solidplisskin.pearlforge.settings.PolishersModel
import com.solidplisskin.pearlforge.settings.SettingsModel

/** The app's long-lived state: keys, settings, AI choices and the recording library. */
class AppContainer(application: Application) {
    val credentials = CredentialStore(application)
    val settings = SettingsModel(application, credentials)
    val polishers = PolishersModel(application, credentials)
    val library = RecordingLibrary(application)
}

class PearlForgeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Forge.credentials = container.credentials::read
    }
}
