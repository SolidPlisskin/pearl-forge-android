package com.solidplisskin.pearlforge.pipeline

import java.io.File
import java.util.Date
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** A voice on the ElevenLabs account. */
data class Voice(val id: String, val name: String, val category: String?) {
    /** Voices the account owner made (clones and designed voices), as opposed to ElevenLabs' stock library. */
    val isOwn: Boolean get() = category in setOf("cloned", "professional", "generated")
}

/** Your ElevenLabs plan's credits. Speaking with Multilingual v2 uses one credit per character. */
data class VoiceCredits(val tier: String, val used: Int, val limit: Int, val resets: Date?) {
    val remaining: Int get() = maxOf(0, limit - used)

    /**
     * Roughly what 1,000 credits are worth on this plan: its monthly price over its monthly credits
     * (elevenlabs.io/pricing, October 2026). Null on the free plan, where credits cost nothing.
     */
    val dollarsPerThousand: Double? get() = dollarsPerThousand(tier)

    companion object {
        fun dollarsPerThousand(tier: String?): Double? {
            val name = tier?.lowercase() ?: ""
            if (name.startsWith("free")) return null
            if (name.startsWith("starter")) return 0.20                                            // $6 for 30,000
            if (listOf("pro", "scale", "business").any { name.startsWith(it) }) return 0.165       // $99 for 600,000
            return 0.18                                                                            // Creator: $22 for 121,000
        }
    }
}

/** ElevenLabs calls: the voice list, credits, speech-to-text (Scribe), and text-to-speech in your voice. */
class ElevenLabsClient(private val apiKey: String, private val base: String = PipelineConfig.ELEVENLABS_BASE) {

    /** The account's voices, its own voices first, then alphabetical. */
    suspend fun listVoices(): List<Voice> {
        val request = Request.Builder().url("$base/voices").header("xi-api-key", apiKey).build()
        val json = parseJson(PipelineHttp.send(request, "Could not list your voices")).asObject()
        return json?.arr("voices").orEmpty().mapNotNull { it.asObject() }.mapNotNull { raw ->
            val id = raw.str("voice_id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Voice(id, raw.str("name") ?: "(unnamed voice)", raw.str("category"))
        }.sortedWith(compareBy<Voice>({ !it.isOwn }, { it.name.lowercase() }))
    }

    /** Your plan and its credits this month. Needs a key that's allowed to read your account. */
    suspend fun credits(): VoiceCredits {
        val request = Request.Builder().url("$base/user/subscription").header("xi-api-key", apiKey).build()
        val json = parseJson(PipelineHttp.send(request, "Could not read your ElevenLabs credits")).asObject()
        val used = json?.int("character_count")
        val limit = json?.int("character_limit")
        if (used == null || limit == null) throw PipelineError("ElevenLabs didn't say how many credits you have.")
        return VoiceCredits(json.str("tier") ?: "", used, limit, json.double("next_character_count_reset_unix")?.let { Date((it * 1000).toLong()) })
    }

    /** A faithful transcript of the recording. */
    suspend fun transcribe(file: File): String {
        val mimeType = when (file.extension.lowercase()) {
            "m4a", "mp4", "aac" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "webm" -> "audio/webm"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model_id", PipelineConfig.TRANSCRIPTION_MODEL)
            // Keep the transcript plain: no "(laughter)" style event tags, and no speaker
            // diarisation (this is one person thinking aloud).
            .addFormDataPart("tag_audio_events", "false")
            .addFormDataPart("diarize", "false")
            .addFormDataPart("file", file.name, file.asRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder().url("$base/speech-to-text").header("xi-api-key", apiKey).post(body).build()
        val text = (parseJson(PipelineHttp.send(request, "Transcription failed")).asObject()?.str("text") ?: "").trim()
        if (text.isEmpty()) throw PipelineError("The transcription came back empty. Is the recording audible?")
        return text
    }

    /** Speech for `text` in the given voice, as MP3 data. Long text is spoken in chunks, each told its neighbours. */
    suspend fun synthesize(text: String, voiceId: String): ByteArray {
        if (voiceId.isEmpty()) throw PipelineError("No voice is selected. Pick your ElevenLabs voice in Settings first.")
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw PipelineError("There is no text to turn into speech.")
        val chunks = splitForSpeech(trimmed)
        val audio = java.io.ByteArrayOutputStream()
        chunks.forEachIndexed { index, chunk ->
            val fields = mutableMapOf("text" to JsonPrimitive(chunk), "model_id" to JsonPrimitive(PipelineConfig.SPEECH_MODEL))
            // Pass neighbouring text so prosody stays continuous across chunks.
            if (index > 0) fields["previous_text"] = JsonPrimitive(chunks[index - 1])
            if (index < chunks.size - 1) fields["next_text"] = JsonPrimitive(chunks[index + 1])
            val request = Request.Builder().url("$base/text-to-speech/$voiceId")
                .header("xi-api-key", apiKey).header("Accept", "audio/mpeg")
                .post(JsonObject(fields).toString().toRequestBody("application/json".toMediaType()))
                .build()
            audio.write(PipelineHttp.send(request, "Voice generation failed"))
        }
        return audio.toByteArray()
    }

    companion object {
        /** Splits long text into chunks on paragraph, then sentence, boundaries (the Pinokio build's _split_for_tts). */
        fun splitForSpeech(text: String, limit: Int = PipelineConfig.SPEECH_CHUNK_CHARACTERS): List<String> {
            val all = text.trim()
            if (all.length <= limit) return listOf(all)
            val chunks = mutableListOf<String>()
            var buffer = ""
            for (piece in Regex("""\n\s*\n""").split(all)) {
                val sentences = Regex("""(?<=[.!?])\s+""").split(piece.trim())
                for (sentence in sentences) {
                    if (sentence.isEmpty()) continue
                    if (buffer.length + sentence.length + 1 > limit && buffer.isNotEmpty()) {
                        chunks.add(buffer.trim())
                        buffer = ""
                    }
                    buffer = "$buffer $sentence".trim()
                }
                if (buffer.isNotEmpty()) {
                    chunks.add(buffer.trim())
                    buffer = ""
                }
            }
            val nonEmpty = chunks.filter { it.isNotEmpty() }
            return nonEmpty.ifEmpty { listOf(all) }
        }
    }
}
