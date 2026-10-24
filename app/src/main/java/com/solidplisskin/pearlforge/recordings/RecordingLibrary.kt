package com.solidplisskin.pearlforge.recordings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.solidplisskin.pearlforge.pipeline.Forge
import com.solidplisskin.pearlforge.pipeline.ForgeStep
import com.solidplisskin.pearlforge.pipeline.PearlJson
import com.solidplisskin.pearlforge.pipeline.PipelineError
import com.solidplisskin.pearlforge.pipeline.PolisherChoice
import com.solidplisskin.pearlforge.pipeline.PolisherProvider
import com.solidplisskin.pearlforge.pipeline.Refinement
import com.solidplisskin.pearlforge.pipeline.RefinementMode
import com.solidplisskin.pearlforge.pipeline.ResearchLoop
import com.solidplisskin.pearlforge.pipeline.WrittenLecture
import com.solidplisskin.pearlforge.pipeline.asArray
import com.solidplisskin.pearlforge.pipeline.asObject
import com.solidplisskin.pearlforge.pipeline.prettyJson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray

/**
 * How a recording is laid out on disk, the same as the iPhone app. Each recording has its own folder:
 *
 *     2026-10-15_143012_I_think_theres_a_decent/
 *     ├── original.m4a               the unmodified recording
 *     ├── original_transcript.txt    what you actually said
 *     ├── pearl-01-clean.txt         each run through the forge…
 *     ├── pearl-01-clean.mp3         …and that run in your voice
 *     └── meta.json
 */
object RecordingFiles {
    const val ORIGINAL_TRANSCRIPT = "original_transcript.txt"
    const val META = "meta.json"

    /** A new recording's folder name, from its date (local time) and the first words of its first refinement. */
    fun folderName(created: Date, refined: String): String =
        SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(created) + "_" + slug(refined)

    /** The first few words, letters and digits only, joined with underscores: "I_think_theres_a_decent". */
    fun slug(text: String, words: Int = 6, maxLength: Int = 40): String {
        val firstWords = text.split(Regex("""\s+""")).filter { it.isNotEmpty() }.take(words).joinToString(" ")
        val kept = firstWords.filter { it == ' ' || (it.code < 128 && it.isLetterOrDigit()) }
        val joined = kept.split(" ").filter { it.isNotEmpty() }.joinToString("_")
        return joined.take(maxLength).ifEmpty { "note" }
    }

    /** The first line of the refined text, as the Pinokio build titled them. */
    fun title(refined: String): String = (refined.split("\n").firstOrNull { it.isNotEmpty() } ?: "").take(80)

    fun encodeMeta(meta: RecordingMeta): String = prettyJson(meta.toJson())

    fun decodeMeta(text: String): RecordingMeta? =
        runCatching { PearlJson.parseToJsonElement(text).asObject()?.let(RecordingMeta::fromJson) }.getOrNull()

    /** A file name for sharing a pearl's audio: "I suspect emotional desensitization… – pearl 2.mp3". */
    fun shareName(title: String, pearlNumber: Int): String {
        val cleaned = title.replace(Regex("""[/\\:?%*|"<>\n\r]"""), " ").split(" ").filter { it.isNotEmpty() }.joinToString(" ")
        return "${cleaned.take(60).ifEmpty { "Pearl Forge" }} – pearl $pearlNumber.mp3"
    }
}

/**
 * Every saved recording. A copy of each recording's details and texts is kept so History opens
 * instantly; audio is read only when you play it. State is observable by the screens.
 */
class RecordingLibrary(context: Context) {
    private val indexFile = File(context.filesDir, "RecordingIndex.json")

