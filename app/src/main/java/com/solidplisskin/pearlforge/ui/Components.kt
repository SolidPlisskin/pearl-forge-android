package com.solidplisskin.pearlforge.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.solidplisskin.pearlforge.audio.AudioPlayback
import com.solidplisskin.pearlforge.recordings.Reference
import com.solidplisskin.pearlforge.recordings.ReferenceKind
import com.solidplisskin.pearlforge.settings.KeyStatus
import java.io.File
import java.text.DateFormat
import java.util.Date

/** A screen with a title bar (and a back arrow when it was opened from another screen), scrolling content, kept to a readable width on the tablet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    scrolls: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = { actions() },
        )
        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
            val column = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp)
            if (scrolls) {
                Column(column.verticalScroll(rememberScrollState()).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { content() }
            } else {
                Column(column, verticalArrangement = Arrangement.spacedBy(20.dp)) { content() }
            }
        }
    }
}

/** A titled group of rows on a rounded card, with an explanation underneath, like iOS's grouped lists. */
@Composable
fun FormSection(title: String? = null, footer: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                 modifier = Modifier.padding(start = 4.dp))
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                content()
            }
        }
        if (footer != null) {
            Box(Modifier.padding(horizontal = 4.dp)) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                    androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodySmall,
                ) { footer() }
            }
        }
    }
}

/** A plain explanation line for a section's footer. */
@Composable
fun FooterText(text: String, isError: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall,
         color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A label on the left and its value on the right. */
@Composable
fun LabeledRow(label: String, value: String, detail: String? = null, bold: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = if (bold) FontWeight.SemiBold else null)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Text(value, fontWeight = if (bold) FontWeight.SemiBold else null,
             color = if (bold) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One line of a cost breakdown: what it's for, the amount, and a detail line. */
@Composable
fun CostRow(title: String, value: String, detail: String?, isTotal: Boolean = false) = LabeledRow(title, value, detail, bold = isTotal)

/** A spinner with what's happening, and an optional detail line. */
@Composable
fun ProgressRow(label: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(label)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** A thin line between rows in a section. */
@Composable
fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

/** A segmented control for a few choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                enabled = enabled,
            ) { Text(label(option), maxLines = 1) }
        }
    }
}

/** "1:05" style minutes and seconds. */
fun timeString(seconds: Double): String {
    val total = maxOf(0, seconds.toInt())
    return "%d:%02d".format(total / 60, total % 60)
}

/** Play/pause, a scrubber, and the elapsed and total time. */
@Composable
fun PlayerRow(player: AudioPlayback) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { player.togglePlay() }, modifier = Modifier.size(52.dp)) {
            Icon(if (player.isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                 contentDescription = if (player.isPlaying) "Pause" else "Play",
                 tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(46.dp))
        }
        Column(Modifier.weight(1f)) {
            Slider(value = player.currentTime.toFloat(), onValueChange = { player.seek(it.toDouble()) },
                   valueRange = 0f..maxOf(player.duration.toFloat(), 0.1f))
            Row {
                Text(timeString(player.currentTime), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(timeString(player.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

val ReferenceKind.icon: ImageVector
    get() = when (this) {
        ReferenceKind.RESEARCH -> Icons.Filled.School
        ReferenceKind.ARTICLE -> Icons.Filled.Article
        ReferenceKind.ESSAY -> Icons.Filled.MenuBook
        ReferenceKind.BLOG -> Icons.Filled.ChatBubbleOutline
        ReferenceKind.BOOK -> Icons.Filled.Book
        ReferenceKind.OTHER -> Icons.Filled.Link
    }

/** One reference: what kind it is, its title and source, and how it relates. */
@Composable
fun ReferenceRow(reference: Reference, fromTitle: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(reference.kind.icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(reference.kind.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Text(reference.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val source = listOfNotNull(reference.authors, reference.source.ifEmpty { null }, reference.year).joinToString(" · ")
        if (source.isNotEmpty()) Text(source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        if (reference.note.isNotEmpty()) Text(reference.note, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        if (fromTitle != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GraphicEq, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(fromTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A recording's topics as small capsules that wrap onto new lines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicChips(topics: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        topics.forEach { topic ->
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                Text(topic, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.padding(PaddingValues(horizontal = 10.dp, vertical = 4.dp)))
            }
        }
    }
}

/** A key's state: Not set, Saved, Checking…, Works, or Not working. */
@Composable
fun KeyStatusRow(title: String, subtitle: String? = null, status: KeyStatus, onClick: () -> Unit) {
    val (text, color, icon) = when (status) {
        KeyStatus.NotSet -> Triple("Not set", MaterialTheme.colorScheme.onSurfaceVariant, null)
        KeyStatus.Saved -> Triple("Saved", MaterialTheme.colorScheme.onSurfaceVariant, null)
        KeyStatus.Checking -> Triple("Checking…", MaterialTheme.colorScheme.onSurfaceVariant, null)
        KeyStatus.Works -> Triple("Works", Color(0xFF2E9E5B), Icons.Filled.CheckCircle)
        is KeyStatus.Failed -> Triple("Not working", MaterialTheme.colorScheme.error, Icons.Filled.Error)
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (status == KeyStatus.Checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A message in the middle of an empty screen. */
@Composable
fun EmptyState(icon: ImageVector, title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
           verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
             textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

fun Date.mediumDateTime(): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(this)
fun Date.fullDateTime(): String = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(this)

/** Opens a web page in the browser. */
fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

/** Shows the system share sheet for a file in the app's cache. */
fun shareFile(context: Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun copyText(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Pearl Forge", text))
}
