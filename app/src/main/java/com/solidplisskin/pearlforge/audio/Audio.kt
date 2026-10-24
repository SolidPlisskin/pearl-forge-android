package com.solidplisskin.pearlforge.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.math.log10
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Records voice notes as AAC in an .m4a file (44.1 kHz mono, like the iPhone app), with a live level and timer. */
class VoiceRecorder(private val context: Context) {
    var isRecording by mutableStateOf(false)
        private set
    var elapsed by mutableDoubleStateOf(0.0)
        private set
    /** 0…1, for the level meter. */
    var level by mutableDoubleStateOf(0.0)
        private set

    private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    private var ticker: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start(file: File) {
        file.delete()
        val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        newRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioChannels(1)
            setAudioEncodingBitRate(128_000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = newRecorder
        startedAt = System.currentTimeMillis()
        elapsed = 0.0
        isRecording = true
        ticker = scope.launch {
            while (isActive) {
                elapsed = (System.currentTimeMillis() - startedAt) / 1000.0
                val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                // Map roughly -50…0 dB onto 0…1.
                level = if (amplitude <= 0) 0.0 else ((20 * log10(amplitude / 32767.0) + 50) / 50).coerceIn(0.0, 1.0)
                delay(100)
            }
        }
    }

    /** Stops and returns how long the recording was, in seconds. */
    fun stop(): Double {
        ticker?.cancel()
        val duration = (System.currentTimeMillis() - startedAt) / 1000.0
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        isRecording = false
        level = 0.0
        return duration
    }
}

/** Plays one piece of audio at a time, with play/pause, seeking and progress. */
class AudioPlayback(private val context: Context) {
    var isPlaying by mutableStateOf(false)
        private set
    var currentTime by mutableDoubleStateOf(0.0)
        private set
    var duration by mutableDoubleStateOf(0.0)
        private set
    /** What's loaded (a pearl's id, "original", …), so a screen knows what's in the player. */
    var loadedKey by mutableStateOf<String?>(null)
        private set
    /** Called when playback reaches the end. */
    var onFinish: (() -> Unit)? = null

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Loads audio bytes (written to a temporary file, since MediaPlayer plays files). */
    fun load(data: ByteArray, key: String, extension: String = "mp3") {
        val file = File(context.cacheDir, "playing-${key.hashCode()}.$extension")
        file.writeBytes(data)
        load(file, key)
    }

    fun load(file: File, key: String) {
        unload()
        val newPlayer = MediaPlayer()
        newPlayer.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        newPlayer.setDataSource(file.absolutePath)
        newPlayer.prepare()
        newPlayer.setOnCompletionListener {
            isPlaying = false
            currentTime = duration
            onFinish?.invoke()
        }
        player = newPlayer
        duration = newPlayer.duration / 1000.0
        currentTime = 0.0
        loadedKey = key
    }

    fun play() {
        val current = player ?: return
        if (currentTime >= duration - 0.05) current.seekTo(0)
        current.start()
        isPlaying = true
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                player?.let { currentTime = it.currentPosition / 1000.0 }
                delay(200)
            }
        }
    }

    fun pause() {
        player?.pause()
        isPlaying = false
        ticker?.cancel()
    }

    fun togglePlay() = if (isPlaying) pause() else play()

    fun seek(seconds: Double) {
        player?.seekTo((seconds * 1000).toInt())
        currentTime = seconds
    }

    fun unload() {
        ticker?.cancel()
        player?.release()
        player = null
        isPlaying = false
        currentTime = 0.0
        duration = 0.0
        loadedKey = null
    }
}
