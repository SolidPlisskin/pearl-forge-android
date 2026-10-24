package com.solidplisskin.pearlforge.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.solidplisskin.pearlforge.audio.AudioPlayback
import com.solidplisskin.pearlforge.audio.VoiceRecorder
import com.solidplisskin.pearlforge.pipeline.Forge
import com.solidplisskin.pearlforge.pipeline.ForgeStep
import com.solidplisskin.pearlforge.pipeline.LectureLength
import com.solidplisskin.pearlforge.pipeline.LectureRequest
import com.solidplisskin.pearlforge.pipeline.PipelineError
import com.solidplisskin.pearlforge.pipeline.PolisherChoice
import com.solidplisskin.pearlforge.pipeline.Refinement
import com.solidplisskin.pearlforge.pipeline.RefinementMode
import com.solidplisskin.pearlforge.pipeline.ResearchProgress
import com.solidplisskin.pearlforge.pipeline.SpeechLanguage
import com.solidplisskin.pearlforge.pipeline.SpeechLanguages
import com.solidplisskin.pearlforge.recordings.LectureInfo
import com.solidplisskin.pearlforge.recordings.RecordingFiles
import com.solidplisskin.pearlforge.recordings.RecordingLibrary
import com.solidplisskin.pearlforge.recordings.Reference
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drives the Create screen: record (or import) a voice note, or type some text; then transcribe it,
 * refine and/or translate it, save it, and speak its first pearl in your voice. If what you said
 * asks for a lecture ("give a lecture on…"), it plans one instead and shows what it will cost
 * before anything is spent. A failed step can be retried without repeating the steps before it.
 */
class CreateModel(private val context: Context, private val library: RecordingLibrary, private val scope: CoroutineScope) {
    enum class Input(val label: String) { RECORD("Record"), TYPE("Type") }

    /** A lecture you asked for, waiting for you to confirm it. */
    data class LecturePlan(val topic: String, val minutes: Int, val model: String)

    companion object {
        /** The Claude models offered for lectures; both search the web and PubMed. Sonnet 5.5 costs about half as much. */
        val lectureModels = listOf("claude-sonnet-5-5", "claude-opus-5-5")

        fun lectureModelName(id: String) = mapOf("claude-sonnet-5-5" to "Claude Sonnet 5.5", "claude-opus-5-5" to "Claude Opus 5.5")[id] ?: id
    }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val pendingRecording = File(context.cacheDir, "pending-recording.m4a")

    val recorder = VoiceRecorder(context)
    val player = AudioPlayback(context)

    var input by mutableStateOf(Input.RECORD)

    /** How much to polish a recording. */
    var mode by mutableStateOf(RefinementMode.from(prefs.getString("refinementMode", null))?.takeIf { it in RefinementMode.polishingModes } ?: RefinementMode.CLEAN)
        private set
    fun chooseMode(value: RefinementMode) { mode = value; prefs.edit { putString("refinementMode", value.raw) } }

    /** Typed text, and how much to polish it ("As written" by default). */
    var typedText by mutableStateOf("")
    var typedMode by mutableStateOf(RefinementMode.from(prefs.getString("typedRefinementMode", null)) ?: RefinementMode.AS_WRITTEN)
        private set
    fun chooseTypedMode(value: RefinementMode) { typedMode = value; prefs.edit { putString("typedRefinementMode", value.raw) } }

    /** The language to speak in; null keeps the language you used. */
    var language by mutableStateOf<SpeechLanguage?>(null)

    /** The mode in use for what's being made now. */
    val activeMode: RefinementMode get() = if (input == Input.TYPE) typedMode else mode

    /** The voice note waiting to be processed, recorded or imported. */
    var source by mutableStateOf<File?>(null)
        private set
    var sourceDuration by mutableStateOf<Double?>(null)
        private set
    var step by mutableStateOf<ForgeStep?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    var transcript by mutableStateOf<String?>(null)
        private set
    /** The refined text, editable before regenerating the audio. */
    var refinedText by mutableStateOf("")
    var refinedMode by mutableStateOf<RefinementMode?>(null)
        private set
    /** The saved recording and its first pearl, once refined. */
    var recordingId by mutableStateOf<String?>(null)
        private set
    private var pearlId: UUID? = null
    /** A copy of the audio for the share sheet. */
    var shareFile by mutableStateOf<File?>(null)
        private set
    /** The text the current audio was made from. */
    private var spokenText by mutableStateOf<String?>(null)

