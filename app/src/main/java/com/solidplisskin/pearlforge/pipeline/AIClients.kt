package com.solidplisskin.pearlforge.pipeline

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val jsonType = "application/json".toMediaType()

/** Anthropic calls: the model list and one-shot completions (polish, translate, sort topics). */
class AnthropicClient(
    private val apiKey: String,
    /** Sent as "anthropic-workspace-id" when the key is organization-scoped. */
    private val workspaceId: String?,
    val base: String = PipelineConfig.ANTHROPIC_BASE,
) {
    companion object {
        /** Models whose safety checks can hand a declined request to Anthropic's recommended fallback model. */
        val fallbackModels = setOf(
            "claude-fable-5-1", "claude-fable-5", "claude-mythos-5-1",
            "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5",
        )

        /** Models that think only when asked; newer ones think by default. */
        val adaptiveThinkingModels = setOf("claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-sonnet-4-6")
    }

    /** Your account's Claude models, newest first. Free, so it doubles as the key check. */
    suspend fun listModels(): List<AIModel> {
        val request = headers(Request.Builder().url("$base/models?limit=1000")).build()
        val json = parseJson(PipelineHttp.send(request, "Your Anthropic key didn't work")).asObject()
        return json?.arr("data").orEmpty().mapNotNull { it.asObject() }.mapNotNull { item ->
            val id = item.str("id") ?: return@mapNotNull null
            AIModel(id, item.str("display_name") ?: id, item.str("created_at")?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() })
        }.sortedByDescending { it.created ?: 0 }
    }

    /** Improves how clearly the transcript is expressed without changing what it says. */
    suspend fun refine(transcript: String, system: String, model: String): Refinement {
        val reply = complete(system, transcript, model, "Refinement failed")
        if (reply.stopReason == "refusal") {
            throw PipelineError("Claude declined to refine this recording. Try again, or try a different mode.")
        }
        // A cut-off refinement would silently drop the end of your thought.
        if (reply.stopReason == "max_tokens") {
            throw PipelineError("The refined version was cut off before the end. The recording may be too long for one pass; try Summarize or a shorter recording.")
        }
        if (reply.text.isEmpty()) throw PipelineError("The refinement came back empty. Please try again.")
        return Refinement(reply.text, PolisherProvider.ANTHROPIC, reply.model)
    }

    /** One system prompt and one user message; returns the visible reply. */
    suspend fun complete(system: String, user: String, model: String, failure: String = "Claude request failed"): Completion {
        val usesFallback = model in fallbackModels
        val body = mutableMapOf<String, Any?>(
            "model" to model,
            "max_tokens" to PipelineConfig.REFINEMENT_MAX_TOKENS,
            "system" to system,
            "messages" to listOf(mapOf("role" to "user", "content" to user)),
        )
        // If a safety check declines the request, Anthropic re-runs it on its recommended fallback model.
        if (usesFallback) body["fallbacks"] = "default"
        // Current models think by default (which helps with the meaning-preservation judgement);
        // older ones are asked for it. Only the visible text is read back out.
        if (model in adaptiveThinkingModels) body["thinking"] = mapOf("type" to "adaptive")
        val response = send(body, failure, usesFallback)
        val text = response.arr("content").orEmpty().mapNotNull { it.asObject() }
            .filter { it.str("type") == "text" }.mapNotNull { it.str("text") }.joinToString("").trim()
        return Completion(text, response.str("model") ?: model, response.str("stop_reason"))
    }

    /** Posts a Messages request built from plain values and returns the reply object. */
    suspend fun send(body: Map<String, Any?>, failure: String, usesFallback: Boolean) =
        sendJson(jsonOf(body), failure, usesFallback)

    suspend fun sendJson(body: JsonElement, failure: String, usesFallback: Boolean): kotlinx.serialization.json.JsonObject {
        val builder = headers(Request.Builder().url("$base/messages"))
        if (usesFallback) builder.header("anthropic-beta", "server-side-fallback-2026-07-01")
        val data = PipelineHttp.send(builder.post(body.toString().toRequestBody(jsonType)).build(), failure)
        return runCatching { parseJson(data).asObject() }.getOrNull()
            ?: throw PipelineError("$failure: the reply couldn't be read.")
    }

    private fun headers(builder: Request.Builder): Request.Builder {
        builder.header("x-api-key", apiKey).header("anthropic-version", PipelineConfig.ANTHROPIC_VERSION)
        if (!workspaceId.isNullOrEmpty()) builder.header("anthropic-workspace-id", workspaceId)
        return builder
    }
}

