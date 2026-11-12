package com.solidplisskin.pearlforge

import com.solidplisskin.pearlforge.pipeline.AvatarVideo
import com.solidplisskin.pearlforge.pipeline.ElevenLabsClient
import com.solidplisskin.pearlforge.pipeline.LectureEstimate
import com.solidplisskin.pearlforge.pipeline.LectureLength
import com.solidplisskin.pearlforge.pipeline.LecturePrompt
import com.solidplisskin.pearlforge.pipeline.LectureRequest
import com.solidplisskin.pearlforge.pipeline.PearlJson
import com.solidplisskin.pearlforge.pipeline.RefinementMode
import com.solidplisskin.pearlforge.pipeline.RefinementPrompts
import com.solidplisskin.pearlforge.pipeline.ReferenceFinder
import com.solidplisskin.pearlforge.pipeline.ResearchLoop
import com.solidplisskin.pearlforge.pipeline.SpeechLanguages
import com.solidplisskin.pearlforge.pipeline.TopicSorter
import com.solidplisskin.pearlforge.pipeline.TranslationPrompt
import com.solidplisskin.pearlforge.pipeline.VideoModel
import com.solidplisskin.pearlforge.pipeline.VoiceCredits
import com.solidplisskin.pearlforge.pipeline.arr
import com.solidplisskin.pearlforge.pipeline.asArray
import com.solidplisskin.pearlforge.pipeline.asObject
import com.solidplisskin.pearlforge.pipeline.jsonOf
import com.solidplisskin.pearlforge.pipeline.obj
import com.solidplisskin.pearlforge.pipeline.str
import com.solidplisskin.pearlforge.pipeline.stringValue
import com.solidplisskin.pearlforge.pipeline.swiftNumber
import com.solidplisskin.pearlforge.recordings.LectureInfo
import com.solidplisskin.pearlforge.recordings.Pearl
import com.solidplisskin.pearlforge.recordings.RecordingEntry
import com.solidplisskin.pearlforge.recordings.RecordingFiles
import com.solidplisskin.pearlforge.recordings.RecordingMeta
import com.solidplisskin.pearlforge.recordings.TextLanguage
import java.util.Date
import java.util.UUID
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the Android port against "golden" files the iPhone app's own Swift code wrote: every
 * prompt word for word, the tool definitions, lecture detection, estimates, speech chunking,
 * folder names, meta.json, video models and prices, and pearl languages.
 */
class MatchesIPhoneAppTest {
    private fun golden(name: String): String =
        javaClass.classLoader!!.getResource("golden/$name")!!.readText()

    @Test fun polishingPromptsAreWordForWord() {
        for (mode in RefinementMode.polishingModes) {
            assertEquals("refine-${mode.raw}", golden("refine-${mode.raw}.txt"), RefinementPrompts.system(mode))
            assertEquals("refine-${mode.raw} with notes", golden("refine-${mode.raw}-notes.txt"),
                         RefinementPrompts.system(mode, "Say it in Italian, the way a native speaker would. keep the bridge analogy"))
        }
    }

    @Test fun translationAndLanguagesMatch() {
        assertEquals(golden("translate-french.txt"), TranslationPrompt.system(SpeechLanguages.named("fr")!!))
        assertEquals(golden("languages.txt"), SpeechLanguages.all.joinToString("\n") { "${it.code}\t${it.name}" })
        assertEquals(golden("language-request-german.txt"), SpeechLanguages.request(SpeechLanguages.named("de")!!))
    }

    @Test fun topicPromptsMatch() {
        assertEquals(golden("topics-empty.txt"), TopicSorter.system(emptyList()))
        assertEquals(golden("topics-existing.txt"), TopicSorter.system(listOf("Work & Career", "Dogs", "relationships")))
    }

    @Test fun researchAndLecturePromptsMatch() {
        assertEquals(golden("reference-system.txt"), ReferenceFinder.system)
        assertEquals(golden("reference-user.txt"), ReferenceFinder.userMessage("I said this.", "I'm wondering.", listOf("Money", "Work & Career")))
        assertEquals(golden("lecture-10-none.txt"), LecturePrompt.system(10, null))
        assertEquals(golden("lecture-3-italian.txt"), LecturePrompt.system(3, SpeechLanguages.named("it")))
        assertEquals(golden("lecture-user.txt"), LecturePrompt.user("the strangeness of quantum entanglement"))
    }