    /** The lecture you asked for, once what you said has been understood. */
    var lecture by mutableStateOf<LecturePlan?>(null)
    /** Set when you chose to treat a lecture request as an ordinary thought. */
    private var ignoresLectureRequest = false
    var researchProgress by mutableStateOf<ResearchProgress?>(null)
        private set
    /** A finished lecture's details and the sources it draws on. */
    var lectureInfo by mutableStateOf<LectureInfo?>(null)
        private set
    var lectureSources by mutableStateOf<List<Reference>>(emptyList())
        private set
    /** Sources Claude named that couldn't be confirmed, so weren't kept. */
    var unconfirmedSources by mutableIntStateOf(0)
        private set

    val isBusy: Boolean get() = step != null
    val hasResult: Boolean get() = recordingId != null

    /** There's a voice note or some typed text ready to process. */
    val hasInput: Boolean get() = if (input == Input.RECORD) source != null else typedText.isNotBlank()

    /** Whether processing calls an AI at all (typed text spoken as written, in its own language, doesn't). */
    val needsAI: Boolean get() = activeMode != RefinementMode.AS_WRITTEN || language != null

    /** True once you've edited the refined text since the audio was made. */
    val hasUnspokenEdits: Boolean get() = spokenText?.let { refinedText.trim() != it } ?: false

    // Recording

    fun startRecording() {
        startNew()
        try {
            recorder.start(pendingRecording)
        } catch (error: Exception) {
            errorMessage = "Couldn't start recording: ${error.message}"
        }
    }

    fun stopRecording() {
        val duration = recorder.stop()
        if (duration < 1) {
            errorMessage = "That recording was too short. Hold on a little longer next time."
            return
        }
        source = pendingRecording
        sourceDuration = duration
    }

    /** Uses an existing audio file (from the Files app, a voice recorder, …) instead of a new recording. */
    fun importRecording(uri: Uri) {
        startNew()
        try {
            val extension = context.contentResolver.getType(uri)?.substringAfter('/')?.let {
                when (it) { "mpeg" -> "mp3"; "mp4", "x-m4a", "m4a", "aac" -> "m4a"; "x-wav", "wav" -> "wav"; else -> it }
            } ?: "m4a"
            val copy = File(context.cacheDir, "imported-recording.$extension")
            context.contentResolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
            source = copy
            sourceDuration = runCatching {
                MediaMetadataRetriever().run {
                    setDataSource(copy.absolutePath)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDouble()?.div(1000).also { release() }
                }
            }.getOrNull()
        } catch (error: Exception) {
            errorMessage = "Couldn't open that file: ${error.message}"
        }
    }

    /** Clears the screen for a new voice note. Anything already refined is saved in History. */
    fun startNew() {
        player.unload()
        if (hasResult) typedText = ""
        source = null
        sourceDuration = null
        errorMessage = null
        transcript = null
        refinedText = ""
        refinedMode = null
        recordingId = null
        pearlId = null
        shareFile = null
        spokenText = null
        lecture = null
        ignoresLectureRequest = false
        researchProgress = null
        lectureInfo = null
        lectureSources = emptyList()
        unconfirmedSources = 0
    }

    // Pipeline

    /** Runs whichever steps haven't succeeded yet: transcribe, then lecture check, refine and/or translate (and save), then speak. */
    fun refineAndSpeak(voiceId: String, polisher: PolisherChoice?) = scope.launch {
        if (!hasInput || step != null) return@launch
        errorMessage = null
        try {
            if (transcript == null) {
                when (input) {
                    Input.RECORD -> {
                        val audio = source ?: return@launch
                        step = ForgeStep.TRANSCRIBING
                        transcript = Forge.transcribe(audio)
                    }
                    Input.TYPE -> transcript = typedText.trim()
                }
            }
            val said = transcript
            if (recordingId == null && said != null && !ignoresLectureRequest) {
                val request = LectureRequest.parse(said)
                if (request != null) {
                    // Asked for a lecture: show the plan and its cost first.
                    lecture = LecturePlan(request.topic, request.minutes?.let(LectureLength::nearest) ?: LectureLength.STANDARD, savedLectureModel)
                    step = null
                    return@launch
                }
            }
            if (recordingId == null && said != null) {
                val chosenMode = activeMode
                val notes = language?.let(SpeechLanguages::request)
                var refinement: Refinement? = null
                if (needsAI) {
                    polisher ?: throw PipelineError("Choose the AI that polishes your thoughts in the AI tab first.")
                    step = if (language != null) ForgeStep.TRANSLATING else ForgeStep.REFINING
                    val target = language
                    refinement = if (chosenMode == RefinementMode.AS_WRITTEN && target != null) Forge.translate(said, target, polisher)
                                 else Forge.refine(said, chosenMode, notes, polisher)
                }
                val (entry, pearl) = library.createRecording(if (input == Input.RECORD) source else null, said, refinement, chosenMode, notes, voiceId)
                recordingId = entry.id
                pearlId = pearl.id
                // Sort recordings into topics in the background; it doesn't hold anything up.
                if (input == Input.RECORD && polisher != null) scope.launch { library.sortIntoTopics(entry.id, polisher) }
                refinedText = refinement?.text ?: said
                refinedMode = chosenMode
            }
            step = ForgeStep.SPEAKING
            speak(voiceId)
        } catch (error: Exception) {
            errorMessage = error.message ?: "Something went wrong."
        }
        step = null
    }

