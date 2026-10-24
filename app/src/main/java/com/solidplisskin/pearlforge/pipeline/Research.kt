package com.solidplisskin.pearlforge.pipeline

import com.solidplisskin.pearlforge.recordings.Reference
import com.solidplisskin.pearlforge.recordings.ReferenceKind
import java.net.URI
import java.util.Date
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** How far Claude's research has got: how many searches so far, and the latest one. */
data class ResearchProgress(val webSearches: Int = 0, val pubMedSearches: Int = 0, val latestQuery: String? = null) {
    val searches: Int get() = webSearches + pubMedSearches
}

/**
 * A conversation in which Claude searches the web and PubMed until it calls the finishing tool
 * (save_references, write_lecture, …). Every address that came back from a search is kept, so
 * the sources Claude names can be checked against them, and what the calls used is added up so
 * their cost is known.
 */
class ResearchLoop(
    private val client: AnthropicClient,
    private val model: String,
    private val system: String,
    private val finishTool: Map<String, Any?>,
    private val maxWebSearches: Int = 6,
    private val maxSteps: Int = 12,
    /** Starts the message of a failed request: "Finding references failed: …". */
    private val failure: String = "The search failed",
    /** Sent once if Claude stops without calling the finishing tool. */
    private val nudge: String? = null,
    private val onProgress: (suspend (ResearchProgress) -> Unit)? = null,
) {
    /** How the conversation ended when Claude didn't call the finishing tool. */
    enum class Stop { REFUSAL, CUT_OFF, UNFINISHED, TOO_MANY_STEPS }

    class Stopped(val stop: Stop) : Exception(stop.name)

    data class Outcome(
        /** What Claude passed to the finishing tool. */
        val input: JsonObject,
        val seenUrls: Map<String, String>,
        val papers: Map<String, PubMedPaper>,
        val usage: ClaudeUsage,
        /** The model that answered; differs from the one asked for only when Anthropic's fallback stepped in. */
        val answeredBy: String,
    )

    private val finishName = finishTool["name"] as? String ?: ""

    suspend fun run(user: String): Outcome {
        val messages = mutableListOf<JsonElement>(jsonOf(mapOf("role" to "user", "content" to user)))
        val seenUrls = mutableMapOf<String, String>()
        val papers = mutableMapOf<String, PubMedPaper>()
        val usage = ClaudeUsage()
        var progress = ResearchProgress()
        var answeredBy = model
        var nudged = false

        repeat(maxSteps) {
            val response = send(messages)
            usage.add(response.obj("usage"))
            response.str("model")?.let { answeredBy = it }
            val content = response.arr("content") ?: JsonArray(emptyList())
            collectUrls(content, seenUrls)
            for (block in content.mapNotNull { it.asObject() }.filter { it.str("type") == "server_tool_use" }) {
                progress = progress.copy(webSearches = progress.webSearches + 1,
                                         latestQuery = block.obj("input")?.str("query") ?: progress.latestQuery)
            }

            when (response.str("stop_reason")) {
                // A long search paused; send the turn back unchanged to resume.
                "pause_turn" -> messages.add(assistant(content))
                "tool_use" -> {
                    val calls = content.mapNotNull { it.asObject() }.filter { it.str("type") == "tool_use" }
                    calls.firstOrNull { it.str("name") == finishName }?.let { finish ->
                        return Outcome(finish.obj("input") ?: JsonObject(emptyMap()), seenUrls, papers, usage, answeredBy)
                    }
                    val results = mutableListOf<JsonElement>()
                    for (call in calls) {
                        call.obj("input")?.str("query")?.let { query ->
                            progress = progress.copy(pubMedSearches = progress.pubMedSearches + 1, latestQuery = query)
                        }
                        results.add(runTool(call, papers))
                    }
                    // The assistant turn goes back exactly as it came, thinking blocks included.
                    messages.add(assistant(content))
                    messages.add(jsonOf(mapOf("role" to "user", "content" to JsonArray(results))))
                }
                "end_turn" -> {
                    if (nudged) throw Stopped(Stop.UNFINISHED)
                    // It stopped without finishing; ask once more.
                    nudged = true
                    messages.add(assistant(content))
                    messages.add(jsonOf(mapOf("role" to "user", "content" to (nudge ?: "Please call $finishName now."))))
                }
                "refusal" -> throw Stopped(Stop.REFUSAL)
                "max_tokens" -> throw Stopped(Stop.CUT_OFF)
                else -> throw Stopped(Stop.UNFINISHED)
            }
            onProgress?.invoke(progress)
        }
        throw Stopped(Stop.TOO_MANY_STEPS)
    }

    private fun assistant(content: JsonArray) = jsonOf(mapOf("role" to "assistant", "content" to content))

    private suspend fun send(messages: List<JsonElement>): JsonObject {
        val usesFallback = model in AnthropicClient.fallbackModels
        val body = mutableMapOf<String, Any?>(
            "model" to model,
            "max_tokens" to PipelineConfig.REFINEMENT_MAX_TOKENS,
            "system" to system,
            "messages" to JsonArray(messages),
            "tools" to listOf(webSearchTool(maxWebSearches), pubMedTool, finishTool),
            // Each step resends the earlier search results; caching them makes those repeats much cheaper.
            "cache_control" to mapOf("type" to "ephemeral"),
        )
        if (usesFallback) body["fallbacks"] = "default"
        if (model in AnthropicClient.adaptiveThinkingModels) body["thinking"] = mapOf("type" to "adaptive")
        return client.send(body, failure, usesFallback)
    }

    /** Runs one of Pearl Forge's own tools and returns its result block. */
    private suspend fun runTool(call: JsonObject, papers: MutableMap<String, PubMedPaper>): JsonElement {
        val id = call.str("id") ?: ""
        val input = call.obj("input")
        val query = input?.str("query")
        if (call.str("name") != "pubmed_search" || query.isNullOrEmpty()) {
            return jsonOf(mapOf("type" to "tool_result", "tool_use_id" to id, "is_error" to true, "content" to "Unknown tool or missing query."))
        }
        return try {
            val found = PubMedClient.search(query, input.int("max_results") ?: 6)
            for (paper in found) papers[paper.pmid] = paper
            val summary = found.map { paper ->
                mapOf("pmid" to paper.pmid, "url" to paper.url, "title" to paper.title, "journal" to paper.journal,
                      "year" to paper.year, "authors" to paper.authorLine, "abstract" to paper.abstract.take(1200))
            }
            jsonOf(mapOf("type" to "tool_result", "tool_use_id" to id,
                         "content" to if (found.isEmpty()) "No PubMed results for that query. Try broader or different terms." else jsonOf(summary).toString()))
        } catch (error: Exception) {
            jsonOf(mapOf("type" to "tool_result", "tool_use_id" to id, "is_error" to true, "content" to (error.message ?: "PubMed search failed")))
        }
    }

    companion object {
        /**
         * The basic web search tool. The filtered version (web_search_20260209) runs searches inside
         * code, and in live tests some of the results Claude read never came back as checkable search
         * results, so every reference failed verification. The basic version always returns them.
         */
        fun webSearchTool(maxUses: Int): Map<String, Any?> = mapOf("type" to "web_search_20250305", "name" to "web_search", "max_uses" to maxUses)

        val pubMedTool: Map<String, Any?> = mapOf(
            "name" to "pubmed_search",
            "description" to "Search PubMed, the US National Library of Medicine's database of peer-reviewed biomedical and life-sciences research (including psychology, psychiatry, child development, relationships and health). Returns titles, journals, years and abstracts. Call this whenever the subject touches on something researchers study. Use PubMed search terms, not a sentence: a few key terms joined with AND/OR.",
            "input_schema" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf("type" to "string", "description" to "PubMed query, for example: (emotional numbing OR desensitization) AND moral development"),
                    "max_results" to mapOf("type" to "integer", "description" to "How many papers to return, 1 to 10. Default 6."),
                ),
                "required" to listOf("query"),
            ),
        )

        /** The fields of one source, shared by save_references and write_lecture. */
        fun sourceSchema(noteDescription: String): Map<String, Any?> = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("title", "url", "source", "kind", "year", "authors", "pmid", "note"),
            "properties" to mapOf(
                "title" to mapOf("type" to "string"),
                "url" to mapOf("type" to "string", "description" to "The exact URL from the search results. For PubMed papers: https://pubmed.ncbi.nlm.nih.gov/<PMID>/"),
                "source" to mapOf("type" to "string", "description" to "The site, publication or journal."),
                "kind" to mapOf("type" to "string", "enum" to ReferenceKind.entries.map { it.raw }),
                "year" to mapOf("type" to "string", "description" to "Year published, or empty if unknown."),
                "authors" to mapOf("type" to "string", "description" to "Authors, or empty if unknown."),
                "pmid" to mapOf("type" to "string", "description" to "The PubMed ID for papers from pubmed_search; otherwise empty."),
                "note" to mapOf("type" to "string", "description" to noteDescription),
            ),
        )

        /** Every URL that appeared in web search or fetch results, keyed by its normalized form. */
        fun collectUrls(value: JsonElement, urls: MutableMap<String, String>) {
            when (value) {
                is JsonArray -> value.forEach { collectUrls(it, urls) }
                is JsonObject -> {
                    val type = value.str("type")
                    val url = value.str("url")
                    if (type in setOf("web_search_result", "web_search_result_location", "web_fetch_result") && url != null) {
                        normalize(url)?.let { urls.putIfAbsent(it, url) }
                    }
                    value.values.forEach { collectUrls(it, urls) }
                }
                else -> Unit
            }
        }

        /** A URL reduced to host, path and query, so small differences (http/https, "www.", a trailing slash, a #section) still match. */
        fun normalize(string: String): String? {
            val uri = runCatching { URI(string.trim()) }.getOrNull() ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val bareHost = host.removePrefix("www.")
            val path = (uri.rawPath ?: "").trimEnd('/')
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return bareHost + path + query
        }

        /** "12345678" from a pubmed.ncbi.nlm.nih.gov link. */
        fun pubMedId(url: String): String {
            val uri = runCatching { URI(url) }.getOrNull() ?: return ""
            if (uri.host?.contains("pubmed.ncbi.nlm.nih.gov") != true) return ""
            return (uri.path ?: "").split("/").firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) } ?: ""
        }

        /** Keeps only sources that match a real search result. PubMed papers get their details from PubMed itself. */
        fun verify(suggested: List<JsonObject>, seenUrls: Map<String, String>, papers: Map<String, PubMedPaper>,
                   maxReferences: Int = Int.MAX_VALUE): VerifiedSources {
            val kept = mutableListOf<Reference>()
            val keptUrls = mutableSetOf<String>()
            var unverified = 0
            for (item in suggested) {
                fun field(key: String) = (item.str(key) ?: "").trim()
                val url = field("url")
                val pmid = listOf(field("pmid"), pubMedId(url)).firstOrNull { it.isNotEmpty() && papers[it] != null } ?: ""
                val paper = papers[pmid]
                val found = normalize(url)?.let { seenUrls[it] }
                var reference = when {
                    paper != null -> Reference(
                        UUID.randomUUID(), paper.title.ifEmpty { field("title") }, paper.url,
                        paper.journal.ifEmpty { "PubMed" }, ReferenceKind.RESEARCH, paper.year.ifEmpty { null },
                        paper.authorLine.ifEmpty { null }, field("note"), paper.pmid, Date())
                    // Saved with the address exactly as the search found it.
                    found != null -> Reference(
                        UUID.randomUUID(), field("title"), found, field("source"),
                        ReferenceKind.from(field("kind")) ?: ReferenceKind.OTHER, field("year").ifEmpty { null },
                        field("authors").ifEmpty { null }, field("note"), null, Date())
                    else -> {
                        unverified += 1
                        continue
                    }
                }
                if (reference.title.isEmpty()) reference = reference.copy(title = reference.source)
                val key = normalize(reference.url) ?: continue
                if (!keptUrls.add(key)) continue
                kept.add(reference)
            }
            return VerifiedSources(kept.take(maxReferences), unverified)
        }
    }
}

