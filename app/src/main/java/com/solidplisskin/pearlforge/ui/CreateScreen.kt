package com.solidplisskin.pearlforge.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.solidplisskin.pearlforge.AppContainer
import com.solidplisskin.pearlforge.pipeline.ForgeStep
import com.solidplisskin.pearlforge.pipeline.LectureEstimate
import com.solidplisskin.pearlforge.pipeline.LectureLength
import com.solidplisskin.pearlforge.pipeline.PolisherProvider
import com.solidplisskin.pearlforge.pipeline.RefinementMode
import com.solidplisskin.pearlforge.pipeline.ResearchProgress
import com.solidplisskin.pearlforge.pipeline.SpeechLanguage
import com.solidplisskin.pearlforge.pipeline.SpeechLanguages
import com.solidplisskin.pearlforge.pipeline.VoiceCredits
import com.solidplisskin.pearlforge.pipeline.dollarRange
import com.solidplisskin.pearlforge.pipeline.dollars
import com.solidplisskin.pearlforge.recordings.LectureInfo
import com.solidplisskin.pearlforge.settings.KeyStatus
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Record a thought (or type something), pick how much refinement and which language, and hear it in
 * your voice. Say "give a lecture on…" to have a topic researched and turned into a talk in your
 * voice, with its cost shown first.
 */
@Composable
fun CreateScreen(container: AppContainer, openTab: (AppTab) -> Unit) {
    val model = container.create
    val settings = container.settings
    val polishers = container.polishers
    val library = container.library
    val context = LocalContext.current
    var confirmingDiscard by remember { mutableStateOf(false) }
    var microphoneDenied by remember { mutableStateOf(false) }

    val hasVoice = settings.elevenLabsStatus != KeyStatus.NotSet && settings.selectedVoiceId.isNotEmpty()
    val isReady = hasVoice && polishers.activeChoice != null
    val canGo = hasVoice && (!model.needsAI || polishers.activeChoice != null)

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneDenied = !granted
        if (granted) model.startRecording()
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importRecording) }

    fun record() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            microphoneDenied = false
            model.startRecording()
        } else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Research takes a few minutes; keep the screen on so the tablet doesn't sleep mid-way.
    val view = LocalView.current
    DisposableEffect(model.isBusy) {
        view.keepScreenOn = model.isBusy
        onDispose { view.keepScreenOn = false }
    }
    // Know how many ElevenLabs credits are left before showing a lecture's estimate.
    LaunchedEffect(model.lecture != null) { if (model.lecture != null) settings.refreshCredits() }

    ScreenScaffold("Pearl Forge", actions = {
        if (model.hasResult && !model.isBusy) IconButton(onClick = { model.startNew() }) { Icon(Icons.Filled.Add, "New") }
    }) {
        if (!isReady) SetupSection(needsVoice = !hasVoice, needsAI = polishers.activeChoice == null, openTab = openTab)

        if (!model.hasResult && model.lecture != null) {
            if (model.input == CreateModel.Input.RECORD) RecorderSection(model, microphoneDenied, ::record, { confirmingDiscard = true }, { importer.launch(arrayOf("audio/*")) })
            LecturePlanSection(model)
            LectureCostSection(container)
            LectureActionSection(container, hasVoice)
        } else if (!model.hasResult) {
            if (!model.recorder.isRecording && model.source == null && model.transcript == null) {
                Segmented(CreateModel.Input.entries, model.input, { it.label }) { model.input = it }
            }
            if (model.input == CreateModel.Input.RECORD) {
                RecorderSection(model, microphoneDenied, ::record, { confirmingDiscard = true }, { importer.launch(arrayOf("audio/*")) })
            } else {
                FormSection("What you want to say", footer = { FooterText("Spoken in your voice. Pick a language below to translate it first.") }) {
                    OutlinedTextField(model.typedText, { model.typedText = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(vertical = 8.dp),
                                      enabled = !model.isBusy, placeholder = { Text("Type or paste what you want to say…") })
                }
            }
            if (model.hasInput) {
                ModeSection(model)
                ActionSection(container, canGo)
            } else model.errorMessage?.let { FormSection { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 10.dp)) } }
        }

        val transcript = model.transcript
        if (transcript != null && (model.lecture == null || model.hasResult) &&
            (model.input == CreateModel.Input.RECORD || (model.hasResult && transcript != model.refinedText))) {
            FormSection(if (model.input == CreateModel.Input.TYPE) "What you wrote" else "What you said") {
                Text(transcript, modifier = Modifier.padding(vertical = 10.dp))
            }
        }

        if (model.hasResult) {
            RefinedSection(model)
            VoiceSection(container)
            model.lectureInfo?.let { info ->
                LectureSourcesSection(model)
                LectureCostPaidSection(info)
            }
        }
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text("Discard this recording?") },
            text = { Text("It hasn't been refined or saved yet.") },
            confirmButton = { TextButton(onClick = { confirmingDiscard = false; record() }) { Text("Discard and record again", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SetupSection(needsVoice: Boolean, needsAI: Boolean, openTab: (AppTab) -> Unit) {
    FormSection {
        if (needsVoice) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Key, null); Spacer(Modifier.width(10.dp)); Text("Add your ElevenLabs key and pick your voice in Settings.")
            }
            TextButton(onClick = { openTab(AppTab.SETTINGS) }) { Text("Open Settings") }
        }
        if (needsAI) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(10.dp)); Text("Choose the AI that polishes your thoughts in the AI tab.")
            }
            TextButton(onClick = { openTab(AppTab.AI) }) { Text("Open the AI tab") }
        }
    }
}