    @Test fun toolDefinitionsMatch() {
        fun same(file: String, tool: Map<String, Any?>) =
            assertEquals(file, PearlJson.parseToJsonElement(golden(file)), jsonOf(tool))
        same("tool-save-references.json", ReferenceFinder.saveTool)
        same("tool-write-lecture.json", LecturePrompt.writeTool)
        same("tool-pubmed.json", ResearchLoop.pubMedTool)
        same("tool-web-search-7.json", ResearchLoop.webSearchTool(7))
    }

    @Test fun lectureDetectionMatches() {
        for (line in golden("lecture-phrases.tsv").lines()) {
            val (phrase, topic, minutes) = line.split("\t")
            val parsed = LectureRequest.parse(phrase)
            assertEquals(phrase, topic, parsed?.topic ?: "-")
            assertEquals(phrase, minutes, parsed?.minutes?.toString() ?: "-")
        }
    }

    @Test fun lectureEstimatesMatch() {
        for (line in golden("lecture-estimates.tsv").lines()) {
            val f = line.split("\t")
            val minutes = f[1].toInt()
            val fresh = LectureEstimate.make(minutes, f[0], VoiceCredits("creator", 0, 121_000, null), emptyList())!!
            val learned = LectureEstimate.make(minutes, f[0], null, listOf(0.5, 0.7, 0.6))!!
            val expected = f.drop(2).map { it.toDouble() }
            val actual = listOf(fresh.claudeLow, fresh.claudeHigh, fresh.claudeBase, fresh.credits.toDouble(), fresh.voiceDollars!!,
                                learned.claudeLow, learned.claudeHigh)
            expected.zip(actual).forEach { (e, a) -> assertTrue("$line: $a", abs(e - a) < 1e-5) }
        }
        assertEquals(10_000, LectureLength.characters(10))
    }

    @Test fun speechChunkingMatches() {
        val text = (1..400).joinToString(" ") { "Sentence number $it is here." } + "\n\nA new paragraph starts. It has two sentences."
        assertEquals(golden("split-speech.txt"), ElevenLabsClient.splitForSpeech(text).joinToString("\n----\n"))
    }

    @Test fun folderNamesAndTitlesMatch() {
        assertEquals(golden("folder-names.txt"), listOf(
            RecordingFiles.slug("I think there's a decent chance I procrastinate because"),
            RecordingFiles.slug("   "),
            RecordingFiles.slug("Café olé — naïve résumé works great"),
            RecordingFiles.title("First line here\nSecond line"),
        ).joinToString("\n"))
    }

    @Test fun englishVersionPromptAndVideoDirectionsAreWordForWord() {
        assertEquals(golden("translate-english.txt"), TranslationPrompt.english)
        assertEquals(golden("avatar-directions.txt"), AvatarVideo.DIRECTIONS)
        assertEquals(golden("avatar-needs-pro.txt"), AvatarVideo.NEEDS_PRO_MESSAGE)
    }

    private fun number(text: String): Double? = if (text == "-") null else text.toDouble()

    private fun close(label: String, expected: Double?, actual: Double?) {
        if (expected == null || actual == null) return assertEquals(label, expected, actual)
        assertTrue("$label: expected $expected, got $actual", abs(expected - actual) <= 1e-12 * maxOf(1.0, abs(expected)))
    }

