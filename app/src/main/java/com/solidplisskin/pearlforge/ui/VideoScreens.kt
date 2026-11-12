package com.solidplisskin.pearlforge.ui

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.solidplisskin.pearlforge.AppContainer
import com.solidplisskin.pearlforge.audio.AudioOutput
import com.solidplisskin.pearlforge.pipeline.PipelineError
import com.solidplisskin.pearlforge.pipeline.VideoModel
import com.solidplisskin.pearlforge.recordings.Pearl
import com.solidplisskin.pearlforge.recordings.RecordingEntry
import com.solidplisskin.pearlforge.recordings.RecordingLibrary
import java.io.File

/** Where sound plays, for every player's output chip. */
val LocalAudioOutput = staticCompositionLocalOf<AudioOutput?> { null }

/** Badge colours, like the iPhone app's. */
object BadgeColors {
    val video = Color(0xFF8E44AD)
    val waiting = Color(0xFFE67E22)
    val language = Color(0xFF1E88E5)
}

/** A small coloured label: "Video", "Korean". */
@Composable
fun Badge(text: String, icon: ImageVector, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(12.dp), tint = color)
            Spacer(Modifier.width(3.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        }
    }
}

/**
 * Where the sound is playing ("Tablet speaker", "Galaxy Buds3 Pro"). Tapping it lists everywhere it
 * could play instead, like Spotify's devices button: this tablet, Bluetooth headphones and
 * speakers, and cast devices.
 */
@Composable
fun AudioOutputButton(modifier: Modifier = Modifier) {
    val output = LocalAudioOutput.current ?: return
    val context = LocalContext.current
    LaunchedEffect(Unit) { output.refresh() }
    val icon = when (output.kind) {
        AudioOutput.Kind.SPEAKER -> Icons.Filled.TabletAndroid
        AudioOutput.Kind.HEADPHONES -> Icons.Filled.Headphones
        AudioOutput.Kind.BLUETOOTH -> Icons.Filled.Bluetooth
        AudioOutput.Kind.TV -> Icons.Filled.Tv
        AudioOutput.Kind.CAR -> Icons.Filled.DirectionsCar
        AudioOutput.Kind.OTHER -> Icons.Filled.Speaker
    }
    Surface(
        onClick = { output.showPicker(context) },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        modifier = modifier.semantics { contentDescription = "Playing on ${output.name}. Choose where to play" },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(5.dp))
            Text(output.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                 color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A still from a pearl's video, with a play mark, or a film placeholder while there's no still (the video is still being made). */
@Composable
fun VideoStill(library: RecordingLibrary, entryId: String, pearl: Pearl, width: Dp = 54.dp, height: Dp = 72.dp) {
    val revision = library.videoRevisions[pearl.id] ?: 0
    val image by produceState<ImageBitmap?>(null, pearl.videoFile, revision) {
        value = library.videoStill(pearl.id, entryId)?.asImageBitmap()
    }
    Box(Modifier.size(width, height).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        val still = image
        if (still != null) {
            Image(still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)))
            Icon(if (pearl.videoFile == null) Icons.Filled.HourglassEmpty else Icons.Filled.Movie, null, tint = MaterialTheme.colorScheme.primary)
        }
        if (pearl.videoFile != null) {
            val mark = minOf(width, height) * 0.38f
            Box(Modifier.size(mark).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(mark * 0.7f), tint = Color.White)
            }
        }
    }
}

/** A pearl's video ready to play, with what it says in English when it's in another language. */
data class PlayingVideo(
    val file: File,
    /** The pearl's text, as spoken in the video. */
    val text: String?,
    /** The language it's spoken in when that isn't yours, and its English version. */
    val language: String?,
    val english: String?,
) {
    companion object {
        /** Gathers a pearl's video and its words. */
        fun of(library: RecordingLibrary, entry: RecordingEntry, pearl: Pearl): PlayingVideo {
            val file = library.videoFile(pearl, entry.id)?.takeIf { it.exists() } ?: throw PipelineError("The video file isn't there any more.")
            return PlayingVideo(file, entry.pearlTexts[pearl.id], entry.otherLanguage(pearl), pearl.english)
        }
    }
}

/**
 * Plays a pearl's video full screen, with its words underneath: in English for a pearl in another
 * language (switchable to the original), so you can follow what your avatar is saying.
 */
@Composable
fun VideoPlayerDialog(video: PlayingVideo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setMediaItem(MediaItem.fromUri(Uri.fromFile(video.file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    var showingOriginal by remember { mutableStateOf(false) }
    // The English version unless you switched to the original; the text itself for a pearl in your language.
    val words = if (video.language != null && !showingOriginal) video.english ?: video.text else video.text
    val wordsLabel = when {
        video.language == null -> "What you're saying"
        showingOriginal || video.english == null -> "What you're saying, in ${video.language}"
        else -> "What you're saying, in English"
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MaterialTheme(colorScheme = darkColorScheme(), typography = MaterialTheme.typography) {
            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("Done", fontWeight = FontWeight.SemiBold) }
                    }
                    AndroidView(factory = { PlayerView(it).apply { this.player = player; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
                                modifier = Modifier.weight(1f).fillMaxWidth())
                    Column(Modifier.fillMaxWidth().background(Color(0xFF1C1C1C))) {
                        AudioOutputButton(Modifier.padding(start = 16.dp, top = 16.dp, bottom = if (words == null) 16.dp else 0.dp))
                        if (words != null) {
                            Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                                   verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(wordsLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    if (video.language != null && video.english != null) {
                                        TextButton(onClick = { showingOriginal = !showingOriginal }) {
                                            Text(if (showingOriginal) "Show English" else "Show ${video.language}", style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                }
                                SelectionContainer { Text(words, color = Color.White) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The system's name for a video file's type, for sharing it. */
fun videoMimeType(file: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "video/mp4"

/** The models that can make videos, with what each costs. */
@Composable
fun VideoModelList(container: AppContainer, onBack: () -> Unit) {
    val settings = container.settings
    @Composable
    fun row(video: VideoModel) {
        Row(Modifier.fillMaxWidth().clickable { settings.chooseVideoModel(video) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(video.name)
                Text("${video.priceLine}. ${video.strength}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (settings.videoModelId == video.id) Icon(Icons.Filled.Check, "Chosen", tint = MaterialTheme.colorScheme.primary)
        }
    }
    ScreenScaffold("Make videos with", onBack) {
        FormSection("fal.ai: pay per video", footer = {
            FooterText("fal.ai's prices for each minute of speech; a video is as long as its pearl. Try a short pearl first to see which you like.")
        }) {
            VideoModel.FAL.forEachIndexed { index, video ->
                if (index > 0) RowDivider()
                row(video)
            }
        }
        FormSection("ElevenLabs") { row(VideoModel.ELEVENLABS) }
    }
}