/** The big round record / stop button, the timer and level while recording, or the waiting voice note. */
@Composable
private fun RecorderSection(model: CreateModel, microphoneDenied: Boolean, record: () -> Unit, recordAgain: () -> Unit, import: () -> Unit) {
    val context = LocalContext.current
    FormSection(footer = if (model.source == null && !model.recorder.isRecording) {
        { FooterText("Tip: start with “Give a lecture on…” to have a topic researched and spoken as a talk in your voice. You'll see what it costs first.") }
    } else null) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
               verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when {
                model.recorder.isRecording -> {
                    Text(timeString(model.recorder.elapsed), fontSize = 48.sp, fontWeight = FontWeight.Light)
                    LinearProgressIndicator(progress = { model.recorder.level.toFloat() }, modifier = Modifier.width(220.dp).height(6.dp),
                                            color = Color(0xFFE53935))
                    RecordButton(isRecording = true) { model.stopRecording() }
                    Text("Tap to stop", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                model.source != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.GraphicEq, null)
                        Spacer(Modifier.width(8.dp))
                        Text(model.sourceDuration?.let { "Voice note ready · ${timeString(it)}" } ?: "Voice note ready", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = recordAgain, enabled = !model.isBusy) { Text("Record again") }
                }
                else -> {
                    RecordButton(isRecording = false, onClick = record)
                    Text("Tap to record", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = import) { Text("Import a recording instead") }
                }
            }
            if (microphoneDenied) {
                Text("Pearl Forge needs microphone access to record.", textAlign = TextAlign.Center)
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Allow in Settings") }
            }
        }
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(88.dp).clip(CircleShape).background(Color(0xFFE53935)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (isRecording) Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Color.White))
        else Icon(Icons.Filled.Mic, if (isRecording) "Stop recording" else "Record", tint = Color.White, modifier = Modifier.size(38.dp))
    }
}

@Composable
private fun ModeSection(model: CreateModel) {
    var text = model.activeMode.subtitleSentence
    model.language?.let { text += " Then spoken in ${it.name}, in your voice." }
    FormSection(if (model.input == CreateModel.Input.RECORD) "How much refinement?" else "Options", footer = { FooterText(text) }) {
        if (model.input == CreateModel.Input.RECORD) {
            Box(Modifier.padding(vertical = 8.dp)) {
                Segmented(RefinementMode.polishingModes, model.mode, { it.title }, enabled = !model.isBusy) { model.chooseMode(it) }
            }
        } else {
            MenuPicker("Polish", model.typedMode.title, listOf(RefinementMode.AS_WRITTEN) + RefinementMode.polishingModes, { it.title },
                       enabled = !model.isBusy) { model.chooseTypedMode(it) }
        }
        RowDivider()
        LanguagePicker(model.language, if (model.input == CreateModel.Input.TYPE) "The language I wrote in" else "The language I spoke in",
                       enabled = !model.isBusy) { model.language = it }
    }
}

