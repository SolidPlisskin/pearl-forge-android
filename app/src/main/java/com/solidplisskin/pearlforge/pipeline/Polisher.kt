package com.solidplisskin.pearlforge.pipeline

/** The credentials Pearl Forge keeps, each stored encrypted on this device. */
enum class Credential(val id: String) {
    ELEVENLABS_KEY("elevenlabs-api-key"),
    ANTHROPIC_KEY("anthropic-api-key"),
    /** Only needed when the Anthropic key is organization-scoped. */
    ANTHROPIC_WORKSPACE_ID("anthropic-workspace-id"),
    OPENAI_KEY("openai-api-key"),
    GEMINI_KEY("gemini-api-key"),
    OPENROUTER_KEY("openrouter-api-key"),
}

/** The AI services that can polish a transcript. */
enum class PolisherProvider(val raw: String) {
    ANTHROPIC("anthropic"),
    OPENAI("openai"),
    GEMINI("gemini"),
    OPENROUTER("openrouter");

    val displayName: String
        get() = when (this) {
            ANTHROPIC -> "Claude"
            OPENAI -> "ChatGPT"
            GEMINI -> "Gemini"
            OPENROUTER -> "OpenRouter"
        }

    val company: String
        get() = when (this) {
            ANTHROPIC -> "Anthropic"
            OPENAI -> "OpenAI"
            GEMINI -> "Google"
            OPENROUTER -> "Hundreds of models, one key"
        }

    val credential: Credential
        get() = when (this) {
            ANTHROPIC -> Credential.ANTHROPIC_KEY
            OPENAI -> Credential.OPENAI_KEY
            GEMINI -> Credential.GEMINI_KEY
            OPENROUTER -> Credential.OPENROUTER_KEY
        }

    val keysPageLabel: String
        get() = when (this) {
            ANTHROPIC -> "Get your key at console.anthropic.com"
            OPENAI -> "Get your key at platform.openai.com"
            GEMINI -> "Get your key at aistudio.google.com"
            OPENROUTER -> "Get your key at openrouter.ai"
        }

    val keysPage: String
        get() = when (this) {
            ANTHROPIC -> "https://console.anthropic.com/settings/keys"
            OPENAI -> "https://platform.openai.com/api-keys"
            GEMINI -> "https://aistudio.google.com/apikey"
            OPENROUTER -> "https://openrouter.ai/settings/keys"
        }

    /** The service's OpenAI-compatible endpoint (Claude uses its own API). */
    val compatibleBase: String
        get() = when (this) {
            ANTHROPIC -> PipelineConfig.ANTHROPIC_BASE
            OPENAI -> "https://api.openai.com/v1"
            GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai"
            OPENROUTER -> "https://openrouter.ai/api/v1"
        }

    /** The model used until you choose one. */
    val defaultModel: String? get() = if (this == ANTHROPIC) PipelineConfig.REFINEMENT_MODEL else null

    /** One line about the service for its setup page. */
    val about: String
        get() = when (this) {
            ANTHROPIC -> "Claude is what Pearl Forge was designed around. It's the default."
            OPENAI -> "OpenAI's models, billed to your OpenAI API account (separate from a ChatGPT subscription)."
            GEMINI -> "Google's models, billed to your Google AI Studio account. AI Studio has a free tier."
            OPENROUTER -> "One key and one bill for models from many companies, including Claude, GPT, Gemini, Llama, Mistral, DeepSeek and Grok."
        }

    companion object {
        fun from(raw: String?): PolisherProvider? = entries.firstOrNull { it.raw == raw }
    }
}

/** A service and one of its models: who polishes a pearl. */
data class PolisherChoice(val provider: PolisherProvider, val model: String)

/** A model a service offers. `created` is in seconds since 1970. */
data class AIModel(val id: String, val name: String, val created: Long?)

/**
 * The polished text, and which service and model actually wrote it (the model can differ from
 * the one requested when Anthropic's fallback stepped in).
 */
data class Refinement(val text: String, val provider: PolisherProvider, val model: String)

/** A plain reply from any AI service. */
data class Completion(
    val text: String,
    val model: String,
    /** Why the reply ended: "end_turn"/"stop" normally, "refusal", or "max_tokens"/"length" when cut off. */
    val stopReason: String?,
) {
    val wasCutOff: Boolean get() = stopReason == "max_tokens" || stopReason == "length"
}

/** Removes a leading <think>…</think> block some open models put before their answer. */
fun stripThinking(text: String): String {
    var result = text.trim()
    if (result.startsWith("<think>")) {
        val end = result.indexOf("</think>")
        if (end >= 0) result = result.substring(end + "</think>".length).trim()
    }
    return result
}
