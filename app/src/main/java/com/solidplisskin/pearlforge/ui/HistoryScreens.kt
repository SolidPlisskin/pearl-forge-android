package com.solidplisskin.pearlforge.ui

import androidx.compose.foundation.clickable
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

/** Every saved recording, newest first, with search and favorites, or gathered by topic. */
@Composable
fun HistoryList(container: AppContainer, selectedId: String?, open: (HistoryRoute) -> Unit, openTab: (AppTab) -> Unit) {
    val library = container.library
    val polishers = container.polishers
    var showTopics by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RecordingEntry?>(null) }
    val shown = library.entries.filter { entry -> (!favoritesOnly || entry.meta.favorite) && (search.isEmpty() || matches(entry, search)) }

    ScreenScaffold("History", actions = {
        if (!showTopics) IconButton(onClick = { favoritesOnly = !favoritesOnly }) {
            Icon(if (favoritesOnly) Icons.Filled.Star else Icons.Filled.StarBorder, if (favoritesOnly) "Show all recordings" else "Show favorites only")
        }
    }) {
        Segmented(listOf(false, true), showTopics, { if (it) "Topics" else "Recordings" }) { showTopics = it }
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) },
                          placeholder = { Text(if (showTopics) "Search topics" else "Search your thoughts") })
        if (library.entries.isEmpty()) {
            EmptyState(Icons.Filled.History, "No recordings yet", "Every thought you refine is saved here: the original recording and each pearl you forge from it.")
        } else if (showTopics) {
            TopicsContent(container, search, open, openTab)
        } else if (shown.isEmpty()) {
            if (search.isNotEmpty()) EmptyState(Icons.Filled.Search, "No results", "Nothing matches “$search”.")
            else EmptyState(Icons.Filled.Star, "No favorites yet", "Tap the star on a recording to make it a favorite.")
        } else {
            FormSection {
                shown.forEachIndexed { index, entry ->
                    if (index > 0) RowDivider()
                    HistoryRow(entry, entry.id == selectedId, onClick = { open(HistoryRoute.Recording(entry.id)) },
                               onFavorite = { container.scope.launch { library.setFavorite(!entry.meta.favorite, entry.id) } },
                               onDelete = { pendingDelete = entry })
                }
            }
        }
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
    (listOf(entry.meta.title, entry.transcript, entry.meta.gist ?: "") + entry.meta.categories.orEmpty() + entry.pearlTexts.values)
        .any { it.contains(query, ignoreCase = true) }

/** A recording in the list: its title, when it was made, how many pearls, and its topics. */
@Composable
private fun HistoryRow(entry: RecordingEntry, isSelected: Boolean, onClick: () -> Unit, onFavorite: () -> Unit, onDelete: () -> Unit) {
    var pearls = if (entry.meta.pearls.size == 1) "1 pearl" else "${entry.meta.pearls.size} pearls"
    entry.meta.lecture?.let { pearls = "Lecture · about ${it.minutes} min" } ?: run { if (entry.meta.isTyped) pearls = "Typed · $pearls" }
    val topics = entry.meta.categories.orEmpty()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.meta.title.ifEmpty { "Untitled thought" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                 color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(entry.meta.created.mediumDateTime() + " · " + pearls + if (topics.isEmpty()) "" else " · " + topics.joinToString(", "),
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        IconButton(onClick = onFavorite) {
            Icon(if (entry.meta.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, if (entry.meta.favorite) "Unfavorite" else "Favorite",
                 tint = if (entry.meta.favorite) Color(0xFFF9A825) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                Text(entry.meta.title.ifEmpty { "Untitled thought" }, style = MaterialTheme.typography.titleMedium)
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
                PearlRow(pearl, entry.pearlTexts[pearl.id] ?: "", polishers) { open(HistoryRoute.PearlPage(entry.id, pearl.id)) }
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

/** A pearl in the list: its number, mode and AI, your request, and how it starts. */
@Composable
private fun PearlRow(pearl: Pearl, text: String, polishers: PolishersModel, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Pearl ${pearl.number} · ${pearl.mode.title}", style = MaterialTheme.typography.titleSmall)
            polishedBy(pearl, polishers)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            pearl.notes?.takeIf { it.isNotEmpty() }?.let {
                Text("“$it”", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(pearl.created.mediumDateTime() + if (pearl.audioFile == null) " · no audio yet" else "",
                 style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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

/** One pearl: listen to it, share it, edit its text and regenerate the audio, or delete it. */
@Composable
fun PearlPage(container: AppContainer, entryId: String, pearlId: UUID, player: AudioPlayback, onBack: () -> Unit) {
    val library = container.library
    val context = LocalContext.current
    val entry = library.entry(entryId)
    val pearl = entry?.pearl(pearlId)
    if (entry == null || pearl == null) {
        ScreenScaffold("Pearl", onBack) { EmptyState(Icons.Filled.Delete, "This pearl was deleted", "") }
        return
    }
    val savedText = entry.pearlTexts[pearlId] ?: ""
    var text by remember(pearlId) { mutableStateOf(savedText) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val isBusy = library.forging[entryId] != null
    val isEdited = text.trim() != savedText.trim()

    fun respeak(newText: String) = container.scope.launch {
        library.respeak(pearlId, entryId, newText, container.settings.selectedVoiceId)?.let { audio ->
            text = newText.trim()
            player.load(audio, pearlId.toString())
            player.play()
        }
    }

    ScreenScaffold("Pearl ${pearl.number}", onBack) {
        FormSection {
            LabeledRow("Mode", pearl.mode.title)
            pearl.notes?.takeIf { it.isNotEmpty() }?.let {
                RowDivider()
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text("Your request", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it)
                }
            }
            RowDivider()
            LabeledRow("Forged", pearl.created.mediumDateTime())
            polishedBy(pearl, container.polishers)?.let { RowDivider(); LabeledRow("Polished by", it) }
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

        FormSection("Refined text", footer = { FooterText("If anything's off, fix it here, then regenerate the audio.") }) {
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
            entry.latestSpokenPearl?.audioFile?.let { Item(entry.id, entry.meta.title.ifEmpty { "Untitled thought" }, library.audioFile(it, entry.id)) }
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
                    Text(entry.meta.title.ifEmpty { "Untitled thought" }, style = MaterialTheme.typography.titleSmall, maxLines = 2)
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
                    ReferenceRow(reference, entry.meta.title) { openUrl(context, reference.url) }
                }
            }
        }
    }
}
