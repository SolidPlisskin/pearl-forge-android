package com.solidplisskin.pearlforge

import com.solidplisskin.pearlforge.pipeline.AnthropicClient
import com.solidplisskin.pearlforge.pipeline.ClaudeUsage
import com.solidplisskin.pearlforge.pipeline.ElevenLabsClient
import com.solidplisskin.pearlforge.pipeline.LectureWriter
import com.solidplisskin.pearlforge.pipeline.OpenAICompatibleClient
import com.solidplisskin.pearlforge.pipeline.PearlJson
import com.solidplisskin.pearlforge.pipeline.PipelineError
import com.solidplisskin.pearlforge.pipeline.PolisherProvider
import com.solidplisskin.pearlforge.pipeline.PubMedClient
import com.solidplisskin.pearlforge.pipeline.ReferenceFinder
import com.solidplisskin.pearlforge.pipeline.ResearchProgress
import com.solidplisskin.pearlforge.pipeline.SpeechLanguages
import com.solidplisskin.pearlforge.pipeline.arr
import com.solidplisskin.pearlforge.pipeline.asObject
import com.solidplisskin.pearlforge.pipeline.jsonOf
import com.solidplisskin.pearlforge.pipeline.str
import com.solidplisskin.pearlforge.recordings.ReferenceKind
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The cloud calls against stand-in services: request shapes, replies, errors and source checking. */
class CloudCallsTest {
    private lateinit var server: MockWebServer
    private val base get() = server.url("/v1").toString().trimEnd('/')

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() = server.shutdown()

    private fun reply(body: Any, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(jsonOf(body).toString()))

    @Test fun elevenLabsCreditsVoicesAndErrors() = runBlocking {
        reply(mapOf("tier" to "creator", "character_count" to 56_790, "character_limit" to 121_000, "next_character_count_reset_unix" to 1_793_750_400))
        val credits = ElevenLabsClient("test-key", base).credits()
        assertEquals(64_210, credits.remaining)
        assertEquals(0.18, credits.dollarsPerThousand!!, 1e-9)
        val request = server.takeRequest()
        assertEquals("/v1/user/subscription", request.path)
        assertEquals("test-key", request.getHeader("xi-api-key"))

        reply(mapOf("voices" to listOf(
            mapOf("voice_id" to "b", "name" to "Rachel", "category" to "premade"),
            mapOf("voice_id" to "a", "name" to "Jaybot", "category" to "professional"))))
        assertEquals(listOf("Jaybot", "Rachel"), ElevenLabsClient("k", base).listVoices().map { it.name })
        server.takeRequest()

        reply(mapOf("detail" to mapOf("status" to "missing_permissions", "message" to "The API key you used is missing the permission user_read")), 401)
        val error = runCatching { ElevenLabsClient("k", base).credits() }.exceptionOrNull()
        assertEquals("Could not read your ElevenLabs credits (401): The API key you used is missing the permission user_read", error?.message)
    }

