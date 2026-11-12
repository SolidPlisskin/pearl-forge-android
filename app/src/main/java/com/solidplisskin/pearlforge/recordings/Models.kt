package com.solidplisskin.pearlforge.recordings

import com.solidplisskin.pearlforge.pipeline.RefinementMode
import com.solidplisskin.pearlforge.pipeline.arr
import com.solidplisskin.pearlforge.pipeline.asObject
import com.solidplisskin.pearlforge.pipeline.bool
import com.solidplisskin.pearlforge.pipeline.double
import com.solidplisskin.pearlforge.pipeline.int
import com.solidplisskin.pearlforge.pipeline.jsonOf
import com.solidplisskin.pearlforge.pipeline.obj
import com.solidplisskin.pearlforge.pipeline.str
import com.solidplisskin.pearlforge.pipeline.stringValue
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// The data model, saved as meta.json in each recording's folder. Field names and formats match
// the iPhone app exactly, so the same folder can be opened by either app. Fields this version
// doesn't know (added by a newer iPhone app, say) are kept as they are and written back.

/** Dates as the iPhone app writes them: ISO 8601 in UTC, whole seconds ("2026-10-24T14:30:12Z"). */
internal fun Date.iso(): String = toInstant().truncatedTo(ChronoUnit.SECONDS).toString()
internal fun parseIso(text: String?): Date? = text?.let { runCatching { Date.from(Instant.parse(it)) }.getOrNull() }
internal fun String.toUuid(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
internal val UUID.upper: String get() = toString().uppercase()

/** The fields of a JSON object that aren't in `known`, to be written back unchanged. */
internal fun JsonObject.unknownFields(known: Set<String>) = JsonObject(filterKeys { it !in known })

/** Adds fields kept from the file that this version doesn't know, without overwriting any it wrote. */
internal fun MutableMap<String, Any?>.putUnknown(fields: JsonObject) = fields.forEach { (key, value) -> if (key !in this) put(key, value) }

/** One run of a recording through the forge: a refinement (with its mode and request) and that refinement in your voice. */
data class Pearl(
    val id: UUID,
    val number: Int,
    val created: Date,
    val mode: RefinementMode,
    /** What you asked to be different for this version, if anything. */
    val notes: String? = null,
    /** The model that wrote it; differs from the requested model only when Anthropic's fallback stepped in. */
    val refinedBy: String? = null,
    /** The AI service that wrote it ("anthropic", "openai", …). */
    val polisher: String? = null,
    val voiceId: String? = null,
    val textFile: String,
    val audioFile: String? = null,
    /** The pearl as a video of your avatar speaking it, made by ElevenLabs or fal.ai, or added from Photos or Files. */
    val videoFile: String? = null,
    /** A video that was started but not yet saved here, so it can be collected later if Pearl Forge was closed while it was being made. */
    val videoGenerationId: String? = null,
    /** The fal.ai model making or that made the video; null for ElevenLabs. */
    val videoModel: String? = null,
    /**
     * What making the video used, when Pearl Forge made it: ElevenLabs credits or fal.ai's price in
     * dollars, and how many seconds of speech they covered.
     */
    val videoCredits: Int? = null,
    val videoCost: Double? = null,
    val videoSeconds: Double? = null,
    /** The pearl in English, for a pearl written in another language, so you can tell what it (and its video) says. Cleared when the text changes. */
    val english: String? = null,
    val unknownFields: JsonObject = JsonObject(emptyMap()),
) {
    fun toJson(): JsonElement = jsonOf(buildMap {
        put("id", id.upper)
        put("number", number)
        put("created", created.iso())
        put("mode", mode.raw)
        notes?.let { put("notes", it) }
        refinedBy?.let { put("refined_by", it) }
        polisher?.let { put("polisher", it) }
        voiceId?.let { put("voice_id", it) }
        put("text_file", textFile)
        audioFile?.let { put("audio_file", it) }
        videoFile?.let { put("video_file", it) }
        videoGenerationId?.let { put("video_generation_id", it) }
        videoModel?.let { put("video_model", it) }
        videoCredits?.let { put("video_credits", it) }
        videoCost?.let { put("video_cost", it) }
        videoSeconds?.let { put("video_seconds", it) }
        english?.let { put("english", it) }
        putUnknown(unknownFields)
    })

    companion object {
        private val knownFields = setOf("id", "number", "created", "mode", "notes", "refined_by", "polisher", "voice_id", "text_file",
                                        "audio_file", "video_file", "video_generation_id", "video_model", "video_credits", "video_cost",
                                        "video_seconds", "english")

        /** "pearl-02-tighten", the start of a pearl's file names. */
        fun baseName(number: Int, mode: RefinementMode) = "pearl-%02d-%s".format(number, mode.raw)

        fun fromJson(json: JsonObject): Pearl? = Pearl(
            id = json.str("id")?.toUuid() ?: return null,
            number = json.int("number") ?: return null,
            created = parseIso(json.str("created")) ?: return null,
            mode = RefinementMode.from(json.str("mode")) ?: RefinementMode.CLEAN,
            notes = json.str("notes"),
            refinedBy = json.str("refined_by"),
            polisher = json.str("polisher"),
            voiceId = json.str("voice_id"),
            textFile = json.str("text_file") ?: return null,
            audioFile = json.str("audio_file"),
            videoFile = json.str("video_file"),
            videoGenerationId = json.str("video_generation_id"),
            videoModel = json.str("video_model"),
            videoCredits = json.int("video_credits"),
            videoCost = json.double("video_cost"),
            videoSeconds = json.double("video_seconds"),
            english = json.str("english"),
            unknownFields = json.unknownFields(knownFields),
        )
    }
}

/** What kind of reading a reference is. */
enum class ReferenceKind(val raw: String, val title: String) {
    RESEARCH("research", "Research"),
    ARTICLE("article", "Article"),
    ESSAY("essay", "Essay"),
    BLOG("blog", "Blog post"),
    BOOK("book", "Book"),
    OTHER("other", "Other");

    companion object {
        fun from(raw: String?): ReferenceKind? = entries.firstOrNull { it.raw == raw }
    }
}

/** A piece of reading found for a recording: a research paper, essay, article or blog post. */
data class Reference(
    val id: UUID,
    val title: String,
    val url: String,
    /** The site or journal: "The Atlantic", "JAMA Pediatrics". */
    val source: String,
    val kind: ReferenceKind,
    val year: String?,
    val authors: String?,
    /** How it relates to the thought, in a sentence or two. */
    val note: String,
    /** The PubMed id, for papers found through PubMed. */
    val pmid: String?,
    val found: Date,
    val unknownFields: JsonObject = JsonObject(emptyMap()),
) {
    fun toJson(): JsonElement = jsonOf(buildMap {
        put("id", id.upper)
        put("title", title)
        put("url", url)
        put("source", source)
        put("kind", kind.raw)
        year?.let { put("year", it) }
        authors?.let { put("authors", it) }
        put("note", note)
        pmid?.let { put("pmid", it) }
        put("found", found.iso())
        putUnknown(unknownFields)
    })

    companion object {
        private val knownFields = setOf("id", "title", "url", "source", "kind", "year", "authors", "note", "pmid", "found")

        fun fromJson(json: JsonObject): Reference? = Reference(
            id = json.str("id")?.toUuid() ?: return null,
            title = json.str("title") ?: "",
            url = json.str("url") ?: return null,
            source = json.str("source") ?: "",
            kind = ReferenceKind.from(json.str("kind")) ?: ReferenceKind.OTHER,
            year = json.str("year"),
            authors = json.str("authors"),
            note = json.str("note") ?: "",
            pmid = json.str("pmid"),
            found = parseIso(json.str("found")) ?: Date(),
            unknownFields = json.unknownFields(knownFields),
        )
    }
}

/** What a lecture was asked for, and what it cost. */
data class LectureInfo(
    val topic: String,
    /** The length asked for, in minutes. */
    val minutes: Int,
    /** The Claude model that researched and wrote it. */
    val model: String,
    /** The language it was written in, when you chose one. */
    val language: String? = null,
    /** One sentence on what it covers. */
    val summary: String? = null,
    /** What Claude charged, worked out from the usage it reported. */
    val claudeCost: Double? = null,
    /** Claude's estimate before it started (unadjusted), so later estimates can learn how far off it runs. */
    val estimatedClaudeCost: Double? = null,
    val webSearches: Int? = null,
    /** Characters in the spoken text: the ElevenLabs credits it used. */
    val characters: Int? = null,
    /** What 1,000 credits were worth on your ElevenLabs plan at the time. */
    val dollarsPerThousandCredits: Double? = null,
    val unknownFields: JsonObject = JsonObject(emptyMap()),
) {
    /** What speaking it cost, when your plan's rate is known. */
    val voiceCost: Double?
        get() = if (characters != null && dollarsPerThousandCredits != null) characters / 1000.0 * dollarsPerThousandCredits else null

    fun toJson(): JsonElement = jsonOf(buildMap {
        put("topic", topic)
        put("minutes", minutes)
        put("model", model)
        language?.let { put("language", it) }
        summary?.let { put("summary", it) }
        claudeCost?.let { put("claude_cost", it) }
        estimatedClaudeCost?.let { put("estimated_claude_cost", it) }
        webSearches?.let { put("web_searches", it) }
        characters?.let { put("characters", it) }
        dollarsPerThousandCredits?.let { put("dollars_per_thousand_credits", it) }
        putUnknown(unknownFields)
    })

    companion object {
        private val knownFields = setOf("topic", "minutes", "model", "language", "summary", "claude_cost", "estimated_claude_cost",
                                        "web_searches", "characters", "dollars_per_thousand_credits")

        fun fromJson(json: JsonObject): LectureInfo? = LectureInfo(
            topic = json.str("topic") ?: return null,
            minutes = json.int("minutes") ?: return null,
            model = json.str("model") ?: return null,
            language = json.str("language"),
            summary = json.str("summary"),
            claudeCost = json.double("claude_cost"),
            estimatedClaudeCost = json.double("estimated_claude_cost"),
            webSearches = json.int("web_searches"),
            characters = json.int("characters"),
            dollarsPerThousandCredits = json.double("dollars_per_thousand_credits"),
            unknownFields = json.unknownFields(knownFields),
        )
    }
}

/** The details saved alongside each recording as meta.json. */
data class RecordingMeta(
    val title: String,
    val created: Date,
    val originalFile: String?,
    val favorite: Boolean,
    val pearls: List<Pearl>,
    /** Broad topics such as "Relationships" or "Work & Career". Null until the recording has been sorted. */
    val categories: List<String>? = null,
    /** One first-person sentence summing up the thought (or a lecture's summary). */
    val gist: String? = null,
    val references: List<Reference> = emptyList(),
    /** True for text you typed rather than recorded; it has no original audio. */
    val typed: Boolean? = null,
    /** Set for a lecture you asked for; its pearl is the lecture itself. */
    val lecture: LectureInfo? = null,
    val unknownFields: JsonObject = JsonObject(emptyMap()),
) {
    val isTyped: Boolean get() = typed == true

    fun toJson(): JsonElement = jsonOf(buildMap {
        put("title", title)
        put("created", created.iso())
        originalFile?.let { put("original_file", it) }
        put("favorite", favorite)
        put("pearls", pearls.map { it.toJson() })
        categories?.let { put("categories", it) }
        gist?.let { put("gist", it) }
        put("references", references.map { it.toJson() })
        typed?.let { put("typed", it) }
        lecture?.let { put("lecture", it.toJson()) }
        putUnknown(unknownFields)
    })

    companion object {
        /** The fixed id given to the single refinement of a first-version recording. */
        val legacyPearlId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

        /** Its own fields, and the first version's top-level ones, which become pearl 1 and aren't written back (as on the iPhone). */
        private val knownFields = setOf("title", "created", "original_file", "favorite", "pearls", "categories", "gist", "references",
                                        "typed", "lecture", "mode", "voice_id", "output_file", "refined_by")

        fun fromJson(json: JsonObject): RecordingMeta? {
            val created = parseIso(json.str("created")) ?: return null
            val pearls = json.arr("pearls")?.mapNotNull { it.asObject()?.let(Pearl::fromJson) } ?: listOf(
                // A first-version recording: its one refinement becomes pearl 1.
                Pearl(legacyPearlId, 1, created, RefinementMode.from(json.str("mode")) ?: RefinementMode.CLEAN,
                      refinedBy = json.str("refined_by"), polisher = "anthropic", voiceId = json.str("voice_id"),
                      textFile = "refined_transcript.txt", audioFile = json.str("output_file")),
            )
            return RecordingMeta(
                title = json.str("title") ?: return null,
                created = created,
                originalFile = json.str("original_file"),
                favorite = json.bool("favorite") ?: false,
                pearls = pearls,
                categories = json.arr("categories")?.mapNotNull { it.stringValue() },
                gist = json.str("gist"),
                references = json.arr("references")?.mapNotNull { it.asObject()?.let(Reference::fromJson) }.orEmpty(),
                typed = json.bool("typed"),
                lecture = json.obj("lecture")?.let(LectureInfo::fromJson),
                unknownFields = json.unknownFields(knownFields),
            )
        }
    }
}

/** A saved recording with its texts. A copy of these is kept so History opens instantly. */
data class RecordingEntry(
    val folderName: String,
    val meta: RecordingMeta,
    val transcript: String,
    val pearlTexts: Map<UUID, String>,
) {
    val id: String get() = folderName

    /** Newest pearl first. */
    val pearlsNewestFirst: List<Pearl> get() = meta.pearls.sortedByDescending { it.number }

    fun pearl(id: UUID): Pearl? = meta.pearls.firstOrNull { it.id == id }

    /** The newest pearl that has audio, for playing a recording in a list. */
    val latestSpokenPearl: Pearl? get() = pearlsNewestFirst.firstOrNull { it.audioFile != null }

    /** The newest pearl's text, or what you said if there's no pearl yet. */
    val latestText: String get() = pearlsNewestFirst.firstOrNull()?.let { pearlTexts[it.id] } ?: transcript

    // Languages and videos

    /**
     * The title in your language. A recording is titled from its first pearl, so one first spoken
     * in French gets a French title; then the start of what you said or wrote, or the pearl's
     * English version, is used instead.
     */
    val displayTitle: String
        get() {
            val saved = meta.title
            if (!TextLanguage.isOther(saved)) return saved.ifEmpty { "Untitled thought" }
            meta.lecture?.let { return it.topic }
            if (transcript.isNotEmpty() && !TextLanguage.isOther(transcript)) return RecordingFiles.title(transcript)
            meta.pearls.minByOrNull { it.number }?.english?.let { return RecordingFiles.title(it) }
            return saved
        }

    /** The pearl's language when it isn't yours: "Korean". */
    fun otherLanguage(pearl: Pearl): String? = pearlTexts[pearl.id]?.let(TextLanguage::other)

    /** The pearl's text when it's in your language, otherwise its English version if it has one. */
    fun readableText(pearl: Pearl): String? = if (otherLanguage(pearl) == null) pearlTexts[pearl.id] else pearl.english

    /** The other languages its pearls are in, in pearl order: ["French", "Korean"]. */
    val otherLanguages: List<String>
        get() = meta.pearls.sortedBy { it.number }.mapNotNull(::otherLanguage).distinct()

    /** Pearls with a video, or a video still to collect, newest first. */
    val videoPearls: List<Pearl> get() = pearlsNewestFirst.filter { it.videoFile != null || it.videoGenerationId != null }

    /** Pearls in another language with no English version yet. Lectures are left out: they're long, so you ask for theirs on the pearl itself. */
    val pearlsMissingEnglish: List<Pearl>
        get() = if (meta.lecture != null) emptyList() else meta.pearls.filter { it.english == null && otherLanguage(it) != null }

    fun toJson(): JsonElement = jsonOf(mapOf(
        "folderName" to folderName,
        "meta" to meta.toJson(),
        "transcript" to transcript,
        "pearlTexts" to pearlTexts.mapKeys { it.key.upper },
    ))

    companion object {
        fun fromJson(json: JsonObject): RecordingEntry? = RecordingEntry(
            folderName = json.str("folderName") ?: return null,
            meta = json.obj("meta")?.let(RecordingMeta::fromJson) ?: return null,
            transcript = json.str("transcript") ?: "",
            pearlTexts = json.obj("pearlTexts")?.entries?.mapNotNull { (k, v) -> k.toUuid()?.let { it to (v.stringValue() ?: "") } }?.toMap().orEmpty(),
        )
    }
}