@Composable
private fun ActionSection(container: AppContainer, canGo: Boolean) {
    val model = container.create
    val settings = container.settings
    val polishers = container.polishers
    val isLectureRequest = model.input == CreateModel.Input.TYPE && com.solidplisskin.pearlforge.pipeline.LectureRequest.parse(model.typedText) != null
    val title = when {
        model.errorMessage != null -> "Try again"
        isLectureRequest -> "Plan lecture"
        model.input == CreateModel.Input.RECORD && model.language == null -> "Refine & speak"
        model.input == CreateModel.Input.RECORD -> "Refine, translate & speak"
        model.language != null -> "Translate & speak"
        model.typedMode == RefinementMode.AS_WRITTEN -> "Speak in my voice"
        else -> "Polish & speak"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val step = model.step
        if (step != null) FormSection { ProgressRow(step.progressLabel) }
        else Button(onClick = { model.refineAndSpeak(settings.selectedVoiceId, polishers.activeChoice) }, enabled = canGo,
                    modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(title, style = MaterialTheme.typography.titleMedium) }
        val voice = settings.selectedVoiceName.ifEmpty { "your voice" }
        val choice = polishers.activeChoice
        when {
            model.errorMessage != null -> FooterText(model.errorMessage!!, isError = true)
            !canGo -> Unit
            isLectureRequest -> FooterText("Shows the lecture's estimated cost before anything is spent.")
            !model.needsAI || choice == null -> FooterText("Speaking as $voice, exactly as written.")
            else -> FooterText("${if (model.activeMode == RefinementMode.AS_WRITTEN) "Translating" else "Polishing"} with ${polishers.label(choice)}, speaking as $voice.")
        }
    }
}

@Composable
private fun RefinedSection(model: CreateModel) {
    val header = model.lectureInfo?.let { info -> "Lecture · about ${info.minutes} min" + (model.language?.let { " · In ${it.name}" } ?: "") }
        ?: when (val mode = model.refinedMode ?: model.activeMode) {
            RefinementMode.AS_WRITTEN -> if (model.language == null) "As written" else "Translated" + " · In ${model.language!!.name}"
            else -> "Refined · ${mode.title}" + (model.language?.let { " · In ${it.name}" } ?: "")
        }
    FormSection(header, footer = { FooterText("If anything's off, fix it here, then regenerate the audio.") }) {
        OutlinedTextField(model.refinedText, { model.refinedText = it }, Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(vertical = 8.dp),
                          enabled = !model.isBusy)
    }
}

@Composable
private fun VoiceSection(container: AppContainer) {
    val model = container.create
    val context = LocalContext.current
    val step = model.step
    FormSection("In your voice", footer = {
        when {
            model.errorMessage != null -> FooterText(model.errorMessage!!, isError = true)
            model.lectureInfo != null -> FooterText("Saved in History (${container.library.locationName}) with its sources.")
            else -> FooterText("Saved as pearl 1 in History (${container.library.locationName}). Open it there to forge more versions, with any AI.")
        }
    }) {
        when {
            step != null -> ProgressRow(step.progressLabel)
            model.player.loadedKey != null -> {
                PlayerRow(model.player)
                if (model.hasUnspokenEdits) TextButton(onClick = { model.regenerateAudio(container.settings.selectedVoiceId) }) { Text("Regenerate audio from your edits") }
                model.shareFile?.let { file ->
                    TextButton(onClick = { shareFile(context, file, "audio/mpeg") }) {
                        Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp)); Text("Share audio")
                    }
                }
            }
            model.errorMessage != null -> TextButton(onClick = { model.refineAndSpeak(container.settings.selectedVoiceId, container.polishers.activeChoice) }) { Text("Try again") }
        }
    }
}

// Lectures