    @Test fun elevenLabsTranscribesAndSpeaksInChunks() = runBlocking {
        val audio = File.createTempFile("note", ".m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        reply(mapOf("text" to "  Um, hello there.  "))
        assertEquals("Um, hello there.", ElevenLabsClient("k", base).transcribe(audio))
        val upload = server.takeRequest().body.readUtf8()
        fun field(name: String, value: String) =
            assertTrue(name, Regex("name=\"$name\"\r\n(?:[^\r\n]+\r\n)*\r\n$value\r\n").containsMatchIn(upload))
        field("model_id", "scribe_v1")
        field("tag_audio_events", "false")
        field("diarize", "false")
        assertTrue(upload.contains("Content-Type: audio/mp4"))

        val text = (1..300).joinToString(" ") { "Sentence $it is right here." }
        repeat(4) { server.enqueue(MockResponse().setBody("MP3")) }
        val speech = ElevenLabsClient("k", base).synthesize(text, "voice-1")
        val bodies = (0 until server.requestCount - 1).map { server.takeRequest() }.map { PearlJson.parseToJsonElement(it.body.readUtf8()).asObject()!! }
        assertEquals(bodies.size * 3, speech.size)
        assertEquals("eleven_multilingual_v2", bodies[0].str("model_id"))
        assertNull(bodies[0]["previous_text"])
        assertEquals(bodies[0].str("text"), bodies[1].str("previous_text"))
        assertNull(bodies.last()["next_text"])
    }

    @Test fun claudeCompletionUsesFallbackAndReadsOnlyText() = runBlocking {
        reply(mapOf("model" to "claude-opus-5-5", "stop_reason" to "end_turn", "content" to listOf(
            mapOf("type" to "thinking", "thinking" to ""), mapOf("type" to "text", "text" to "  Polished.  "))))
        val reply = AnthropicClient("sk-test", "ws-1", base).complete("system", "user", "claude-opus-5-5")
        assertEquals("Polished.", reply.text)
        val request = server.takeRequest()
        assertEquals("sk-test", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        assertEquals("ws-1", request.getHeader("anthropic-workspace-id"))
        assertEquals("server-side-fallback-2026-07-01", request.getHeader("anthropic-beta"))
        val body = PearlJson.parseToJsonElement(request.body.readUtf8()).asObject()!!
        assertEquals("default", body.str("fallbacks"))
        assertNull(body["thinking"])
        assertEquals("16000", body["max_tokens"].toString())

        reply(mapOf("model" to "claude-opus-4-7", "stop_reason" to "max_tokens", "content" to listOf(mapOf("type" to "text", "text" to "Half"))))
        val error = runCatching { AnthropicClient("k", null, base).refine("t", "s", "claude-opus-4-7") }.exceptionOrNull()
        assertTrue(error is PipelineError && error.message!!.contains("cut off"))
        val older = PearlJson.parseToJsonElement(server.takeRequest().body.readUtf8()).asObject()!!
        assertEquals("adaptive", older["thinking"]?.asObject()?.str("type"))
        assertNull(older["fallbacks"])
    }

    @Test fun openAICompatibleRepliesAndRefusals() = runBlocking {
        reply(mapOf("model" to "gpt-5.5", "choices" to listOf(mapOf("finish_reason" to "stop",
            "message" to mapOf("content" to listOf(mapOf("type" to "text", "text" to "<think>hmm</think> Hello")))))))
        val ok = OpenAICompatibleClient(PolisherProvider.OPENROUTER, "or-key", base).complete("s", "u", "x")
        assertEquals("Hello", ok.text)
        val request = server.takeRequest()
        assertEquals("Bearer or-key", request.getHeader("Authorization"))
        assertEquals("Pearl Forge", request.getHeader("X-Title"))

        reply(mapOf("choices" to listOf(mapOf("finish_reason" to "content_filter", "message" to mapOf("content" to "")))))
        assertEquals("refusal", OpenAICompatibleClient(PolisherProvider.OPENAI, "k", base).complete("s", "u", "x").stopReason)
    }

    /** One search round (web + PubMed), then the finished lecture: sources checked, usage added up, progress reported. */
    @Test fun lectureResearchLoopChecksSources() = runBlocking {
        PubMedClient.base = base
        reply(mapOf("model" to "claude-sonnet-5-5", "stop_reason" to "tool_use",
            "usage" to mapOf("input_tokens" to 5, "output_tokens" to 200, "cache_creation_input_tokens" to 4000, "cache_read_input_tokens" to 0,
                             "server_tool_use" to mapOf("web_search_requests" to 1)),
            "content" to listOf(
                mapOf("type" to "server_tool_use", "id" to "s1", "name" to "web_search", "input" to mapOf("query" to "sleep and memory")),
                mapOf("type" to "web_search_tool_result", "tool_use_id" to "s1", "content" to listOf(
                    mapOf("type" to "web_search_result", "url" to "https://www.example.org/sleep/", "title" to "Sleep"))),
                mapOf("type" to "tool_use", "id" to "p1", "name" to "pubmed_search", "input" to mapOf("query" to "sleep AND memory", "max_results" to 1)))))
        reply(mapOf("esearchresult" to mapOf("idlist" to listOf("12345678"))))
        server.enqueue(MockResponse().setBody("""<?xml version="1.0"?><!DOCTYPE PubmedArticleSet PUBLIC "-//NLM//DTD PubMedArticle//EN" "https://dtd.nlm.nih.gov/ncbi/pubmed/out/pubmed_250101.dtd"><PubmedArticleSet><PubmedArticle><MedlineCitation><PMID>12345678</PMID><Article><Journal><Title>Sleep Journal</Title><JournalIssue><PubDate><Year>2020</Year></PubDate></JournalIssue></Journal><ArticleTitle>Sleep and <i>memory</i>.</ArticleTitle><Abstract><AbstractText>Sleep helps memory.</AbstractText></Abstract><AuthorList><Author><LastName>Doe</LastName><Initials>J</Initials></Author></AuthorList></Article></MedlineCitation></PubmedArticle></PubmedArticleSet>"""))
        val script = "## Opening\n\n**Sleep** matters. " + "We spend a third of our lives asleep, and researchers now think it does real work. ".repeat(5)
        reply(mapOf("model" to "claude-sonnet-5-5", "stop_reason" to "tool_use",
            "usage" to mapOf("input_tokens" to 3, "output_tokens" to 2500, "cache_creation_input_tokens" to 3000, "cache_read_input_tokens" to 4000),
            "content" to listOf(mapOf("type" to "tool_use", "id" to "w1", "name" to "write_lecture", "input" to mapOf(
                "title" to "", "summary" to "Why sleep matters.", "script" to script, "sources" to listOf(
                    mapOf("title" to "Sleep", "url" to "http://example.org/sleep", "source" to "Example", "kind" to "article", "year" to "", "authors" to "", "pmid" to "", "note" to "n"),
                    mapOf("title" to "Paper", "url" to "https://pubmed.ncbi.nlm.nih.gov/12345678/", "source" to "?", "kind" to "research", "year" to "", "authors" to "", "pmid" to "12345678", "note" to "n"),
                    mapOf("title" to "Made up", "url" to "https://invented.example.com/a", "source" to "Nowhere", "kind" to "essay", "year" to "", "authors" to "", "pmid" to "", "note" to "n")))))))
        val progress = mutableListOf<ResearchProgress>()
        val lecture = LectureWriter.write(AnthropicClient("k", null, base), "claude-sonnet-5-5", "why we sleep", 3,
                                          SpeechLanguages.named("it")) { progress.add(it) }

        assertEquals("Why we sleep", lecture.title)
        assertTrue(lecture.script.startsWith("Opening") && !lecture.script.contains("**"))
        assertEquals(listOf("https://www.example.org/sleep/", "https://pubmed.ncbi.nlm.nih.gov/12345678/"), lecture.references.map { it.url })
        val paper = lecture.references[1]
        assertEquals(ReferenceKind.RESEARCH, paper.kind)
        assertEquals("Sleep and memory.", paper.title)
        assertEquals("Sleep Journal", paper.source)
        assertEquals("Doe J", paper.authors)
        assertEquals(1, lecture.unverified)
        assertEquals(ClaudeUsage(8, 7000, 4000, 2700, 1), lecture.usage)
        assertTrue(abs(lecture.cost!! - ((8 * 2 + 7000 * 2.5 + 4000 * 0.1 + 2700 * 10) / 1e6 + 0.01)) < 1e-9)
        assertEquals(ResearchProgress(1, 1, "sleep AND memory"), progress.last())

        val first = PearlJson.parseToJsonElement(server.takeRequest().body.readUtf8()).asObject()!!
        assertTrue(first.str("system")!!.contains("About 450 words") && first.str("system")!!.contains("in Italian"))
        assertEquals(listOf("web_search", "pubmed_search", "write_lecture"), first.arr("tools")!!.map { it.asObject()!!.str("name") })
        assertEquals("ephemeral", first["cache_control"]?.asObject()?.str("type"))
        server.takeRequest(); server.takeRequest()
        // The second Claude call sends the first assistant turn back unchanged, then the PubMed result.
        val second = PearlJson.parseToJsonElement(server.takeRequest().body.readUtf8()).asObject()!!
        val messages = second.arr("messages")!!
        assertEquals(3, messages.size)
        assertEquals("assistant", messages[1].asObject()!!.str("role"))
        val toolResult = messages[2].asObject()!!.arr("content")!![0].asObject()!!
        assertEquals("p1", toolResult.str("tool_use_id"))
        assertTrue(toolResult.str("content")!!.contains("12345678"))
    }

    @Test fun findReadingStopsWithClearMessages() = runBlocking {
        reply(mapOf("model" to "claude-sonnet-5-5", "stop_reason" to "refusal", "content" to emptyList<Any>()))
        val error = runCatching { ReferenceFinder.find(AnthropicClient("k", null, base), "claude-sonnet-5-5", "t", null, emptyList()) }.exceptionOrNull()
        assertEquals("Claude declined to search for this recording.", error?.message)

        reply(mapOf("model" to "claude-sonnet-5-5", "stop_reason" to "max_tokens", "content" to emptyList<Any>()))
        val cut = runCatching { LectureWriter.write(AnthropicClient("k", null, base), "claude-sonnet-5-5", "x", 20, null) }.exceptionOrNull()
        assertFalse(cut == null)
        assertTrue(cut!!.message!!.contains("shorter length"))
    }
}
