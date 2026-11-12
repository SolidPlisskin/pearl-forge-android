package com.solidplisskin.pearlforge.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RecentActors
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.draw.clip
import com.solidplisskin.pearlforge.pipeline.AvatarVideo
import com.solidplisskin.pearlforge.pipeline.VideoModel
import com.solidplisskin.pearlforge.pipeline.dollars
import com.solidplisskin.pearlforge.recordings.RecordingLibrary
import com.solidplisskin.pearlforge.settings.KeyStatus
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solidplisskin.pearlforge.AppContainer
import com.solidplisskin.pearlforge.audio.AudioPlayback
import com.solidplisskin.pearlforge.pipeline.PolisherChoice
import com.solidplisskin.pearlforge.pipeline.PolisherProvider
import com.solidplisskin.pearlforge.pipeline.RefinementMode
import com.solidplisskin.pearlforge.pipeline.ResearchLoop
import com.solidplisskin.pearlforge.pipeline.SpeechLanguage
import com.solidplisskin.pearlforge.pipeline.SpeechLanguages
import com.solidplisskin.pearlforge.pipeline.TopicSorter
import com.solidplisskin.pearlforge.recordings.Pearl
import com.solidplisskin.pearlforge.recordings.RecordingEntry
import com.solidplisskin.pearlforge.recordings.RecordingFiles
import com.solidplisskin.pearlforge.settings.PolishersModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

/** What History lists. */
enum class HistoryMode(val label: String) { RECORDINGS("Recordings"), VIDEOS("Videos"), TOPICS("Topics") }