/** Sources that matched real search results, and how many didn't. */
data class VerifiedSources(val references: List<Reference>, val unverified: Int)

/** Turns a research loop's stop into the sentence shown to you. */
fun ResearchLoop.Stopped.message(refusal: String, cutOff: String, unfinished: String, tooManySteps: String) = when (stop) {
    ResearchLoop.Stop.REFUSAL -> refusal
    ResearchLoop.Stop.CUT_OFF -> cutOff
    ResearchLoop.Stop.UNFINISHED -> unfinished
    ResearchLoop.Stop.TOO_MANY_STEPS -> tooManySteps
}

/**
 * Finds reading related to a recording (research papers from PubMed, and essays, articles and blog
 * posts from the web) using Claude with web search. Every reference is checked against the actual
 * search results, so a title or link the AI made up is never saved.
 */
object ReferenceFinder {
    private const val MAX_REFERENCES = 10

    suspend fun find(client: AnthropicClient, model: String, transcript: String, gist: String?, categories: List<String>): VerifiedSources {
        val loop = ResearchLoop(client, model, system, saveTool, maxWebSearches = 6, maxSteps = 12,
                                failure = "Finding references failed",
                                nudge = "Please call save_references now with the references you found (an empty list is fine).")
        try {
            val outcome = loop.run(userMessage(transcript, gist, categories))
            return ResearchLoop.verify(outcome.input.arr("references").orEmpty().mapNotNull { it.asObject() },
                                       outcome.seenUrls, outcome.papers, MAX_REFERENCES)
        } catch (stopped: ResearchLoop.Stopped) {
            throw PipelineError(stopped.message(
                refusal = "Claude declined to search for this recording.",
                cutOff = "The search ran too long. Please try again.",
                unfinished = "The search finished without saving any references. Please try again.",
                tooManySteps = "The search took too many steps. Please try again.",
            ))
        }
    }