/** Calls to services that speak OpenAI's chat format: OpenAI itself, Google Gemini, and OpenRouter. */
class OpenAICompatibleClient(
    private val provider: PolisherProvider,
    private val apiKey: String,
    private val base: String = provider.compatibleBase,
) {
    /** The service's chat models, newest first. */
    suspend fun listModels(): List<AIModel> {
        val request = headers(Request.Builder().url("$base/models")).build()
        val json = parseJson(PipelineHttp.send(request, "Your ${provider.displayName} key didn't work")).asObject()
        return json?.arr("data").orEmpty().mapNotNull { it.asObject() }.mapNotNull { item ->
            val raw = item.str("id") ?: return@mapNotNull null
            // Gemini lists ids as "models/gemini-…".
            val id = raw.removePrefix("models/")
            val outputs = item.obj("architecture")?.arr("output_modalities")?.mapNotNull { it.stringValue() }
            if (!isChatModel(id, outputs)) return@mapNotNull null
            AIModel(id, item.str("name") ?: id, item.double("created")?.toLong())
        }.sortedWith { lhs, rhs ->
            val l = lhs.created
            val r = rhs.created
            if (l != null && r != null && l != r) r.compareTo(l) else rhs.id.compareTo(lhs.id, ignoreCase = true)
        }
    }

    /** Confirms the key works. OpenRouter lists its models without a key, so its key is checked separately. */
    suspend fun checkKey() {
        if (provider != PolisherProvider.OPENROUTER) {
            listModels()
            return
        }
        PipelineHttp.send(headers(Request.Builder().url("$base/key")).build(), "Your OpenRouter key didn't work")
    }

    /** Leaves out models that can't write text replies (images, speech, embeddings, …). */
    private fun isChatModel(id: String, outputs: List<String>?): Boolean {
        val lower = id.lowercase()
        return when (provider) {
            PolisherProvider.OPENAI -> {
                val families = listOf("gpt-", "chatgpt-", "o1", "o3", "o4", "o5")
                val excluded = listOf(
                    "audio", "realtime", "tts", "transcribe", "image", "search", "embedding",
                    "instruct", "moderation", "codex", "dall-e", "whisper", "computer-use",
                    "deep-research", "babbage", "davinci",
                )
                val datedSnapshot = Regex("""-\d{4}-\d{2}-\d{2}$""").containsMatchIn(lower)
                families.any { lower.startsWith(it) } && excluded.none { lower.contains(it) } && !datedSnapshot
            }
            PolisherProvider.GEMINI -> {
                val excluded = listOf("embedding", "image", "tts", "live", "audio", "veo", "imagen", "aqa")
                (lower.contains("gemini") || lower.contains("gemma")) && excluded.none { lower.contains(it) }
            }
            PolisherProvider.OPENROUTER -> outputs?.contains("text") ?: true
            PolisherProvider.ANTHROPIC -> true
        }
    }

    /** Sends the polishing instructions and transcript, and returns the reply. */
    suspend fun refine(transcript: String, system: String, model: String): Refinement {
        val reply = complete(system, transcript, model, "Polishing with ${provider.displayName} failed")
        if (reply.stopReason == "refusal") {
            throw PipelineError("${provider.displayName} declined to polish this recording. Try again, or try a different AI.")
        }
        // A cut-off refinement would silently drop the end of your thought.
        if (reply.stopReason == "length") {
            throw PipelineError("The polished version was cut off before the end. The recording may be too long for this model; try Summarize, a shorter recording, or a different model.")
        }
        if (reply.text.isEmpty()) throw PipelineError("The polished version came back empty. Please try again.")
        return Refinement(reply.text, provider, reply.model)
    }

    /** One system message and one user message. A refusal is reported as the stop reason "refusal". */
    suspend fun complete(system: String, user: String, model: String, failure: String? = null): Completion {
        val label = failure ?: "${provider.displayName} request failed"
        // No length limit is sent: each model's own maximum applies, so a long recording isn't cut short.
        val body = jsonOf(mapOf("model" to model, "messages" to listOf(
            mapOf("role" to "system", "content" to system),
            mapOf("role" to "user", "content" to user),
        )))
        val request = headers(Request.Builder().url("$base/chat/completions"))
            .post(body.toString().toRequestBody(jsonType)).build()
        val response = parseJson(PipelineHttp.send(request, label)).asObject()
            ?: throw PipelineError("$label: the reply couldn't be read.")
        val choice = response.arr("choices")?.firstOrNull()?.asObject()
            ?: throw PipelineError("$label: ${response.obj("error")?.str("message") ?: "no reply came back."}")
        val message = choice.obj("message")
        val finish = choice.str("finish_reason")
        val refused = !message?.str("refusal").isNullOrEmpty() || finish == "content_filter"
        return Completion(stripThinking(flexibleText(message?.get("content"))), response.str("model") ?: model, if (refused) "refusal" else finish)
    }

    /** Message text that arrives either as a plain string or as a list of parts. */
    private fun flexibleText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> if (content.isString) content.content else ""
        is JsonArray -> content.mapNotNull { it.asObject()?.str("text") }.joinToString("")
        else -> ""
    }

    private fun headers(builder: Request.Builder): Request.Builder {
        builder.header("Authorization", "Bearer $apiKey")
        // Labels the usage in your OpenRouter activity list.
        if (provider == PolisherProvider.OPENROUTER) builder.header("X-Title", "Pearl Forge")
        return builder
    }
}