@Composable
private fun LecturePlanSection(model: CreateModel) {
    val plan = model.lecture ?: return
    FormSection("Lecture", footer = {
        FooterText("Claude researches the topic on PubMed and the web, writes a talk from what it finds, and it's spoken in your voice. Every source it names is checked against the real search results.")
    }) {
        model.transcript?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                                     color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
        OutlinedTextField(plan.topic, { model.lecture = plan.copy(topic = it) }, Modifier.fillMaxWidth().padding(vertical = 8.dp),
                          label = { Text("Topic") }, enabled = !model.isBusy, maxLines = 4)
        MenuPicker("Length", "About ${plan.minutes} min", LectureLength.options, { "About $it min" }, enabled = !model.isBusy) {
            model.lecture = plan.copy(minutes = it)
        }
        RowDivider()
        MenuPicker("Researched by", CreateModel.lectureModelName(plan.model), CreateModel.lectureModels, CreateModel::lectureModelName,
                   enabled = !model.isBusy) { model.lecture = plan.copy(model = it) }
        RowDivider()
        LanguagePicker(model.language, if (model.input == CreateModel.Input.TYPE) "The language I wrote in" else "The language I spoke in",
                       enabled = !model.isBusy) { model.language = it }
    }
}

private fun lectureEstimate(container: AppContainer): LectureEstimate? {
    val plan = container.create.lecture ?: return null
    return LectureEstimate.make(plan.minutes, plan.model, container.settings.credits, container.library.lectureEstimateRatios(plan.model))
}