    val system = """
        You are Pearl Forge's research assistant. The speaker recorded a voice note while thinking out loud, and you are given what they said. Find reading that would help them think further about the ideas in it.

        - Peer-reviewed research: use pubmed_search when the thought touches on something researchers study, such as relationships, parenting, emotions, behaviour, health, sleep, learning or work. Read the abstracts and keep only papers that are genuinely on point.
        - Essays, long-form articles and thoughtful blog posts: use web search. Prefer substantive, reputable sources (established publications, universities, respected writers) over listicles, SEO content, product pages and forums.
        - Aim for 3 to 8 items in total, with a mix of kinds when good ones exist. A few strong items beat a long list. If nothing good exists, save an empty list.
        - Never invent a source, title, author or URL. Only save items that appeared in your search results, with their exact URL.
        - For each item, write a short note on what it says that relates to the speaker's thought, in plain language. Describe the source; don't give advice and don't add opinions of your own.

        When you're done, call save_references exactly once.
        """.trimIndent()

    fun userMessage(transcript: String, gist: String?, categories: List<String>): String {
        var message = "What I said:\n\n$transcript"
        if (categories.isNotEmpty()) message += "\n\nTopics: ${categories.joinToString(", ")}"
        if (!gist.isNullOrEmpty()) message += "\nIn short: $gist"
        return message
    }

    val saveTool: Map<String, Any?> = mapOf(
        "name" to "save_references",
        "description" to "Save the references you chose for the speaker. Call this exactly once, after searching. Include only items that appeared in your web search or pubmed_search results, with their exact URL.",
        "strict" to true,
        "input_schema" to mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("references"),
            "properties" to mapOf(
                "references" to mapOf(
                    "type" to "array",
                    "items" to ResearchLoop.sourceSchema("One or two sentences on what this source says that relates to the speaker's thought."),
                ),
            ),
        ),
    )
}