    @Test fun videoModelsAndPricesMatch() {
        val lines = golden("video-models.tsv").lines()
        assertEquals(VideoModel.ALL.size, lines.size)
        lines.zip(VideoModel.ALL).forEach { (line, model) ->
            val f = line.split("\t")
            assertEquals(f[0], model.id)
            assertEquals(f[1], model.name)
            assertEquals(f[2], model.strength)
            assertEquals(f[3], model.falEndpoint ?: "-")
            assertEquals(f[4], model.falInput.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }.ifEmpty { "-" })
            close("${model.id} price", number(f[5]), model.dollarsPerSecond)
            assertEquals(f[6].toBoolean(), model.billsWholeSeconds)
            close("${model.id} longest", number(f[7]), model.maxSeconds)
            assertEquals(f[8], model.priceLine)
        }
        for (line in golden("video-costs.tsv").lines()) {
            val (id, seconds, cost) = line.split("\t")
            close("$id for $seconds s", number(cost), VideoModel.withId(id).cost(seconds.toDouble()))
        }
        for (line in golden("video-model-names.tsv").lines()) {
            val (key, expected) = line.split("\t")
            val actual = if (key.startsWith("id:")) VideoModel.withId(key.removePrefix("id:").ifEmpty { null }).id else VideoModel.nameOfEndpoint(key)
            assertEquals(key, expected, actual)
        }
        for (line in golden("video-plan-tiers.tsv").lines()) {
            val (tier, allows) = line.split("\t")
            assertEquals(tier, allows, AvatarVideo.planAllowsApp(if (tier == "nil") null else tier)?.toString() ?: "-")
        }
    }

    @Test fun videoCreditEstimatesMatch() {
        // The same earlier videos the Swift code was given.
        fun pearl(number: Int, created: Long, credits: Int?, seconds: Double?, collecting: String? = null) =
            Pearl(UUID.randomUUID(), number, Date(created * 1000), RefinementMode.CLEAN, textFile = "x.txt",
                  videoCredits = credits, videoSeconds = seconds, videoGenerationId = collecting)
        val history = listOf(
            pearl(1, 1000, 1200, 10.0), pearl(2, 2000, 3000, 12.5), pearl(3, 3000, 900, 9.0), pearl(4, 4000, null, 8.0),
            pearl(5, 5000, 5000, 20.0, "still-making"), pearl(6, 6000, 2100, 7.0), pearl(7, 7000, 4400, 22.0),
            pearl(8, 8000, 1000, 0.0), pearl(9, 500, 99999, 1.0),
        )
        val expected = golden("video-credit-rates.tsv").lines().associate { it.split("\t").let { f -> f[0] to number(f[1]) } }
        close("all", expected["all"], AvatarVideo.creditsPerSecond(history))
        close("first3", expected["first3"], AvatarVideo.creditsPerSecond(history.take(3)))
        close("none", expected["none"], AvatarVideo.creditsPerSecond(emptyList()))
    }

    @Test fun writesMetaJsonByteForByteLikeTheIPhone() {
        for (file in listOf("thought-meta.json", "lecture-meta.json", "video-meta.json", "number-meta.json")) {
            val meta = RecordingFiles.decodeMeta(golden(file))
            assertNotNull(file, meta)
            assertEquals(file, golden(file), RecordingFiles.encodeMeta(meta!!))
        }
        val video = RecordingFiles.decodeMeta(golden("video-meta.json"))!!
        val (fal, eleven, added) = video.pearls
        assertEquals("pearl-01-clean.mp4", fal.videoFile)
        assertEquals("fal-ai/kling-video/ai-avatar/v2/standard", fal.videoModel)
        close("cost", VideoModel.STANDARD.cost(12.35591836734694), fal.videoCost)
        assertTrue(fal.english!!.startsWith("Alas, eleventy-one years"))
        assertEquals("01a1b66b-492f-7d50-9372-7ac74c31fbef", eleven.videoGenerationId)
        assertEquals(4200, eleven.videoCredits)
        assertEquals(10.0, eleven.videoSeconds!!, 0.0)
        assertEquals("pearl-03-summarize.mov", added.videoFile)
        // Numbers as Swift writes them.
        assertEquals(listOf("0.30000000000000004", "1e-05", "1234567", "100.25", "1e+21", "0.000123"),
                     listOf(0.1 + 0.2, 1e-05, 1234567.0, 100.25, 1e21, 0.000123).map(::swiftNumber))
    }

    @Test fun keepsFieldsItDoesNotKnow() {
        val original = PearlJson.parseToJsonElement(golden("video-meta.json")).asObject()!!
        fun JsonObject.with(key: String, value: Any) = JsonObject(this + (key to jsonOf(value)))
        val pearls = original.arr("pearls")!!.map { it.asObject()!! }
        val newer = original.with("future_top", mapOf("nested" to listOf(1, 2.5, "three")))
            .with("pearls", listOf(pearls[0].with("video_quality", "1080p")) + pearls.drop(1))
            .with("references", listOf(mapOf("id" to "11111111-2222-4333-8444-555555555555", "title" to "T", "url" to "https://example.org/",
                                              "source" to "S", "kind" to "essay", "note" to "N", "found" to "2026-10-24T14:30:12Z",
                                              "doi" to "10.1000/xyz")))
            .with("lecture", mapOf("topic" to "t", "minutes" to 5, "model" to "claude-sonnet-5-5", "voice_model" to "eleven_v4"))
        val meta = RecordingMeta.fromJson(newer)!!
        assertEquals(newer, PearlJson.parseToJsonElement(RecordingFiles.encodeMeta(meta)))
        // Editing a pearl keeps them too.
        val edited = meta.copy(pearls = meta.pearls.map { it.copy(english = null) })
        val written = PearlJson.parseToJsonElement(RecordingFiles.encodeMeta(edited)).asObject()!!
        assertEquals("1080p", written.arr("pearls")!![0].asObject()!!.str("video_quality"))
        assertNull(written.arr("pearls")!![0].asObject()!!["english"])
        // The first version's top-level fields become pearl 1 and aren't written back, as on the iPhone.
        val legacy = RecordingMeta.fromJson(PearlJson.parseToJsonElement(
            """{"title":"x","created":"2026-10-15T10:00:00Z","mode":"tighten","output_file":"refined.mp3","voice_id":"v","refined_by":"m"}""").asObject()!!)!!
        val legacyOut = PearlJson.parseToJsonElement(RecordingFiles.encodeMeta(legacy)).asObject()!!
        assertTrue(listOf("mode", "output_file", "voice_id", "refined_by").none { it in legacyOut })
    }

    @Test fun pearlLanguagesMatchTheIPhone() {
        TextLanguage.systemDetector = null
        for (line in golden("languages-detected.tsv").lines()) {
            val (text, code, other) = line.split("\t")
            assertEquals(text, code, TextLanguage.code(text) ?: "-")
            assertEquals(text, other, TextLanguage.other(text) ?: "-")
        }
    }

    @Test fun libraryTitlesLanguagesAndVideosMatch() {
        TextLanguage.systemDetector = null
        val created = Date(1_762_510_791_000)
        for (case in PearlJson.parseToJsonElement(golden("entry-languages.json")).asArray()!!.map { it.asObject()!! }) {
            val rows = case.arr("pearls")!!.map { it.asObject()!! }
            val pearls = rows.mapIndexed { index, row ->
                Pearl(UUID.fromString(row.str("id")), index + 1, Date(created.time + index * 60_000L), RefinementMode.CLEAN,
                      textFile = "pearl-${index + 1}.txt", english = row.str("english"), videoFile = row.str("video_file"),
                      videoGenerationId = row.str("video_generation_id"))
            }
            val lecture = case.str("lecture_topic")?.let { LectureInfo(it, 5, "claude-sonnet-5-5", "fr") }
            val meta = RecordingMeta(case.str("title")!!, created, null, false, pearls, lecture = lecture)
            val entry = RecordingEntry("f", meta, case.str("transcript")!!, rows.associate { UUID.fromString(it.str("id")) to it.str("text")!! })
            val name = case.str("name")!!
            val expected = case.obj("expected")!!
            assertEquals(name, expected.str("display_title"), entry.displayTitle)
            assertEquals(name, expected.arr("other_languages")!!.map { it.stringValue() }, entry.otherLanguages)
            assertEquals(name, expected.arr("video_pearls")!!.map { it.stringValue() }, entry.videoPearls.map { it.id.toString().uppercase() })
            assertEquals(name, expected.arr("pearls_missing_english")!!.map { it.stringValue() }, entry.pearlsMissingEnglish.map { it.id.toString().uppercase() })
            rows.zip(pearls).forEach { (row, pearl) ->
                assertEquals("$name ${pearl.number}", row.str("other_language"), entry.otherLanguage(pearl))
                assertEquals("$name ${pearl.number}", row.str("readable_text"), entry.readableText(pearl))
            }
        }
    }

    @Test fun readsAndWritesTheIPhoneAppsMetaJson() {
        for (file in listOf("thought-meta.json", "lecture-meta.json")) {
            val original = PearlJson.parseToJsonElement(golden(file))
            val meta = RecordingFiles.decodeMeta(golden(file))
            assertNotNull(file, meta)
            // Written back out, it's the same JSON the iPhone app wrote.
            assertEquals(file, original, PearlJson.parseToJsonElement(RecordingFiles.encodeMeta(meta!!)))
        }
        val lecture = RecordingFiles.decodeMeta(golden("lecture-meta.json"))!!
        assertEquals(10, lecture.lecture?.minutes)
        assertEquals(RefinementMode.LECTURE, lecture.pearls.single().mode)
        val thought = RecordingFiles.decodeMeta(golden("thought-meta.json"))!!
        assertEquals(listOf("Personal Growth", "Mind & Emotions"), thought.categories)
        assertEquals("17201571", thought.references.single().pmid)
        // A first-version meta.json without pearls still reads, as pearl 1.
        val legacy = RecordingMeta.fromJson(PearlJson.parseToJsonElement(
            """{"title":"x","created":"2026-10-15T10:00:00Z","mode":"tighten","output_file":"refined.mp3"}""").asObject()!!)!!
        assertEquals(RefinementMode.TIGHTEN, legacy.pearls.single().mode)
        assertEquals("refined_transcript.txt", legacy.pearls.single().textFile)
    }
}