@Composable
private fun LectureCostSection(container: AppContainer) {
    val plan = container.create.lecture ?: return
    val estimate = lectureEstimate(container) ?: return
    val credits = container.settings.credits
    FormSection("Estimated cost", footer = { FooterText(costFooter(estimate, credits)) }) {
        CostRow("Research & writing", dollarRange(estimate.claudeLow, estimate.claudeHigh),
                "${CreateModel.lectureModelName(plan.model)} · up to ${LectureLength.webSearches(plan.minutes)} web searches")
        RowDivider()
        CostRow("Your voice", estimate.voiceDollars?.let { "about ${it.dollars}" } ?: "free plan", "About ${"%,d".format(estimate.credits)} ElevenLabs credits")
        RowDivider()
        CostRow("Total", if (estimate.voiceDollars == null) dollarRange(estimate.claudeLow, estimate.claudeHigh) else dollarRange(estimate.totalLow, estimate.totalHigh),
                null, isTotal = true)
        if (credits != null && estimate.credits > credits.remaining) {
            Row(Modifier.padding(vertical = 8.dp)) {
                Icon(Icons.Filled.Warning, null, tint = Color(0xFFF57C00))
                Spacer(Modifier.width(8.dp))
                Text("That's more than the ${"%,d".format(credits.remaining)} credits you have left this month. Choose a shorter lecture, or add credits at elevenlabs.io.",
                     color = Color(0xFFF57C00), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun costFooter(estimate: LectureEstimate, credits: VoiceCredits?): String {
    val parts = mutableListOf<String>()
    if (credits != null) {
        var left = "You have ${"%,d".format(credits.remaining)} ElevenLabs credits left"
        credits.resets?.let { left += " until ${SimpleDateFormat("MMM d", Locale.getDefault()).format(it)}" }
        parts.add("$left.")
    }
    parts.add(if (estimate.learnedFrom > 0) "Adjusted to what your last ${if (estimate.learnedFrom == 1) "lecture" else "${estimate.learnedFrom} lectures"} actually cost."
              else "Claude's part is a range because it depends on how much it needs to read.")
    parts.add("Claude bills your Anthropic account; your voice uses credits from your ElevenLabs plan, valued here at your plan's rate.")
    return parts.joinToString(" ")
}

@Composable
private fun LectureActionSection(container: AppContainer, hasVoice: Boolean) {
    val model = container.create
    val settings = container.settings
    val polishers = container.polishers
    val hasClaudeKey = polishers.status[PolisherProvider.ANTHROPIC] != KeyStatus.NotSet
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val step = model.step
        if (step != null) {
            FormSection { ProgressRow(step.progressLabel, model.researchProgress?.takeIf { step == ForgeStep.RESEARCHING && it.searches > 0 }?.let(::progressDetail)) }
        } else {
            Button(onClick = {
                val estimate = lectureEstimate(container)
                model.giveLecture(settings.selectedVoiceId, polishers.activeChoice, estimate?.claudeBase,
                                  settings.credits?.dollarsPerThousand ?: if (settings.credits == null) VoiceCredits.dollarsPerThousand(null) else null)
            }, enabled = hasVoice && hasClaudeKey && model.lecture?.topic?.isNotBlank() == true,
                   modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (model.errorMessage == null) "Give the lecture" else "Try again", style = MaterialTheme.typography.titleMedium)
            }
        }
        when {
            model.errorMessage != null -> FooterText(model.errorMessage!!, isError = true)
            !hasClaudeKey -> FooterText("Lectures are researched with Claude's web search. Add your Claude key in the AI tab.")
            !hasVoice -> FooterText("Add your ElevenLabs key and pick your voice in Settings.")
            model.isBusy -> FooterText("This takes a minute or two, then your voice. Pearl Forge keeps the screen on while it works; stay on this screen until it's done.")
            else -> FooterText("Takes a minute or two to research and write, then it's spoken as ${settings.selectedVoiceName.ifEmpty { "your voice" }}.")
        }
        // For when "give a lecture…" wasn't meant as a request.
        if (!model.isBusy) {
            FormSection {
                TextButton(onClick = { model.speakInsteadOfLecture(settings.selectedVoiceId, polishers.activeChoice) }) {
                    Text(if (model.input == CreateModel.Input.TYPE) "Not a lecture: speak what I wrote" else "Not a lecture: refine what I said")
                }
                if (model.input == CreateModel.Input.TYPE) TextButton(onClick = { model.editTypedText() }) { Text("Edit what I wrote") }
            }
        }
    }
}

/** "3 PubMed and 2 web searches · “circadian rhythm teens”". */
private fun progressDetail(progress: ResearchProgress): String {
    val counts = listOfNotNull(progress.pubMedSearches.takeIf { it > 0 }?.let { "$it PubMed" }, progress.webSearches.takeIf { it > 0 }?.let { "$it web" })
    var text = counts.joinToString(" and ") + if (progress.searches == 1) " search" else " searches"
    progress.latestQuery?.let { text += " · “$it”" }
    return text
}

@Composable
private fun LectureSourcesSection(model: CreateModel) {
    val context = LocalContext.current
    FormSection("Sources", footer = {
        FooterText(if (model.unconfirmedSources > 0) "Each one was checked against the actual search results; ${model.unconfirmedSources} that couldn't be confirmed were left out. They're also in the Reading tab."
                   else "Each one was checked against the actual search results. They're also in the Reading tab.")
    }) {
        model.lectureSources.forEachIndexed { index, reference ->
            if (index > 0) RowDivider()
            ReferenceRow(reference) { openUrl(context, reference.url) }
        }
        if (model.lectureSources.isEmpty()) {
            Text("None of the sources Claude named could be confirmed, so none were saved.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.padding(vertical = 10.dp))
        }
    }
}

@Composable
fun LectureCostPaidSection(info: LectureInfo, footer: String = "Claude's cost is worked out from the usage it reported, at Anthropic's list prices. Regenerating the audio uses the credits again.") {
    FormSection("What it cost", footer = { FooterText(footer) }) {
        CostRow("Research & writing", info.claudeCost?.dollars ?: "not known",
                CreateModel.lectureModelName(info.model) + (info.webSearches?.let { " · $it web search${if (it == 1) "" else "es"}" } ?: ""))
        info.characters?.let { characters ->
            RowDivider()
            CostRow("Your voice", info.voiceCost?.let { "about ${it.dollars}" } ?: "free plan", "${"%,d".format(characters)} ElevenLabs credits")
        }
    }
}

// Pickers shared by several screens

/** A row that opens a menu of choices. */
@Composable
fun <T> MenuPicker(label: String, value: String, options: List<T>, optionLabel: (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        LabeledRow(label, value, onClick = if (enabled) ({ open = true }) else null)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { open = false; onSelect(option) },
                                 trailingIcon = if (optionLabel(option) == value) ({ Icon(Icons.Filled.Check, "Selected") }) else null)
            }
        }
    }
}

/** "Speak in": your own language or one of the 29 your voice speaks. */
@Composable
fun LanguagePicker(selected: SpeechLanguage?, noneLabel: String, enabled: Boolean = true, onSelect: (SpeechLanguage?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    LabeledRow("Speak in", selected?.name ?: noneLabel, onClick = if (enabled) ({ open = true }) else null)
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Speak in") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    item { LanguageRow(noneLabel, selected == null) { onSelect(null); open = false } }
                    items(SpeechLanguages.all) { language -> LanguageRow(language.name, selected == language) { onSelect(language); open = false } }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun LanguageRow(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f))
        if (isSelected) Icon(Icons.Filled.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}
