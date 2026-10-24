package com.solidplisskin.pearlforge

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
import com.solidplisskin.pearlforge.pipeline.VoiceCredits
import com.solidplisskin.pearlforge.pipeline.asObject
import com.solidplisskin.pearlforge.pipeline.jsonOf
import com.solidplisskin.pearlforge.recordings.RecordingFiles
import com.solidplisskin.pearlforge.recordings.RecordingMeta
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the Android port against "golden" files the iPhone app's own Swift code wrote: every
 * prompt word for word, the tool definitions, lecture detection, estimates, speech chunking,
 * folder names and meta.json.
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
