package com.solidplisskin.pearlforge.audio

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where Pearl Forge's sound is playing right now ("Tablet speaker", "Galaxy Buds3 Pro"), kept up to
 * date as headphones connect or you pick somewhere else, and the system's list of places it could
 * play instead.
 */
class AudioOutput(context: Context) {
    enum class Kind { SPEAKER, HEADPHONES, BLUETOOTH, TV, CAR, OTHER }

    private val context = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600

    var name by mutableStateOf("")
        private set
    var kind by mutableStateOf(Kind.SPEAKER)
        private set

    init {
        refresh()
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
        }, Handler(Looper.getMainLooper()))
    }

    fun refresh() {
        val device = currentDevice()
        val (newName, newKind) = describe(device)
        name = newName
        kind = newKind
    }

    /** The device media would play on now. */
    private fun currentDevice(): AudioDeviceInfo? {
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { audio.getAudioDevicesForAttributes(media).firstOrNull() }.getOrNull()?.let { return it }
        }
        // Before Android 13, media goes to the most recently connected headphones, else the speaker.
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val preference = listOf(bluetooth, wired, setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE), setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        return preference.firstNotNullOfOrNull { types -> outputs.firstOrNull { it.type in types } }
    }

    private fun describe(device: AudioDeviceInfo?): Pair<String, Kind> {
        val speaker = if (isTablet) "Tablet speaker" else "Phone speaker"
        val product = device?.productName?.toString()?.trim().orEmpty()
        return when (device?.type) {
            null, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> speaker to Kind.SPEAKER
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> (if (isTablet) "Tablet earpiece" else "Phone earpiece") to Kind.SPEAKER
            in wired -> "Headphones" to Kind.HEADPHONES
            in bluetooth -> product.ifEmpty { "Bluetooth" } to Kind.BLUETOOTH
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> product.ifEmpty { "TV" } to Kind.TV
            AudioDeviceInfo.TYPE_BUS -> product.ifEmpty { "Car" } to Kind.CAR
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> product.ifEmpty { "USB audio" } to Kind.HEADPHONES
            else -> product.ifEmpty { speaker } to Kind.OTHER
        }
    }

    /**
     * Shows the system's list of places to play, like Spotify's devices button: this tablet,
     * Bluetooth headphones and speakers, and cast devices. Older Android versions get their own
     * version of the list, or Bluetooth settings.
     */
    fun showPicker(activityContext: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)) return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 and 13: System UI's media output dialog.
            val shown = runCatching {
                context.sendBroadcast(Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                    .setPackage("com.android.systemui").putExtra("package_name", context.packageName))
            }.isSuccess
            if (shown) return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val panel = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
            if (start(activityContext, panel)) return
        }
        start(activityContext, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun start(activityContext: Context, intent: Intent): Boolean = try {
        activityContext.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private companion object {
        val wired = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG)
        val bluetooth = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
        }
    }
}
