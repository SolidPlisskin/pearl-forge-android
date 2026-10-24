package com.solidplisskin.pearlforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.solidplisskin.pearlforge.AppContainer
import com.solidplisskin.pearlforge.pipeline.PolisherProvider
import com.solidplisskin.pearlforge.recordings.ReferenceKind
import com.solidplisskin.pearlforge.settings.KeyStatus
import kotlinx.coroutines.launch

/** Every reference found for your recordings, filterable by kind and topic. */
@Composable
fun ReadingScreen(container: AppContainer) {
    val library = container.library
    val context = LocalContext.current
    var kind by remember { mutableStateOf("All") }
    var topic by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var topicMenu by remember { mutableStateOf(false) }
    val shown = library.allReferences.filter { (reference, entry) ->
        (kind == "All" || (kind == "Research") == (reference.kind == ReferenceKind.RESEARCH)) &&
            (topic == null || entry.meta.categories?.contains(topic) == true) &&
            (search.isEmpty() || listOf(reference.title, reference.source, reference.note, reference.authors ?: "").any { it.contains(search, ignoreCase = true) })
    }
    ScreenScaffold("Reading", actions = {
        Row {
            IconButton(onClick = { topicMenu = true }) { Icon(Icons.Filled.FilterList, topic?.let { "Filtered to $it" } ?: "Filter by topic") }
            DropdownMenu(expanded = topicMenu, onDismissRequest = { topicMenu = false }) {
                DropdownMenuItem(text = { Text("All topics") }, onClick = { topic = null; topicMenu = false },
                                 trailingIcon = if (topic == null) ({ Icon(Icons.Filled.Check, null) }) else null)
                library.topicNames.forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { topic = name; topicMenu = false },
                                     trailingIcon = if (topic == name) ({ Icon(Icons.Filled.Check, null) }) else null)
                }
            }
        }
    }) {
        Segmented(listOf("All", "Research", "Articles & essays"), kind, { it }) { kind = it }
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) },
                          placeholder = { Text("Search your reading") })
        topic?.let { FooterText("Showing reading for $it.") }
        when {
            library.allReferences.isEmpty() -> EmptyState(Icons.Filled.LibraryBooks, "No reading yet",
                "Open a recording in History and tap Find reading. Pearl Forge searches PubMed and the web for research, essays and articles related to your thought, and keeps them here.")
            shown.isEmpty() -> EmptyState(Icons.Filled.FilterList, "Nothing here yet", "Try a different filter.")
            else -> FormSection {
                shown.forEachIndexed { index, (reference, entry) ->
                    if (index > 0) RowDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { ReferenceRow(reference, entry.meta.title) { openUrl(context, reference.url) } }
                        Column {
                            IconButton(onClick = { shareText(context, reference.url) }) { Icon(Icons.Filled.Share, "Share link", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            IconButton(onClick = { copyText(context, reference.url) }) { Icon(Icons.Filled.ContentCopy, "Copy link", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            IconButton(onClick = { container.scope.launch { library.deleteReference(reference.id, entry.id) } }) {
                                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Choose which AI polishes your thoughts, and set up each service. */
@Composable
fun AIHome(container: AppContainer, open: (AIRoute) -> Unit) {
    val polishers = container.polishers
    ScreenScaffold("AI") {
        val ready = polishers.readyProviders
        FormSection("Polishing with", footer = {
            FooterText("Every AI gets the same polishing instructions, word for word. When you forge a new pearl from History, you can pick a different AI for just that pearl and compare.")
        }) {
            if (ready.isEmpty()) Text("Set up one of the services below to start polishing.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                      modifier = Modifier.padding(vertical = 10.dp))
            ready.forEach { provider ->
                val choice = polishers.choice(provider) ?: return@forEach
                Row(Modifier.fillMaxWidth().clickable { polishers.polishWith(provider) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = polishers.active == provider, onClick = { polishers.polishWith(provider) })
                    Column {
                        Text(provider.displayName)
                        Text(polishers.modelName(choice.model, provider), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val claude = polishers.status[PolisherProvider.ANTHROPIC]
        FormSection("Finding reading", footer = {
            FooterText(if (claude == KeyStatus.NotSet) "Find reading searches the web with Claude, so it needs your Claude key, whichever AI polishes."
                       else "Find reading always uses Claude, because it searches the web with Claude's search tool. Each search costs roughly 5–15¢ with Sonnet 5.5, or about 25¢ with Opus 5.5, which digs a little deeper.")
        }) {
            LabeledRow("Model", polishers.readingModelLabel, onClick = if (claude == KeyStatus.Works) ({ open(AIRoute.Models(PolisherProvider.ANTHROPIC, forReading = true)) }) else null)
        }
        FormSection("AI services", footer = { FooterText("Each service needs its own API key and bills your account with it directly. Keys are kept encrypted on this tablet.") }) {
            PolisherProvider.entries.forEachIndexed { index, provider ->
                if (index > 0) RowDivider()
                val model = polishers.selectedModels[provider]
                val subtitle = if (model != null && polishers.status[provider] != KeyStatus.NotSet) polishers.modelName(model, provider) else provider.company
                KeyStatusRow(provider.displayName, subtitle, polishers.status[provider] ?: KeyStatus.NotSet) { open(AIRoute.Provider(provider)) }
            }
        }
    }
}

/** One service: its key, which of its models to use, and whether it's the default polisher. */
@Composable
fun ProviderPage(container: AppContainer, provider: PolisherProvider, onBack: () -> Unit, open: (AIRoute) -> Unit) {
    val polishers = container.polishers
    val context = LocalContext.current
    val status = polishers.status[provider] ?: KeyStatus.NotSet
    ScreenScaffold(provider.displayName, onBack) {
        FormSection(provider.company, footer = {
            Column {
                if (status is KeyStatus.Failed) FooterText(status.message, isError = true)
                FooterText(provider.about)
                if (status == KeyStatus.NotSet) TextButton(onClick = { openUrl(context, provider.keysPage) }) { Text(provider.keysPageLabel) }
            }
        }) {
            KeyStatusRow("API key", null, status) { open(AIRoute.Key(provider)) }
            if (status is KeyStatus.Failed) TextButton(onClick = { container.scope.launch { polishers.check(provider) } }) { Text("Check again") }
        }
        FormSection("Model", footer = if (status == KeyStatus.Works) {
            { FooterText("Bigger models usually keep your meaning and voice more faithfully; smaller ones are faster and cheaper.") }
        } else null) {
            when (status) {
                KeyStatus.NotSet -> Text("Add your key to see the models you can use.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
                KeyStatus.Saved, KeyStatus.Checking -> ProgressRow("Loading models…")
                is KeyStatus.Failed -> Text("The models couldn't be loaded. Check your key above.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
                KeyStatus.Works -> {
                    LabeledRow("Model", polishers.selectedModels[provider]?.let { polishers.modelName(it, provider) } ?: "Choose a model",
                               onClick = { open(AIRoute.Models(provider, forReading = false)) })
                    TextButton(onClick = { container.scope.launch { polishers.check(provider) } }) { Text("Refresh models") }
                }
            }
        }
        if (polishers.isReady(provider)) {
            FormSection {
                if (polishers.active == provider && status is KeyStatus.Failed) {
                    Row(Modifier.padding(vertical = 10.dp)) {
                        Icon(Icons.Filled.Warning, null, tint = Color(0xFFF57C00)); Spacer(Modifier.width(8.dp))
                        Text("Pearl Forge is set to polish with ${provider.displayName}, but this key isn't working", color = Color(0xFFF57C00))
                    }
                } else if (polishers.active == provider) {
                    Row(Modifier.padding(vertical = 10.dp)) {
                        Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E9E5B)); Spacer(Modifier.width(8.dp))
                        Text("Pearl Forge is polishing with ${provider.displayName}", color = Color(0xFF2E9E5B))
                    }
                } else TextButton(onClick = { polishers.polishWith(provider) }) { Text("Polish with ${provider.displayName}") }
            }
        }
    }
}

/** Pick one of a service's models, or type the id of one that isn't listed. */
@Composable
fun ModelPicker(container: AppContainer, provider: PolisherProvider, forReading: Boolean, onBack: () -> Unit) {
    val polishers = container.polishers
    var search by remember { mutableStateOf("") }
    var customId by remember { mutableStateOf("") }
    val selected = if (forReading) polishers.readingModel else polishers.selectedModels[provider]
    val all = polishers.models[provider].orEmpty()
    val shown = if (search.isEmpty()) all else all.filter { it.name.contains(search, ignoreCase = true) || it.id.contains(search, ignoreCase = true) }
    fun choose(id: String, name: String?) {
        if (forReading) polishers.selectReadingModel(id, name) else polishers.selectModel(id, name, provider)
        onBack()
    }
    ScreenScaffold("${provider.displayName} models", onBack) {
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) },
                          placeholder = { Text("Search models") })
        val note = when {
            forReading -> "Sonnet 5.5 costs about half as much per search as Opus 5.5."
            provider == PolisherProvider.ANTHROPIC -> "Pearl Forge was built and tested with Claude Opus 5.5."
            else -> null
        }
        FormSection(footer = note?.let { { FooterText(it) } }) {
            if (shown.isEmpty()) Text(if (search.isEmpty()) "No models loaded." else "No models match “$search”.", modifier = Modifier.padding(vertical = 10.dp))
            shown.forEachIndexed { index, model ->
                if (index > 0) RowDivider()
                Row(Modifier.fillMaxWidth().clickable { choose(model.id, model.name) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(model.name)
                        if (model.name != model.id) Text(model.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selected == model.id) Icon(Icons.Filled.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        FormSection("Not in the list?", footer = { FooterText("Type the exact model id from ${if (provider == PolisherProvider.OPENROUTER) "OpenRouter" else provider.company}'s documentation.") }) {
            OutlinedTextField(customId, { customId = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true, placeholder = { Text("Model id") })
            TextButton(onClick = { choose(customId.trim(), null) }, enabled = customId.isNotBlank()) { Text("Use this model") }
        }
    }
}

/** What a key editor saves to, and how it's described. */
data class KeyTarget(
    val title: String,
    val keysPageLabel: String,
    val keysPage: String,
    val usesWorkspace: Boolean,
    val isSaved: Boolean,
    val workspace: String,
    val save: suspend (key: String?, workspace: String) -> Unit,
    val remove: () -> Unit,
)

/** Enter, replace or remove an API key. The key is never shown once saved. */
@Composable
fun KeyEditor(container: AppContainer, target: KeyTarget, onBack: () -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var workspace by remember { mutableStateOf(target.workspace) }
    var showKey by remember { mutableStateOf(false) }
    var confirmingRemove by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val trimmed = key.trim()
    val canSave = trimmed.isNotEmpty() || (target.usesWorkspace && target.isSaved && workspace.trim() != target.workspace)
    ScreenScaffold(target.title, onBack) {
        FormSection(if (target.isSaved) "Replace your key" else "Your key", footer = {
            Column {
                error?.let { FooterText(it, isError = true) }
                FooterText(if (target.isSaved) "A key is saved. Paste a new one to replace it; the saved key is never shown."
                           else "Paste your key. It's kept encrypted on this tablet: never in the app's code, and never copied off.")
                TextButton(onClick = { openUrl(context, target.keysPage) }) { Text(target.keysPageLabel) }
            }
        }) {
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true,
                              placeholder = { Text("API key") },
                              visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                              trailingIcon = {
                                  IconButton(onClick = { showKey = !showKey }) {
                                      Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (showKey) "Hide key" else "Show key")
                                  }
                              })
        }
        if (target.usesWorkspace) {
            FormSection("Workspace (optional)", footer = { FooterText("Only needed if your key belongs to an organization that asks for a workspace id.") }) {
                OutlinedTextField(workspace, { workspace = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true, placeholder = { Text("Workspace id") })
            }
        }
        Button(onClick = {
            error = null
            container.scope.launch {
                runCatching { target.save(trimmed.ifEmpty { null }, workspace.trim()) }.onSuccess { onBack() }.onFailure { error = it.message }
            }
        }, enabled = canSave, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        if (target.isSaved) {
            FormSection { TextButton(onClick = { confirmingRemove = true }) { Text("Remove key", color = MaterialTheme.colorScheme.error) } }
        }
    }
    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text("Remove this key?") },
            text = { Text("Pearl Forge won't be able to use this service until you add a key again.") },
            confirmButton = { TextButton(onClick = { confirmingRemove = false; target.remove(); onBack() }) { Text("Remove key", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmingRemove = false }) { Text("Cancel") } },
        )
    }
}

/** The ElevenLabs key, your voice, and where recordings are kept. */
@Composable
fun SettingsScreen(container: AppContainer, openKey: () -> Unit) {
    val settings = container.settings
    LaunchedEffect(Unit) { settings.checkSavedKeys() }
    ScreenScaffold("Settings") {
        FormSection("API key", footer = {
            Column {
                (settings.elevenLabsStatus as? KeyStatus.Failed)?.let { FooterText(it.message, isError = true) }
                FooterText("Kept encrypted on this tablet: never in the app's code, and never copied off it. Keys for the AI that polishes your thoughts are in the AI tab.")
            }
        }) {
            KeyStatusRow("ElevenLabs", "Speech-to-text and your voice", settings.elevenLabsStatus, openKey)
            if (settings.elevenLabsStatus is KeyStatus.Failed) TextButton(onClick = { container.scope.launch { settings.loadVoices() } }) { Text("Check again") }
        }
        FormSection("Voice", footer = { FooterText("Pearl Forge reads your refined thoughts back in this voice. Voices you made yourself are listed first.") }) {
            when {
                settings.elevenLabsStatus == KeyStatus.NotSet -> Text("Add your ElevenLabs key to choose a voice.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
                settings.elevenLabsStatus == KeyStatus.Checking || settings.elevenLabsStatus == KeyStatus.Saved -> ProgressRow("Loading your voices…")
                settings.elevenLabsStatus is KeyStatus.Failed -> Text("Your voices couldn't be loaded. Check your ElevenLabs key above.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
                settings.voices.isEmpty() -> Text("This ElevenLabs account has no voices yet.", modifier = Modifier.padding(vertical = 10.dp))
                else -> {
                    val own = settings.voices.filter { it.isOwn }
                    val library = settings.voices.filter { !it.isOwn }
                    MenuPicker("Your voice", settings.selectedVoiceName.ifEmpty { "Choose a voice" }, own + library,
                               { voice -> if (voice.isOwn) "${voice.name} (yours)" else voice.name }) { settings.selectVoice(it) }
                    TextButton(onClick = { container.scope.launch { settings.loadVoices() } }) { Text("Refresh voices") }
                }
            }
        }
        FormSection("Storage", footer = {
            FooterText("Recordings are kept in Pearl Forge's own folder on this tablet (Android › data › com.solidplisskin.pearlforge › files › Recordings), in the same format as the iPhone app. Updates keep them, but uninstalling Pearl Forge deletes them, so copy that folder to your computer over USB to back them up.")
        }) {
            LabeledRow("Saved in", container.library.locationName)
        }
    }
}