    /** Re-speaks the (edited) refined text and replaces the saved audio. */
    fun regenerateAudio(voiceId: String) = scope.launch {
        if (recordingId == null || step != null) return@launch
        errorMessage = null
        step = ForgeStep.SPEAKING
        try {
            speak(voiceId)
        } catch (error: Exception) {
            errorMessage = error.message ?: "Something went wrong."
        }
        step = null
    }

    /** Saves the current refined text, speaks it, and plays the result. */
    private suspend fun speak(voiceId: String) {
        val entryId = recordingId ?: return
        val pearl = pearlId ?: return
        val text = refinedText.trim()
        if (text.isEmpty()) throw PipelineError("There's no text to turn into speech.")
        if (library.entry(entryId)?.pearlTexts?.get(pearl) != text) library.updateText(text, pearl, entryId)
        player.unload()
        val audio = Forge.speak(text, voiceId)
        library.setAudio(audio, pearl, entryId, voiceId)
        spokenText = text
        val title = library.entry(entryId)?.meta?.title ?: RecordingFiles.title(text)
        shareFile = runCatching {
            File(context.cacheDir, "shared").apply { mkdirs() }.resolve(RecordingFiles.shareName(title, 1)).apply { writeBytes(audio) }
        }.getOrNull()
        player.load(audio, pearl.toString())
        player.play()
    }

    // Lectures

    private val savedLectureModel: String
        get() = prefs.getString("lecture.model", null)?.takeIf { it in lectureModels } ?: lectureModels[0]

    /**
     * Researches and writes the planned lecture, saves it with its sources, and speaks it in your
     * voice. `estimate` is the unadjusted Claude estimate shown beforehand, saved so later estimates
     * can learn from it.
     */
    fun giveLecture(voiceId: String, topicSorter: PolisherChoice?, estimate: Double?, voiceRate: Double?) = scope.launch {
        val plan = lecture ?: return@launch
        if (step != null) return@launch
        val topic = plan.topic.trim()
        if (topic.isEmpty()) {
            errorMessage = "Say or type what the lecture should be about."
            return@launch
        }
        errorMessage = null
        prefs.edit { putString("lecture.model", plan.model) }
        try {
            if (recordingId == null) {
                step = ForgeStep.RESEARCHING
                researchProgress = ResearchProgress()
                val written = Forge.writeLecture(topic, plan.minutes, language, plan.model) { researchProgress = it }
                val info = LectureInfo(topic, plan.minutes, written.model, language?.code, written.summary.ifEmpty { null },
                                       written.cost, estimate, written.usage.webSearches, written.script.length, voiceRate)
                val (entry, pearl) = library.createLecture(if (input == Input.RECORD) source else null, transcript ?: topic, written, info, voiceId)
                recordingId = entry.id
                pearlId = pearl.id
                refinedText = written.script
                refinedMode = RefinementMode.LECTURE
                lectureInfo = info
                lectureSources = written.references
                unconfirmedSources = written.unverified
                // Sort it into your topics so it plays alongside related thoughts.
                if (topicSorter != null) scope.launch { library.sortIntoTopics(entry.id, topicSorter) }
            }
            researchProgress = null
            step = ForgeStep.SPEAKING
            speak(voiceId)
        } catch (error: Exception) {
            errorMessage = error.message ?: "Something went wrong."
        }
        researchProgress = null
        step = null
    }

    /** Treats a lecture request as an ordinary thought and refines and speaks it as usual. */
    fun speakInsteadOfLecture(voiceId: String, polisher: PolisherChoice?) {
        ignoresLectureRequest = true
        lecture = null
        refineAndSpeak(voiceId, polisher)
    }

    /** Goes back to editing the text you typed. */
    fun editTypedText() {
        lecture = null
        transcript = null
        errorMessage = null
    }
}