    /** Where recordings are saved: app storage on this tablet, visible from a computer over USB. */
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "Recordings").apply { mkdirs() }
    val locationName = "On this tablet"

    /** Newest first. */
    var entries by mutableStateOf(loadIndex())
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    /** The step of a pearl being forged (or re-spoken) right now, per recording. */
    val forging = mutableStateMapOf<String, ForgeStep>()
    val forgeErrors = mutableStateMapOf<String, String>()
    /** Recordings being sorted into topics right now. */
    val sorting = mutableStateMapOf<String, Boolean>()
    val sortErrors = mutableStateMapOf<String, String>()
    /** Recordings whose references are being searched for right now. */
    val findingReferences = mutableStateMapOf<String, Boolean>()
    val referenceErrors = mutableStateMapOf<String, String>()
    /** What the last reference search for a recording found. */
    val referenceNotices = mutableStateMapOf<String, String>()

    fun entry(id: String): RecordingEntry? = entries.firstOrNull { it.id == id }

    fun audioFile(name: String, entryId: String) = File(File(root, entryId), name)

    // MARK: Reading

    /** Re-reads the recordings folder, picking up anything added or changed. */
    suspend fun refresh() {
        if (isRefreshing) return
        isRefreshing = true
        val cached = entries.associateBy { it.folderName }
        val scanned = withContext(Dispatchers.IO) { scan(cached) }
        entries = scanned.sortedByDescending { it.meta.created }
        saveIndex()
        isRefreshing = false
    }

    private fun scan(cached: Map<String, RecordingEntry>): List<RecordingEntry> =
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { folder ->
            val previous = cached[folder.name]
            val meta = runCatching { File(folder, RecordingFiles.META).readText() }.getOrNull()?.let(RecordingFiles::decodeMeta)
                ?: return@mapNotNull previous
            if (previous != null && previous.meta == meta) return@mapNotNull previous
            fun text(file: String) = runCatching { File(folder, file).readText() }.getOrNull()
            val texts = meta.pearls.associate { it.id to (text(it.textFile) ?: previous?.pearlTexts?.get(it.id) ?: "") }
            RecordingEntry(folder.name, meta, text(RecordingFiles.ORIGINAL_TRANSCRIPT) ?: previous?.transcript ?: "", texts)
        }

    // MARK: Saving

    /**
     * Saves a new recording: the original audio (none for typed text), what you said or wrote, and
     * its first pearl's text. `refinement` is null when typed text is spoken as written.
     */
    suspend fun createRecording(sourceAudio: File?, transcript: String, refinement: Refinement?,
                                mode: RefinementMode, notes: String?, voiceId: String): Pair<RecordingEntry, Pearl> {
        val created = Date()
        val text = refinement?.text ?: transcript
        val pearl = Pearl(UUID.randomUUID(), 1, created, mode, notes, refinement?.model, refinement?.provider?.raw, voiceId,
                          Pearl.baseName(1, mode) + ".txt")
        val meta = RecordingMeta(RecordingFiles.title(text), created, sourceAudio?.let(::originalName), false, listOf(pearl),
                                 typed = if (sourceAudio == null) true else null)
        return saveNew(meta, pearl, text, transcript, sourceAudio)
    }

    /**
     * Saves a new lecture: what you asked for (and the recording of you asking, if you said it), the
     * lecture itself as pearl 1, and the sources it draws on, which also appear in the Reading tab.
     */
    suspend fun createLecture(sourceAudio: File?, request: String, lecture: WrittenLecture, info: LectureInfo,
                              voiceId: String): Pair<RecordingEntry, Pearl> {
        val created = Date()
        val pearl = Pearl(UUID.randomUUID(), 1, created, RefinementMode.LECTURE, null, lecture.model, PolisherProvider.ANTHROPIC.raw,
                          voiceId, Pearl.baseName(1, RefinementMode.LECTURE) + ".txt")
        val meta = RecordingMeta(lecture.title, created, sourceAudio?.let(::originalName), false, listOf(pearl),
                                 gist = lecture.summary.ifEmpty { null }, references = lecture.references,
                                 typed = if (sourceAudio == null) true else null, lecture = info)
        return saveNew(meta, pearl, lecture.script, request, sourceAudio)
    }

    /** "original.m4a", keeping the recording's own file type. */
    private fun originalName(sourceAudio: File) = "original.${sourceAudio.extension.ifEmpty { "m4a" }}"

    /** Writes a new recording's folder: its original audio (if any), what you said or wrote, its first pearl's text, and meta.json. */
    private suspend fun saveNew(meta: RecordingMeta, pearl: Pearl, text: String, transcript: String,
                                sourceAudio: File?): Pair<RecordingEntry, Pearl> {
        val folderName = RecordingFiles.folderName(meta.created, if (meta.lecture == null) text else meta.title)
        val folder = File(root, folderName)
        withContext(Dispatchers.IO) {
            folder.mkdirs()
            if (sourceAudio != null && meta.originalFile != null) sourceAudio.copyTo(File(folder, meta.originalFile), overwrite = false)
            File(folder, RecordingFiles.ORIGINAL_TRANSCRIPT).writeText(transcript)
            File(folder, pearl.textFile).writeText(text)
            File(folder, RecordingFiles.META).writeText(RecordingFiles.encodeMeta(meta))
        }
        val entry = RecordingEntry(folderName, meta, transcript, mapOf(pearl.id to text))
        replace(entry)
        return entry to pearl
    }

    /** Adds another pearl's text to a recording. */
    suspend fun addPearl(entryId: String, refinement: Refinement, mode: RefinementMode, notes: String?, voiceId: String): Pearl {
        val entry = entry(entryId) ?: throw missingRecording
        val number = (entry.meta.pearls.maxOfOrNull { it.number } ?: 0) + 1
        val pearl = Pearl(UUID.randomUUID(), number, Date(), mode, notes, refinement.model, refinement.provider.raw, voiceId,
                          Pearl.baseName(number, mode) + ".txt")
        writeText(refinement.text, pearl.textFile, entryId)
        save(entry.copy(meta = entry.meta.copy(pearls = entry.meta.pearls + pearl), pearlTexts = entry.pearlTexts + (pearl.id to refinement.text)))
        return pearl
    }

    /** Saves your edits to a pearl's text. */
    suspend fun updateText(text: String, pearlId: UUID, entryId: String) {
        val entry = entry(entryId) ?: throw missingRecording
        val pearl = entry.pearl(pearlId) ?: throw missingRecording
        writeText(text, pearl.textFile, entryId)
        var meta = entry.meta
        if (pearl.number == meta.pearls.minOf { it.number } && meta.lecture == null) meta = meta.copy(title = RecordingFiles.title(text))
        save(entry.copy(meta = meta, pearlTexts = entry.pearlTexts + (pearlId to text)))
    }

    /** Saves (or replaces) a pearl's audio. */
    suspend fun setAudio(audio: ByteArray, pearlId: UUID, entryId: String, voiceId: String) {
        val entry = entry(entryId) ?: throw missingRecording
        val pearl = entry.pearl(pearlId) ?: throw missingRecording
        val file = pearl.audioFile ?: (Pearl.baseName(pearl.number, pearl.mode) + ".mp3")
        withContext(Dispatchers.IO) { audioFile(file, entryId).writeBytes(audio) }
        val pearls = entry.meta.pearls.map { if (it.id == pearlId) it.copy(audioFile = file, voiceId = voiceId) else it }
        save(entry.copy(meta = entry.meta.copy(pearls = pearls)))
    }

    suspend fun setFavorite(favorite: Boolean, entryId: String) {
        val entry = entry(entryId) ?: return
        runCatching { save(entry.copy(meta = entry.meta.copy(favorite = favorite))) }
    }

    suspend fun deletePearl(pearlId: UUID, entryId: String) {
        val entry = entry(entryId) ?: return
        val pearl = entry.pearl(pearlId) ?: return
        withContext(Dispatchers.IO) { listOfNotNull(pearl.textFile, pearl.audioFile).forEach { File(File(root, entryId), it).delete() } }
        save(entry.copy(meta = entry.meta.copy(pearls = entry.meta.pearls.filter { it.id != pearlId }), pearlTexts = entry.pearlTexts - pearlId))
    }

    /** Deletes a recording's whole folder: the original and every pearl. */
    suspend fun deleteRecording(entryId: String) {
        withContext(Dispatchers.IO) { File(root, entryId).deleteRecursively() }
        entries = entries.filter { it.id != entryId }
        saveIndex()
    }

    // MARK: Forging more pearls

    /**
     * Runs the recording's original transcript through the forge again with the given mode and
     * request, saves it as a new pearl, and returns it with its audio. Progress and errors are in
     * `forging` and `forgeErrors`.
     */
    suspend fun forgeNewPearl(entryId: String, mode: RefinementMode, notes: String?, polisher: PolisherChoice,
                              voiceId: String): Pair<Pearl, ByteArray>? {
        if (forging[entryId] != null) return null
        val entry = entry(entryId) ?: return null
        forgeErrors.remove(entryId)
        return try {
            forging[entryId] = ForgeStep.REFINING
            val refinement = Forge.refine(entry.transcript, mode, notes, polisher)
            val pearl = addPearl(entryId, refinement, mode, notes, voiceId)
            forging[entryId] = ForgeStep.SPEAKING
            val audio = Forge.speak(refinement.text, voiceId)
            setAudio(audio, pearl.id, entryId, voiceId)
            (entry(entryId)?.pearl(pearl.id) ?: pearl) to audio
        } catch (error: Exception) {
            forgeErrors[entryId] = error.message ?: "Something went wrong."
            null
        } finally {
            forging.remove(entryId)
        }
    }

    /** Saves edited text for a pearl and speaks it again, replacing its audio. */
    suspend fun respeak(pearlId: UUID, entryId: String, text: String, voiceId: String): ByteArray? {
        if (forging[entryId] != null) return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            forgeErrors[entryId] = "There's no text to turn into speech."
            return null
        }
        forgeErrors.remove(entryId)
        return try {
            forging[entryId] = ForgeStep.SPEAKING
            if (entry(entryId)?.pearlTexts?.get(pearlId) != trimmed) updateText(trimmed, pearlId, entryId)
            val audio = Forge.speak(trimmed, voiceId)
            setAudio(audio, pearlId, entryId, voiceId)
            audio
        } catch (error: Exception) {
            forgeErrors[entryId] = error.message ?: "Something went wrong."
            null
        } finally {
            forging.remove(entryId)
        }
    }

    // MARK: Topics

    /** Every topic with how many recordings are in it, biggest first. */
    val topics: List<Pair<String, Int>>
        get() = entries.flatMap { it.meta.categories.orEmpty() }.groupingBy { it }.eachCount().toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })

    val topicNames: List<String> get() = topics.map { it.first }

    /** Recordings in a topic, oldest first, so they read as a thread. */
    fun entriesInTopic(topic: String) = entries.filter { it.meta.categories?.contains(topic) == true }.sortedBy { it.meta.created }

    /**
     * Recordings that haven't been sorted into topics yet. Typed text, which is usually a phrase to
     * say rather than a thought, isn't sorted, but a lecture you typed the request for is.
     */
    val unsorted: List<RecordingEntry>
        get() = entries.filter { it.meta.categories == null && (!it.meta.isTyped || it.meta.lecture != null) }

    /** Asks the AI which topics a recording belongs to. Failures are kept in `sortErrors`. */
    suspend fun sortIntoTopics(entryId: String, choice: PolisherChoice) {
        if (sorting[entryId] == true) return
        val entry = entry(entryId) ?: return
        sorting[entryId] = true
        sortErrors.remove(entryId)
        // A lecture is sorted by what it covers, not by the request for it, and keeps its own summary as its gist.
        val text = entry.meta.lecture?.let { "A lecture titled “${entry.meta.title}”. ${it.summary ?: it.topic}" } ?: entry.transcript
        try {
            val insights = Forge.sortIntoTopics(text, topicNames, choice)
            val current = entry(entryId) ?: return
            var meta = current.meta.copy(categories = insights.categories)
            if (insights.gist.isNotEmpty() && meta.lecture == null) meta = meta.copy(gist = insights.gist)
            save(current.copy(meta = meta))
        } catch (error: Exception) {
            sortErrors[entryId] = error.message ?: "Sorting failed."
        } finally {
            sorting.remove(entryId)
        }
    }

    /** Sorts every unsorted recording, one at a time. */
    suspend fun sortAllUnsorted(choice: PolisherChoice) {
        for (entry in unsorted) sortIntoTopics(entry.id, choice)
    }

    /** Sets a recording's topics by hand. */
    suspend fun setCategories(categories: List<String>, entryId: String) {
        val entry = entry(entryId) ?: throw missingRecording
        save(entry.copy(meta = entry.meta.copy(categories = categories)))
    }

    // MARK: References

    /** Every saved reference with the recording it was found for, newest first. */
    val allReferences: List<Pair<Reference, RecordingEntry>>
        get() = entries.flatMap { entry -> entry.meta.references.map { it to entry } }.sortedByDescending { it.first.found }

    /** Searches for reading related to a recording and adds what's found (skipping ones it already has). */
    suspend fun findReferences(entryId: String, claudeModel: String) {
        if (findingReferences[entryId] == true) return
        val entry = entry(entryId) ?: return
        findingReferences[entryId] = true
        referenceErrors.remove(entryId)
        referenceNotices.remove(entryId)
        try {
            val result = Forge.findReferences(entry, claudeModel)
            val current = entry(entryId) ?: return
            val known = current.meta.references.mapNotNull { ResearchLoop.normalize(it.url) }.toSet()
            val new = result.references.filter { reference -> ResearchLoop.normalize(reference.url)?.let { it !in known } ?: false }
            save(current.copy(meta = current.meta.copy(references = current.meta.references + new)))
            referenceNotices[entryId] = when {
                new.isEmpty() && result.references.isEmpty() && result.unverified > 0 ->
                    "Some possible sources came up, but their links couldn't be confirmed, so nothing was saved. Try again."
                new.isEmpty() && result.references.isEmpty() -> "No good matches came up this time."
                new.isEmpty() -> "Nothing new: everything found was already saved."
                new.size == 1 -> "Found 1 new reference."
                else -> "Found ${new.size} new references."
            }
        } catch (error: Exception) {
            referenceErrors[entryId] = error.message ?: "Finding references failed."
        } finally {
            findingReferences.remove(entryId)
        }
    }

    suspend fun deleteReference(referenceId: UUID, entryId: String) {
        val entry = entry(entryId) ?: return
        runCatching { save(entry.copy(meta = entry.meta.copy(references = entry.meta.references.filter { it.id != referenceId }))) }
    }

    /** How far off past lecture estimates ran for a model (what Claude actually charged over the unadjusted estimate), newest first. */
    fun lectureEstimateRatios(model: String): List<Double> = entries.mapNotNull { entry ->
        val info = entry.meta.lecture ?: return@mapNotNull null
        val actual = info.claudeCost
        val estimated = info.estimatedClaudeCost
        if (info.model != model || actual == null || estimated == null || estimated <= 0) null else actual / estimated
    }

    // MARK: Helpers

    private val missingRecording get() = PipelineError("That recording is no longer there.")

    private suspend fun writeText(text: String, file: String, entryId: String) =
        withContext(Dispatchers.IO) { File(File(root, entryId), file).writeText(text) }

    /** Writes a recording's meta.json and updates the in-memory copy. */
    private suspend fun save(entry: RecordingEntry) {
        withContext(Dispatchers.IO) { File(File(root, entry.folderName), RecordingFiles.META).writeText(RecordingFiles.encodeMeta(entry.meta)) }
        replace(entry)
    }

    private fun replace(entry: RecordingEntry) {
        val index = entries.indexOfFirst { it.id == entry.id }
        entries = if (index >= 0) entries.toMutableList().also { it[index] = entry } else listOf(entry) + entries
        saveIndex()
    }

    private fun loadIndex(): List<RecordingEntry> = runCatching {
        PearlJson.parseToJsonElement(indexFile.readText()).asArray().orEmpty().mapNotNull { it.asObject()?.let(RecordingEntry::fromJson) }
    }.getOrDefault(emptyList())

    private fun saveIndex() {
        val snapshot = entries
        runCatching { indexFile.writeText(JsonArray(snapshot.map { it.toJson() }).toString()) }
    }
}
