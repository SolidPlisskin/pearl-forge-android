package com.solidplisskin.pearlforge.pipeline

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Endpoints, models and limits for Pearl Forge's cloud calls, the same as the iPhone app's. */
object PipelineConfig {
    // ElevenLabs: speech-to-text, text-to-speech, and the voice list.
    const val ELEVENLABS_BASE = "https://api.elevenlabs.io/v1"
    const val TRANSCRIPTION_MODEL = "scribe_v1"
    const val SPEECH_MODEL = "eleven_multilingual_v2"

    // Anthropic: the refinement step.
    const val ANTHROPIC_BASE = "https://api.anthropic.com/v1"
    const val ANTHROPIC_VERSION = "2023-06-01"
    const val REFINEMENT_MODEL = "claude-opus-5-5"
    const val REFINEMENT_MAX_TOKENS = 16000

    /** Long text is spoken in chunks, each told its neighbours so the delivery stays continuous. */
    const val SPEECH_CHUNK_CHARACTERS = 2500

    /** Long recordings and lectures take a while. */
    const val REQUEST_TIMEOUT_SECONDS = 900L
}

/** A failure in one of the cloud calls, worded for the person using the app. */
class PipelineError(message: String) : Exception(message)

/** Shared request plumbing for every service. */
object PipelineHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(PipelineConfig.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(PipelineConfig.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(PipelineConfig.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Sends the request and returns the body. HTTP errors become a PipelineError like
     * "Could not list your voices (401): Invalid API key".
     */
    suspend fun send(request: Request, failure: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.bytes() ?: ByteArray(0)
                if (response.code >= 400) {
                    throw PipelineError("$failure (${response.code}): ${readableMessage(body)}")
                }
                body
            }
        } catch (error: IOException) {
            throw PipelineError("$failure: couldn't reach the service (${error.message ?: "no connection"}). Check your internet connection.")
        }
    }

    /** Pulls the human-readable message out of an API error body, falling back to the start of the raw body. */
    fun readableMessage(data: ByteArray): String {
        val text = data.decodeToString()
        var parsed = runCatching { PearlJson.parseToJsonElement(text) }.getOrNull()
        // Gemini sometimes wraps its error in a one-item list.
        if (parsed is JsonArray && parsed.isNotEmpty()) parsed = parsed[0]
        if (parsed is JsonObject) {
            // Anthropic and OpenAI: {"error": {"message": "..."}}
            parsed.obj("error")?.str("message")?.let { return it }
            // ElevenLabs: {"detail": {"message": "..."}} or {"detail": "..."}
            parsed.obj("detail")?.str("message")?.let { return it }
            parsed.str("detail")?.let { return it }
        }
        return text.take(500)
    }
}

/** The JSON settings used everywhere. */
val PearlJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

fun parseJson(data: ByteArray): JsonElement = PearlJson.parseToJsonElement(data.decodeToString())

// Small helpers for reading loosely typed JSON replies.
fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
fun JsonElement?.asArray(): JsonArray? = this as? JsonArray
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
fun JsonElement.stringValue(): String? = (this as? JsonPrimitive)?.contentOrNull

/** Builds JSON from plain Kotlin values: maps, lists, strings, numbers, booleans and nulls. */
fun jsonOf(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to jsonOf(v) })
    is List<*> -> JsonArray(value.map { jsonOf(it) })
    else -> JsonPrimitive(value.toString())
}

/**
 * JSON written the way the iPhone app writes meta.json: keys sorted, two-space indents and
 * `"key" : value`, so the files stay easy to compare across both apps.
 */
fun prettyJson(element: JsonElement, indent: String = ""): String {
    val inner = "$indent  "
    return when (element) {
        is JsonObject -> if (element.isEmpty()) "{\n\n$indent}" else element.entries.sortedBy { it.key }
            .joinToString(",\n", "{\n", "\n$indent}") { (key, value) -> "$inner${JsonPrimitive(key)} : ${prettyJson(value, inner)}" }
        is JsonArray -> if (element.isEmpty()) "[\n\n$indent]" else element
            .joinToString(",\n", "[\n", "\n$indent]") { "$inner${prettyJson(it, inner)}" }
        else -> element.toString()
    }
}