/** Every saved recording, newest first, with search and favorites; every video you've made; or recordings gathered by topic. */
@Composable
fun HistoryList(container: AppContainer, selectedId: String?, selectedPearl: UUID?, player: AudioPlayback,
                open: (HistoryRoute) -> Unit, openTab: (AppTab) -> Unit) {
    val library = container.library
    // Read so the list is redrawn once the languages of everything have been worked out.
    library.languageRevision
    var mode by remember { mutableStateOf(HistoryMode.RECORDINGS) }
    var search by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RecordingEntry?>(null) }
    var playingVideo by remember { mutableStateOf<PlayingVideo?>(null) }
    var videoError by remember { mutableStateOf<String?>(null) }
    val shown = library.entries.filter { entry -> (!favoritesOnly || entry.meta.favorite) && (search.isEmpty() || matches(entry, search)) }
    // Every pearl with a video (or one still to collect), newest first.
    val videos = library.entries.flatMap { entry -> entry.videoPearls.map { entry to it } }
        .filter { search.isEmpty() || matches(it.first, search) }
        .sortedByDescending { it.second.created }

    fun play(entry: RecordingEntry, pearl: Pearl) {
        player.pause()
        runCatching { PlayingVideo.of(library, entry, pearl) }.onSuccess { playingVideo = it }.onFailure { videoError = it.message }
    }

    ScreenScaffold("History", actions = {
        if (mode == HistoryMode.RECORDINGS) IconButton(onClick = { favoritesOnly = !favoritesOnly }) {
            Icon(if (favoritesOnly) Icons.Filled.Star else Icons.Filled.StarBorder, if (favoritesOnly) "Show all recordings" else "Show favorites only")
        }
    }) {
        Segmented(HistoryMode.entries, mode, { it.label }) { mode = it }
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) },
                          placeholder = { Text(if (mode == HistoryMode.TOPICS) "Search topics" else "Search your thoughts, in any language") })
        when {
            library.entries.isEmpty() ->
                EmptyState(Icons.Filled.History, "No recordings yet", "Every thought you refine is saved here: the original recording and each pearl you forge from it.")
            mode == HistoryMode.TOPICS -> TopicsContent(container, search, open, openTab)
            mode == HistoryMode.VIDEOS -> when {
                videos.isEmpty() && search.isNotEmpty() -> EmptyState(Icons.Filled.Search, "No results", "Nothing matches “$search”.")
                videos.isEmpty() -> EmptyState(Icons.Filled.Movie, "No videos yet",
                                               "Open a pearl and choose Make video, or add one you made elsewhere. Every video shows up here.")
                else -> FormSection {
                    videos.forEachIndexed { index, (entry, pearl) ->
                        if (index > 0) RowDivider()
                        VideoRow(library, entry, pearl, isSelected = pearl.id == selectedPearl, play = { play(entry, pearl) },
                                 onClick = { open(HistoryRoute.PearlPage(entry.id, pearl.id)) })
                    }
                }
            }
            shown.isEmpty() -> {
                if (search.isNotEmpty()) EmptyState(Icons.Filled.Search, "No results", "Nothing matches “$search”.")
                else EmptyState(Icons.Filled.Star, "No favorites yet", "Tap the star on a recording to make it a favorite.")
            }
            else -> {
                if (search.isEmpty() && !favoritesOnly) EnglishCatchUp(container, openTab)
                FormSection {
                    shown.forEachIndexed { index, entry ->
                        if (index > 0) RowDivider()
                        HistoryRow(library, entry, entry.id == selectedId, onClick = { open(HistoryRoute.Recording(entry.id)) },
                                   onFavorite = { container.scope.launch { library.setFavorite(!entry.meta.favorite, entry.id) } },
                                   onDelete = { pendingDelete = entry })
                    }
                }
            }
        }
    }

    playingVideo?.let { VideoPlayerDialog(it) { playingVideo = null } }
    videoError?.let { message ->
        AlertDialog(
            onDismissRequest = { videoError = null },
            title = { Text("Couldn't play the video") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { videoError = null }) { Text("OK") } },
        )
    }
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this recording?") },
            text = { Text("The original recording and every pearl made from it will be deleted from ${library.locationName}.") },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; container.scope.launch { library.deleteRecording(entry.id) } }) {
                    Text("Delete recording and all its pearls", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun matches(entry: RecordingEntry, query: String): Boolean =
    (listOf(entry.meta.title, entry.displayTitle, entry.transcript, entry.meta.gist ?: "") + entry.meta.categories.orEmpty() +
        entry.pearlTexts.values + entry.meta.pearls.mapNotNull { it.english } + entry.otherLanguages)
        .any { it.contains(query, ignoreCase = true) }

/** Offers English versions for pearls in other languages made before Pearl Forge wrote them automatically. */
@Composable
private fun EnglishCatchUp(container: AppContainer, openTab: (AppTab) -> Unit) {
    val library = container.library
    val polishers = container.polishers
    val missing = library.pearlsMissingEnglish.size
    if (missing == 0) return
    val choice = polishers.activeChoice
    val error = library.translationErrors.values.firstOrNull()
    FormSection(footer = when {
        error != null -> ({ FooterText(error, isError = true) })
        choice != null -> ({ FooterText("So you can tell what pearls in other languages say. New ones get theirs automatically. Uses ${polishers.label(choice)}, about a cent a pearl.") })
        else -> null
    }) {
        when {
            library.translating.isNotEmpty() -> ProgressRow("Writing English versions…")
            choice != null -> TextButton(onClick = { container.scope.launch { library.addEnglishToAll(choice) } }) {
                Icon(Icons.Filled.Translate, null); Spacer(Modifier.width(8.dp))
                Text(if (missing == 1) "Add English to 1 pearl in another language" else "Add English to $missing pearls in other languages")
            }
            else -> TextButton(onClick = { openTab(AppTab.AI) }) { Text("Choose an AI in the AI tab to see pearls in English") }
        }
    }
}

/**
 * A recording in the list: a still from its newest video (or what kind of recording it is), its
 * title in your language, when it was made, how many pearls, and badges for its videos and the
 * languages its pearls are in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(library: RecordingLibrary, entry: RecordingEntry, isSelected: Boolean, onClick: () -> Unit, onFavorite: () -> Unit, onDelete: () -> Unit) {
    var pearls = if (entry.meta.pearls.size == 1) "1 pearl" else "${entry.meta.pearls.size} pearls"
    entry.meta.lecture?.let { pearls = "Lecture · about ${it.minutes} min" } ?: run { if (entry.meta.isTyped) pearls = "Typed · $pearls" }
    val topics = entry.meta.categories.orEmpty()
    val made = entry.videoPearls.count { it.videoFile != null }
    val waiting = entry.videoPearls.count { it.videoGenerationId != null }
    val languages = entry.otherLanguages
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val video = entry.videoPearls.firstOrNull { it.videoFile != null } ?: entry.videoPearls.firstOrNull()
        if (video != null) VideoStill(library, entry.id, video, 50.dp, 50.dp)
        else Box(Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(when {
                entry.meta.lecture != null -> Icons.Filled.School
                entry.meta.isTyped -> Icons.Filled.Keyboard
                else -> Icons.Filled.GraphicEq
            }, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                 color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(entry.meta.created.mediumDateTime() + " · " + pearls + if (topics.isEmpty()) "" else " · " + topics.joinToString(", "),
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            if (made + waiting > 0 || languages.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                    if (made > 0) Badge(if (made == 1) "Video" else "$made videos", Icons.Filled.Movie, BadgeColors.video)
                    if (waiting > 0) Badge(if (waiting == 1) "Video to collect" else "$waiting videos to collect", Icons.Filled.Download, BadgeColors.waiting)
                    languages.forEach { Badge(it, Icons.Filled.Language, BadgeColors.language) }
                }
            }
        }
        IconButton(onClick = onFavorite) {
            Icon(if (entry.meta.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, if (entry.meta.favorite) "Unfavorite" else "Favorite",
                 tint = if (entry.meta.favorite) Color(0xFFF9A825) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A video in the Videos list: its still (tap to play), the recording's title, the pearl and language, what it says in English, and what it cost. */
@Composable
private fun VideoRow(library: RecordingLibrary, entry: RecordingEntry, pearl: Pearl, isSelected: Boolean, play: () -> Unit, onClick: () -> Unit) {
    val language = entry.otherLanguage(pearl)
    // "Pearl 2 · Korean · Kling Avatar v2 Standard · $1.05 · Nov 6, 2026"
    val detail = buildList {
        add("Pearl ${pearl.number}")
        language?.let(::add)
        val endpoint = pearl.videoModel
        if (endpoint != null) {
            add(VideoModel.nameOfEndpoint(endpoint))
            pearl.videoCost?.let { add(it.dollars) }
        } else if (pearl.videoCredits != null) add("ElevenLabs")
        add(pearl.created.mediumDate())
    }.joinToString(" · ")
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = pearl.videoFile != null, onClickLabel = "Play video", onClick = play)) {
            VideoStill(library, entry.id, pearl, 66.dp, 88.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                 color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val words = entry.readableText(pearl)
            if (words != null) Text(words, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else if (language != null) Text("In $language. Open it to see it in English.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (library.videoWork[pearl.id] != null) Badge("Being made", Icons.Filled.HourglassEmpty, BadgeColors.waiting)
            else if (pearl.videoGenerationId != null) Badge("Not collected yet: open to collect", Icons.Filled.Download, BadgeColors.waiting)
        }
        Icon(Icons.Filled.ChevronRight, null, Modifier.align(Alignment.CenterVertically), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TopicsContent(container: AppContainer, search: String, open: (HistoryRoute) -> Unit, openTab: (AppTab) -> Unit) {
    val library = container.library
    val polishers = container.polishers
    val unsorted = library.unsorted
    if (unsorted.isNotEmpty()) {
        val choice = polishers.activeChoice
        FormSection(footer = if (choice != null && library.sorting.isEmpty()) {
            { FooterText("New recordings are sorted automatically. These were made before topics existed. Uses ${polishers.label(choice)}.") }
        } else null) {
            when {
                library.sorting.isNotEmpty() -> ProgressRow("Sorting into topics…")
                choice != null -> TextButton(onClick = { container.scope.launch { library.sortAllUnsorted(choice) } }) {
                    Text(if (unsorted.size == 1) "Sort 1 recording into topics" else "Sort ${unsorted.size} recordings into topics")
                }
                else -> TextButton(onClick = { openTab(AppTab.AI) }) { Text("Choose an AI in the AI tab to sort recordings") }
            }
        }
    }
    val topics = library.topics.filter { search.isEmpty() || it.first.contains(search, ignoreCase = true) }
    if (library.topics.isEmpty() && unsorted.isEmpty()) {
        FormSection { Text("No topics yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp)) }
    } else if (topics.isNotEmpty()) {
        FormSection(footer = { FooterText("Open a topic to see everything you've said about it in order, play it all back to back, and find related reading.") }) {
            topics.forEachIndexed { index, (name, count) ->
                if (index > 0) RowDivider()
                LabeledRow(name, if (count == 1) "1 thought" else "$count thoughts", onClick = { open(HistoryRoute.Topic(name)) })
            }
        }
    }
}

/** One recording: your original voice note and transcript, its topics, every pearl, and reading found for it. */
@Composable
fun RecordingDetail(container: AppContainer, entryId: String, player: AudioPlayback, onBack: (() -> Unit)?, open: (HistoryRoute) -> Unit) {
    val library = container.library
    val polishers = container.polishers
    val settings = container.settings
    val context = LocalContext.current
    val entry = library.entry(entryId)
    library.languageRevision
    var showingForge by remember { mutableStateOf(false) }
    var editingTopics by remember { mutableStateOf(false) }
    var showingTranscript by remember { mutableStateOf(false) }

    if (entry == null) {
        ScreenScaffold("Recording", onBack) { EmptyState(Icons.Filled.Delete, "This recording was deleted", "") }
        return
    }
    val lecture = entry.meta.lecture
    ScreenScaffold(if (lecture == null) "Recording" else "Lecture", onBack, actions = {
        IconButton(onClick = { container.scope.launch { library.setFavorite(!entry.meta.favorite, entry.id) } }) {
            Icon(if (entry.meta.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                 if (entry.meta.favorite) "Remove from favorites" else "Add to favorites",
                 tint = if (entry.meta.favorite) Color(0xFFF9A825) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }) {
        FormSection {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.displayTitle, style = MaterialTheme.typography.titleMedium)
                Text(entry.meta.created.fullDateTime(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (lecture != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.School, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("Lecture · about ${lecture.minutes} min", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                entry.meta.gist?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        TopicsSection(container, entry) { editingTopics = true }

        player.loadedKey?.let { key -> runCatching { UUID.fromString(key) }.getOrNull()?.let(entry::pearl) }?.let { playing ->
            FormSection("Now playing · Pearl ${playing.number}") { PlayerRow(player) }
        }

        FormSection(if (lecture != null) "What you asked for" else if (entry.meta.isTyped) "What you wrote" else "Your original recording") {
            if (entry.meta.isTyped || lecture != null) Text(entry.transcript, modifier = Modifier.padding(vertical = 10.dp))
            val original = entry.meta.originalFile
            if (!entry.meta.isTyped && original != null) {
                AudioItemRow(if (lecture == null) "Play what you said" else "Play your request", "original", player) { library.audioFile(original, entry.id) }
            }
            if (!entry.meta.isTyped && lecture == null) {
                TextButton(onClick = { showingTranscript = !showingTranscript }) { Text(if (showingTranscript) "Hide what you said" else "What you said") }
                if (showingTranscript) Text(entry.transcript, modifier = Modifier.padding(bottom = 10.dp))
            }
        }

        FormSection(if (lecture == null) "Pearls" else "The lecture", footer = {
            val error = library.forgeErrors[entry.id]
            when {
                error != null -> FooterText(error, isError = true)
                lecture != null -> FooterText("Open it to read or edit the text and speak it again. For a different take, ask for another lecture on the Create screen.")
                entry.meta.isTyped -> FooterText("Each pearl is a fresh run of what you wrote through the forge. Your text itself is never changed.")
                else -> FooterText("Each pearl is a fresh run of your original words through the forge. Your recording itself is never changed.")
            }
        }) {
            library.forging[entry.id]?.let { ProgressRow(it.progressLabel) }
            entry.pearlsNewestFirst.forEachIndexed { index, pearl ->
                if (index > 0) RowDivider()
                PearlRow(library, entry, pearl, polishers) { open(HistoryRoute.PearlPage(entry.id, pearl.id)) }
            }
            if (lecture == null) {
                TextButton(onClick = { showingForge = true },
                           enabled = library.forging[entry.id] == null && settings.selectedVoiceId.isNotEmpty() && polishers.readyProviders.isNotEmpty()) {
                    Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Forge a new pearl")
                }
            }
        }

        ReadingSection(container, entry)

        if (lecture != null) LectureCostPaidSection(lecture, "When it was made. Speaking it again uses the ElevenLabs credits again.")
    }

    if (showingForge) {
        ForgeSheet(polishers, entry.pearlsNewestFirst.firstOrNull()?.mode ?: RefinementMode.CLEAN, onDismiss = { showingForge = false }) { mode, notes, choice ->
            showingForge = false
            container.scope.launch {
                library.forgeNewPearl(entry.id, mode, notes, choice, settings.selectedVoiceId)?.let { (pearl, audio) ->
                    player.load(audio, pearl.id.toString())
                    player.play()
                }
            }
        }
    }
    if (editingTopics) TopicEditor(container, entry) { editingTopics = false }
}

@Composable
private fun TopicsSection(container: AppContainer, entry: RecordingEntry, edit: () -> Unit) {
    val library = container.library
    val choice = container.polishers.activeChoice
    val isSorting = library.sorting[entry.id] == true
    FormSection("Topics", footer = library.sortErrors[entry.id]?.let { error -> { FooterText(error, isError = true) } }) {
        val topics = entry.meta.categories
        when {
            isSorting -> ProgressRow("Sorting into topics…")
            !topics.isNullOrEmpty() -> TopicChips(topics)
            else -> Text(if (topics == null) "Not sorted into topics yet." else "No topics.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.padding(vertical = 8.dp))
        }
        Row {
            TextButton(onClick = edit, enabled = !isSorting) { Text("Edit topics") }
            Spacer(Modifier.weight(1f))
            if (choice != null) TextButton(onClick = { container.scope.launch { library.sortIntoTopics(entry.id, choice) } }, enabled = !isSorting) {
                Text(if (topics == null) "Sort automatically" else "Sort again")
            }
        }
    }
}

@Composable
private fun ReadingSection(container: AppContainer, entry: RecordingEntry) {
    val library = container.library
    val polishers = container.polishers
    val context = LocalContext.current
    val isSearching = library.findingReferences[entry.id] == true
    val noClaude = polishers.status[PolisherProvider.ANTHROPIC] == com.solidplisskin.pearlforge.settings.KeyStatus.NotSet
    FormSection(if (entry.meta.lecture == null) "Reading" else "Sources", footer = {
        val error = library.referenceErrors[entry.id]
        val notice = library.referenceNotices[entry.id]
        when {
            error != null -> FooterText(error, isError = true)
            noClaude -> FooterText("Finding reading uses Claude's web search. Add your Claude key in the AI tab.")
            notice != null -> FooterText(notice)
            entry.meta.lecture != null -> FooterText("What the lecture draws on, each checked against the actual search results. Also kept in the Reading tab.")
            entry.meta.references.isEmpty() -> FooterText("Searches PubMed for peer-reviewed research and the web for essays, articles and blog posts related to this thought. Everything found is also kept in the Reading tab.")
        }
    }) {
        entry.meta.references.forEachIndexed { index, reference ->
            if (index > 0) RowDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { ReferenceRow(reference) { openUrl(context, reference.url) } }
                IconButton(onClick = { container.scope.launch { library.deleteReference(reference.id, entry.id) } }) {
                    Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (isSearching) ProgressRow("Searching PubMed and the web… this can take a minute or two.")
        else TextButton(onClick = { container.scope.launch { library.findReferences(entry.id, polishers.readingModel) } }, enabled = !noClaude) {
            Icon(Icons.Filled.LibraryBooks, null); Spacer(Modifier.width(8.dp))
            Text(if (entry.meta.references.isEmpty()) "Find reading" else "Find more reading")
        }
    }
}

/** "Claude · Claude Opus 5.5" for a pearl, when known. */
fun polishedBy(pearl: Pearl, polishers: PolishersModel): String? {
    val model = pearl.refinedBy ?: return null
    val provider = PolisherProvider.from(pearl.polisher) ?: return model
    return "${provider.displayName} · ${polishers.modelName(model, provider)}"
}

/**
 * A pearl in the list: a still from its video if it has one, its number, mode and language, its AI,
 * your request, and how it starts (in English when it's in another language, with the original underneath).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PearlRow(library: RecordingLibrary, entry: RecordingEntry, pearl: Pearl, polishers: PolishersModel, onClick: () -> Unit) {
    val text = entry.pearlTexts[pearl.id] ?: ""
    val language = entry.otherLanguage(pearl)
    val english = pearl.english
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        if (pearl.videoFile != null || pearl.videoGenerationId != null) {
            VideoStill(library, entry.id, pearl, 48.dp, 64.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Pearl ${pearl.number} · ${pearl.mode.title}", style = MaterialTheme.typography.titleSmall)
            polishedBy(pearl, polishers)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            pearl.notes?.takeIf { it.isNotEmpty() && !isLanguageRequest(it) }?.let {
                Text("“$it”", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (language != null && english != null) {
                Text(english, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (library.translating[pearl.id] == true) {
                    Text("Writing it in English…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (language != null || pearl.videoFile != null || pearl.videoGenerationId != null || library.videoWork[pearl.id] != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                    language?.let { Badge(it, Icons.Filled.Language, BadgeColors.language) }
                    when {
                        library.videoWork[pearl.id] != null -> Badge("Making video", Icons.Filled.HourglassEmpty, BadgeColors.waiting)
                        pearl.videoGenerationId != null -> Badge("Video to collect", Icons.Filled.Download, BadgeColors.waiting)
                        pearl.videoFile != null -> Badge("Video", Icons.Filled.Movie, BadgeColors.video)
                    }
                }
            }
            Text(pearl.created.mediumDateTime() + if (pearl.audioFile == null) " · no audio yet" else "",
                 style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ChevronRight, null, Modifier.align(Alignment.CenterVertically), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The request Pearl Forge adds for "Speak in"; the language badge says it. */
private fun isLanguageRequest(notes: String) = SpeechLanguages.all.any { SpeechLanguages.request(it) == notes }

/** A button that loads a saved audio file into the shared player and plays it. */
@Composable
fun AudioItemRow(title: String, key: String, player: AudioPlayback, file: () -> File) {
    var error by remember { mutableStateOf<String?>(null) }
    Column {
        TextButton(onClick = {
            error = null
            if (player.loadedKey == key) player.togglePlay()
            else runCatching { player.load(file(), key); player.play() }.onFailure { error = "Couldn't play it: ${it.message}" }
        }) {
            val playing = player.loadedKey == key && player.isPlaying
            Icon(if (playing) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle, null); Spacer(Modifier.width(8.dp))
            Text(if (playing) "Pause" else title)
        }
        if (player.loadedKey == key) PlayerRow(player)
        error?.let { FooterText(it, isError = true) }
    }
}

/** One pearl: listen to it, share it, turn it into a video of your avatar, edit its text and regenerate the audio, or delete it. */
@Composable
fun PearlPage(container: AppContainer, entryId: String, pearlId: UUID, player: AudioPlayback, onBack: () -> Unit) {
    val library = container.library
    val settings = container.settings
    val polishers = container.polishers
    val context = LocalContext.current
    val entry = library.entry(entryId)
    val pearl = entry?.pearl(pearlId)
    library.languageRevision
    LaunchedEffect(Unit) {
        if (settings.videoModel.isFal) {
            if (settings.falStatus == KeyStatus.Saved) settings.checkFalKey()
        } else if (settings.credits == null) settings.refreshCredits()
    }
    if (entry == null || pearl == null) {
        ScreenScaffold("Pearl", onBack) { EmptyState(Icons.Filled.Delete, "This pearl was deleted", "") }
        return
    }
    val savedText = entry.pearlTexts[pearlId] ?: ""
    var text by remember(pearlId) { mutableStateOf(savedText) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var playingVideo by remember { mutableStateOf<PlayingVideo?>(null) }
    val isBusy = library.forging[entryId] != null
    val isEdited = text.trim() != savedText.trim()
    val language = entry.otherLanguage(pearl)

    fun respeak(newText: String) = container.scope.launch {
        library.respeak(pearlId, entryId, newText, settings.selectedVoiceId)?.let { audio ->
            text = newText.trim()
            player.load(audio, pearlId.toString())
            player.play()
            // Edited text loses its English version; write a fresh one.
            polishers.activeChoice?.let { library.addEnglishIfMissing(pearlId, entryId, it) }
        }
    }

    ScreenScaffold("Pearl ${pearl.number}", onBack) {
        FormSection {
            LabeledRow("Mode", pearl.mode.title)
            language?.let { RowDivider(); LabeledRow("Language", it) }
            pearl.notes?.takeIf { it.isNotEmpty() }?.let {
                RowDivider()
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text("Your request", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it)
                }
            }
            RowDivider()
            LabeledRow("Forged", pearl.created.mediumDateTime())
            polishedBy(pearl, polishers)?.let { RowDivider(); LabeledRow("Polished by", it) }
        }

        FormSection("In your voice", footer = library.forgeErrors[entryId]?.let { error -> { FooterText(error, isError = true) } }) {
            val step = library.forging[entryId]
            val audioFile = pearl.audioFile
            when {
                step != null -> ProgressRow(step.progressLabel)
                audioFile != null -> {
                    AudioItemRow("Play pearl ${pearl.number}", pearl.id.toString(), player) { library.audioFile(audioFile, entryId) }
                    TextButton(onClick = {
                        val copy = File(context.cacheDir, "shared").apply { mkdirs() }.resolve(RecordingFiles.shareName(entry.meta.title, pearl.number))
                        library.audioFile(audioFile, entryId).copyTo(copy, overwrite = true)
                        shareFile(context, copy, "audio/mpeg")
                    }) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp)); Text("Share audio") }
                }
                else -> TextButton(onClick = { respeak(savedText) }) { Text("Speak it in your voice") }
            }
        }

        VideoSection(container, entry, pearl, isBusy) {
            player.pause()
            runCatching { PlayingVideo.of(library, entry, pearl) }.onSuccess { playingVideo = it }
                .onFailure { library.videoErrors[pearlId] = "Couldn't open the video: ${it.message}" }
        }

        if (language != null) EnglishSection(container, entryId, pearl, language, isEdited)

        FormSection(language?.let { "Refined text · $it" } ?: "Refined text", footer = { FooterText("If anything's off, fix it here, then regenerate the audio.") }) {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp).padding(vertical = 8.dp), enabled = !isBusy)
            if (isEdited) {
                TextButton(onClick = { respeak(text) }, enabled = !isBusy) { Text("Save edits & regenerate audio") }
                TextButton(onClick = { text = savedText }) { Text("Undo edits") }
            } else {
                TextButton(onClick = { shareText(context, savedText) }) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp)); Text("Share text") }
            }
        }

        FormSection {
            TextButton(onClick = { confirmingDelete = true }, enabled = !isBusy) { Text("Delete this pearl", color = MaterialTheme.colorScheme.error) }
        }
    }

    playingVideo?.let { VideoPlayerDialog(it) { playingVideo = null } }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete pearl ${pearl.number}?") },
            text = { Text("Its text and audio will be deleted. Your original recording and other pearls stay.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    if (player.loadedKey == pearl.id.toString()) player.unload()
                    container.scope.launch { library.deletePearl(pearl.id, entryId) }
                    onBack()
                }) { Text("Delete pearl", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

/** What a pearl in another language says, in English. */
@Composable
private fun EnglishSection(container: AppContainer, entryId: String, pearl: Pearl, language: String, isEdited: Boolean) {
    val library = container.library
    val polishers = container.polishers
    val choice = polishers.activeChoice
    val english = pearl.english
    FormSection("In English", footer = {
        val error = library.translationErrors[pearl.id]
        when {
            error != null -> FooterText(error, isError = true)
            english != null -> FooterText("What this pearl says in $language, translated back so you can follow it. Your pearl itself isn't changed.")
            choice != null -> FooterText("${polishers.label(choice)} translates this $language pearl back into English so you know what it says. It's saved with the pearl; a short pearl costs about a cent.")
        }
    }) {
        when {
            library.translating[pearl.id] == true -> ProgressRow("Writing it in English…")
            english != null -> SelectionContainer { Text(english, modifier = Modifier.padding(vertical = 10.dp)) }
            choice != null -> TextButton(onClick = { container.scope.launch { library.addEnglish(pearl.id, entryId, choice) } }, enabled = !isEdited) {
                Icon(Icons.Filled.Translate, null); Spacer(Modifier.width(8.dp)); Text("Show it in English")
            }
            else -> Text("Choose an AI in the AI tab to see this pearl in English.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.padding(vertical = 10.dp))
        }
    }
}

/** The pearl as a video: play or share it, make one of your avatar speaking it, collect one still being made, or add one you made elsewhere. */
@Composable
private fun VideoSection(container: AppContainer, entry: RecordingEntry, pearl: Pearl, isBusy: Boolean, play: () -> Unit) {
    val library = container.library
    val settings = container.settings
    val context = LocalContext.current
    val working = library.videoWork[pearl.id]
    val model = settings.videoModel
    val planAllows = if (model.isFal) settings.falStatus != KeyStatus.NotSet else AvatarVideo.planAllowsApp(settings.credits?.tier) != false
    var actionError by remember { mutableStateOf<String?>(null) }
    var quote by remember { mutableStateOf<String?>(null) }
    var quotedModel by remember { mutableStateOf<VideoModel?>(null) }

    fun add(uri: Uri) = container.scope.launch {
        runCatching { library.addVideo(uri, pearl.id, entry.id) }
            .onSuccess { actionError = null }
            .onFailure { actionError = "Couldn't add that video: ${it.message}" }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::add) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::add) }

    /** Works out what a video of this pearl should cost, then asks. */
    fun quoteVideo() = container.scope.launch {
        actionError = null
        val audio = pearl.audioFile?.let { library.audioFile(it, entry.id) }
        val seconds = audio?.let { withContext(Dispatchers.IO) { AvatarVideo.seconds(it) } }
        if (seconds == null) {
            actionError = "This pearl's audio couldn't be loaded."
            return@launch
        }
        val length = timeString(seconds)
        val chosen = settings.videoModel
        val lines = mutableListOf<String>()
        if (chosen.isFal) {
            settings.refreshFalBalance()
            val longest = chosen.maxSeconds
            if (longest != null && seconds > longest) {
                actionError = "${chosen.name} takes pearls up to ${timeString(longest)}, and this one runs $length. Choose another model in Settings › Video avatar."
                return@launch
            }
            val cost = chosen.cost(seconds)
            val rate = chosen.dollarsPerSecond
            if (cost != null && rate != null) {
                lines.add("This pearl runs $length. With ${chosen.name} it should cost about ${cost.dollars} (fal.ai charges ${perSecond(rate)} a second).")
            }
            settings.falBalance?.let { lines.add("Your fal.ai balance is ${it.dollars}.") }
        } else {
            val rate = AvatarVideo.creditsPerSecond(library.entries.flatMap { it.meta.pearls })
            if (rate != null) {
                val credits = (rate * seconds).roundToInt()
                var line = "This pearl runs $length. Judging by your last videos, it should use about ${"%,d".format(credits)} credits"
                settings.credits?.dollarsPerThousand?.let { line += " (about ${(credits / 1000.0 * it).dollars} of your plan)" }
                lines.add("$line.")
            } else {
                lines.add("This pearl runs $length. ElevenLabs doesn't publish what these videos cost, so Pearl Forge will measure this one and estimate the next ones from it. A short pearl is a good first try.")
            }
            settings.credits?.let { lines.add("You have ${"%,d".format(it.remaining)} credits left this month.") }
        }
        lines.add("It takes a few minutes. Keep Pearl Forge open; if you leave, come back to this pearl to collect the video.")
        quotedModel = chosen
        quote = lines.joinToString("\n\n")
    }

    FormSection("As a video", footer = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (library.videoErrors[pearl.id] ?: actionError)?.let { FooterText(it, isError = true) }
            if (working == null && pearl.videoFile == null) {
                FooterText(when {
                    settings.avatarPicture == null ->
                        "To make videos here, choose a picture of your avatar in Settings › Video avatar. Or make the video in ElevenLabs Avatars (Share audio sends it this pearl's speech), save it, and add it here."
                    !planAllows && model.isFal -> "To make videos with ${model.name}, add your fal.ai key in Settings › API keys."
                    !planAllows -> AvatarVideo.NEEDS_PRO_MESSAGE + " Or choose a fal.ai model in Settings › Video avatar. Share audio sends ElevenLabs this pearl's speech."
                    else -> "Make video sends your avatar picture and this pearl's audio to ${if (model.isFal) "fal.ai (${model.name})" else "ElevenLabs"}, which makes a video of your avatar speaking it. You'll see what it should cost first. To use a different model, go to Settings › Video avatar."
                })
            }
            if (pearl.videoGenerationId == null) {
                val credits = pearl.videoCredits
                val endpoint = pearl.videoModel
                val cost = pearl.videoCost
                if (credits != null) FooterText("Making this video used ${"%,d".format(credits)} ElevenLabs credits.")
                else if (endpoint != null && cost != null) FooterText("Made by ${VideoModel.nameOfEndpoint(endpoint)} on fal.ai for about ${cost.dollars}.")
            }
        }
    }) {
        if (working != null) {
            ProgressRow(working)
        } else {
            if (pearl.videoFile != null) {
                Row(Modifier.fillMaxWidth().clickable(onClick = play).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    VideoStill(library, entry.id, pearl, 72.dp, 96.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Play video")
                        pearl.videoSeconds?.let { Text(timeString(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        entry.otherLanguage(pearl)?.let { language ->
                            Text(if (pearl.english == null) "Shows the $language text as you watch" else "Shows what you're saying in English as you watch",
                                 style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = {
                    container.scope.launch {
                        runCatching { library.videoShareCopy(pearl.id, entry.id) }
                            .onSuccess { shareFile(context, it, videoMimeType(it)) }
                            .onFailure { actionError = "Couldn't prepare the video: ${it.message}" }
                    }
                }) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp)); Text("Share video") }
            }
            if (pearl.videoGenerationId != null) {
                TextButton(onClick = { container.scope.launch { library.collectVideo(pearl.id, entry.id) } }) {
                    Icon(Icons.Filled.Download, null); Spacer(Modifier.width(8.dp))
                    Text("Collect your video from ${if (pearl.videoModel == null) "ElevenLabs" else "fal.ai"}")
                }
            } else if (pearl.audioFile != null) {
                TextButton(onClick = { quoteVideo() }, enabled = !isBusy && settings.avatarPicture != null && planAllows) {
                    Icon(Icons.Filled.RecentActors, null); Spacer(Modifier.width(8.dp))
                    Text(if (pearl.videoFile == null) "Make video" else "Make a new video")
                }
            }
            TextButton(onClick = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) {
                Icon(Icons.Filled.PhotoLibrary, null); Spacer(Modifier.width(8.dp))
                Text(if (pearl.videoFile == null) "Add video from Photos" else "Replace with a video from Photos")
            }
            TextButton(onClick = { files.launch(arrayOf("video/*")) }) {
                Icon(Icons.Filled.Folder, null); Spacer(Modifier.width(8.dp))
                Text(if (pearl.videoFile == null) "Add video from Files" else "Replace with a video from Files")
            }
            if (pearl.videoFile != null) {
                TextButton(onClick = {
                    container.scope.launch { runCatching { library.removeVideo(pearl.id, entry.id) }.onFailure { actionError = it.message } }
                }) { Text("Remove video", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    quote?.let { message ->
        AlertDialog(
            onDismissRequest = { quote = null },
            title = { Text("Make a video of pearl ${pearl.number}?") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    quote = null
                    val picture = settings.avatarPicture
                    val chosen = quotedModel
                    if (picture != null && chosen != null) container.scope.launch { library.makeVideo(pearl.id, entry.id, picture, chosen) }
                }) { Text("Make video") }
            },
            dismissButton = { TextButton(onClick = { quote = null }) { Text("Cancel") } },
        )
    }
}

/** "$0.0562", "$0.14": fal.ai's price per second, to as many places as it has. */
private fun perSecond(rate: Double): String = NumberFormat.getCurrencyInstance(Locale.US).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 4
}.format(rate)

/** Choose how to forge another pearl: the mode, which AI polishes it, the language, and anything you want different this time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForgeSheet(polishers: PolishersModel, initialMode: RefinementMode, onDismiss: () -> Unit,
                       onForge: (RefinementMode, String?, PolisherChoice) -> Unit) {
    var mode by remember { mutableStateOf(if (initialMode in RefinementMode.polishingModes) initialMode else RefinementMode.CLEAN) }
    var provider by remember { mutableStateOf(polishers.activeChoice?.provider ?: polishers.readyProviders.firstOrNull() ?: PolisherProvider.ANTHROPIC) }
    var notes by remember { mutableStateOf("") }
    var language by remember { mutableStateOf<SpeechLanguage?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Forge a new pearl", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = {
                    val choice = polishers.choice(provider) ?: return@TextButton
                    val request = listOfNotNull(language?.let(SpeechLanguages::request), notes.trim().ifEmpty { null }).joinToString(" ")
                    onForge(mode, request.ifEmpty { null }, choice)
                }, enabled = polishers.choice(provider) != null) { Text("Forge", fontWeight = FontWeight.SemiBold) }
            }
            FormSection("How much refinement?", footer = { FooterText(mode.subtitleSentence) }) {
                Column(Modifier.padding(vertical = 8.dp)) { Segmented(RefinementMode.polishingModes, mode, { it.title }) { mode = it } }
            }
            FormSection(footer = { FooterText("Change models for each service in the AI tab.") }) {
                MenuPicker("Polish with", polishers.choice(provider)?.let(polishers::label) ?: provider.displayName, polishers.readyProviders,
                           { p -> polishers.choice(p)?.let(polishers::label) ?: p.displayName }) { provider = it }
            }
            FormSection(footer = language?.let { { FooterText("Translated into ${it.name} and spoken in your voice. Your meaning and tone are kept.") } }) {
                LanguagePicker(language, "The recording's language") { language = it }
            }
            FormSection("Anything to change this time?", footer = {
                FooterText("For example: “keep the bridge analogy”, “make it under a minute”, or “drop the tangent about work”. Leave it blank to use the mode as is. Your original recording is always the starting point.")
            }) {
                OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), placeholder = { Text("Optional") }, minLines = 2, maxLines = 8)
            }
        }
    }
}

/** Choose a recording's topics by hand: tick existing ones or add a new one. */
@Composable
private fun TopicEditor(container: AppContainer, entry: RecordingEntry, onDismiss: () -> Unit) {
    val library = container.library
    val selected = remember { mutableStateListOf<String>().apply { addAll(entry.meta.categories.orEmpty()) } }
    var newTopic by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    // Your topics first, then the starter topics you haven't used yet, plus any just added here.
    val choices = library.topicNames.toMutableList().apply {
        for (name in TopicSorter.starterTopics + selected) if (none { it.equals(name, ignoreCase = true) }) add(name)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Topics") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScrollable()) {
                choices.forEach { name ->
                    Row(Modifier.fillMaxWidth().clickable { if (name in selected) selected.remove(name) else selected.add(name) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = name in selected, onCheckedChange = { if (it) selected.add(name) else selected.remove(name) })
                        Text(name)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newTopic, { newTopic = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("New topic") })
                    TextButton(onClick = {
                        val name = newTopic.trim()
                        if (name.isNotEmpty() && selected.none { it.equals(name, ignoreCase = true) }) selected.add(name)
                        newTopic = ""
                    }, enabled = newTopic.isNotBlank()) { Text("Add") }
                }
                saveError?.let { FooterText(it, isError = true) }
                FooterText("Recordings that share a topic are gathered together under Topics in History.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                container.scope.launch {
                    runCatching { library.setCategories(selected.toList(), entry.id) }
                        .onSuccess { onDismiss() }.onFailure { saveError = it.message }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Modifier.verticalScrollable(): Modifier = this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

/** Plays a topic's recordings one after another, each in its newest pearl. */
class TopicQueue(val player: AudioPlayback) {
    data class Item(val entryId: String, val title: String, val file: File)

    var items by mutableStateOf<List<Item>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    val current: Item? get() = items.getOrNull(index)

    init {
        player.onFinish = { next() }
    }

    fun start(entries: List<RecordingEntry>, library: com.solidplisskin.pearlforge.recordings.RecordingLibrary) {
        items = entries.mapNotNull { entry ->
            entry.latestSpokenPearl?.audioFile?.let { Item(entry.id, entry.displayTitle, library.audioFile(it, entry.id)) }
        }
        index = 0
        playCurrent()
    }

    fun next() {
        if (index + 1 >= items.size) stop() else { index += 1; playCurrent() }
    }

    fun previous() {
        if (index > 0) { index -= 1; playCurrent() }
    }

    fun stop() {
        player.unload()
        items = emptyList()
        index = 0
    }

    private fun playCurrent() {
        val item = current ?: return
        loadError = null
        runCatching { player.load(item.file, item.entryId); player.play() }
            .onFailure { loadError = "Couldn't load “${item.title}”: ${it.message}" }
    }
}

/** Everything you've said about one topic, oldest first, with a button to play them back to back, and the reading found for them. */
@Composable
fun TopicPage(container: AppContainer, topic: String, onBack: () -> Unit, open: (HistoryRoute) -> Unit) {
    val library = container.library
    val context = LocalContext.current
    val queue = remember { TopicQueue(AudioPlayback(context)) }
    DisposableEffect(Unit) { onDispose { queue.stop() } }
    val entries = library.entriesInTopic(topic)
    val seen = mutableSetOf<String>()
    val references = entries.reversed().flatMap { entry -> entry.meta.references.map { it to entry } }
        .filter { seen.add(ResearchLoop.normalize(it.first.url) ?: it.first.url) }
    val playable = entries.filter { it.latestSpokenPearl != null }

    ScreenScaffold(topic, onBack) {
        FormSection(footer = {
            when {
                queue.loadError != null -> FooterText(queue.loadError!!, isError = true)
                queue.items.isEmpty() -> FooterText("Plays the newest pearl of each thought, one after another, in your voice.")
            }
        }) {
            val item = queue.current
            if (item != null) {
                Text("${queue.index + 1} of ${queue.items.size} · ${item.title}", style = MaterialTheme.typography.titleSmall, maxLines = 2,
                     modifier = Modifier.padding(top = 8.dp))
                PlayerRow(queue.player)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { queue.previous() }, enabled = queue.index > 0) { Icon(Icons.Filled.SkipPrevious, "Previous") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { queue.stop() }) { Icon(Icons.Filled.Stop, "Stop", tint = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { queue.next() }, enabled = queue.index + 1 < queue.items.size) { Icon(Icons.Filled.SkipNext, "Next") }
                }
            } else {
                TextButton(onClick = { queue.start(playable, library) }, enabled = playable.isNotEmpty()) {
                    Icon(Icons.Filled.PlayCircle, null); Spacer(Modifier.width(8.dp)); Text("Play all in order", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        FormSection(if (entries.size == 1) "1 thought" else "${entries.size} thoughts", footer = { FooterText("Oldest first, so you can follow how your thinking developed.") }) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                Column(Modifier.fillMaxWidth().clickable { open(HistoryRoute.Recording(entry.id)) }.padding(vertical = 8.dp)) {
                    Text(entry.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    entry.meta.gist?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3) }
                    Text(entry.meta.created.mediumDateTime(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (entries.isEmpty()) Text("No recordings in this topic", modifier = Modifier.padding(vertical = 10.dp))
        }
        if (references.isNotEmpty()) {
            FormSection("Reading") {
                references.forEachIndexed { index, (reference, entry) ->
                    if (index > 0) RowDivider()
                    ReferenceRow(reference, entry.displayTitle) { openUrl(context, reference.url) }
                }
            }
        }
    }
}
