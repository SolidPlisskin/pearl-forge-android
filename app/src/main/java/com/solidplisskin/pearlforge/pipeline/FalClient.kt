package com.solidplisskin.pearlforge.pipeline

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * fal.ai: avatar video models paid per second of video, with no subscription. Pearl Forge uploads
 * the avatar picture and the pearl's audio to fal's storage, queues the video, checks on it, then
 * downloads it.
 */
class FalClient(
    private val apiKey: String,
    private val queueBase: String = QUEUE_BASE,
    private val restBase: String = REST_BASE,
    private val platformBase: String = PLATFORM_BASE,
) {
    companion object {
        const val QUEUE_BASE = "https://queue.fal.run"
        const val REST_BASE = "https://rest.fal.ai"
        const val PLATFORM_BASE = "https://api.fal.ai/v1"

        /**
         * How long fal keeps what Pearl Forge sends and gets back: the uploads only need to last
         * until the video is made; the finished video waits long enough to be collected after a
         * lost connection.
         */
        const val UPLOAD_LIFETIME = 86_400
        const val VIDEO_LIFETIME = 604_800

        private const val BAD_KEY_MESSAGE = "fal.ai didn't accept your key. Check it in Settings › API keys."

        private val jsonType = "application/json".toMediaType()

        fun lifetime(seconds: Int) = "{\"expiration_duration_seconds\":$seconds}"

        fun audioContentType(fileExtension: String) = when (fileExtension) {
            "m4a", "mp4", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            else -> "audio/mpeg"
        }
    }

    /**
     * Your fal.ai balance in dollars, or null when the key may only make videos and not read billing
     * (fal's "API" key preset). Throws when fal doesn't accept the key, so this doubles as the key check.
     */
    suspend fun balance(): Double? = withContext(Dispatchers.IO) {
        val url = "$platformBase/account/billing".toHttpUrl().newBuilder().addQueryParameter("expand", "credits").build()
        val request = authorized(Request.Builder().url(url)).build()
        val (status, body) = try {
            PipelineHttp.client.newCall(request).execute().use { it.code to (it.body?.bytes() ?: ByteArray(0)) }
        } catch (error: IOException) {
            throw PipelineError("Couldn't reach fal.ai: ${error.message ?: "no connection"}. Check your internet connection.")
        }
        when (status) {
            in 200..299 -> runCatching { parseJson(body).asObject()?.obj("credits")?.double("current_balance") }.getOrNull()
            401 -> throw PipelineError(BAD_KEY_MESSAGE)
            // A working key that isn't allowed to read billing.
            403 -> null
            else -> throw PipelineError("Couldn't reach fal.ai ($status): ${PipelineHttp.readableMessage(body)}")
        }
    }

    /** Uploads the picture and audio, queues a video of the picture speaking the audio, and returns its request id for checkVideo. */
    suspend fun startVideo(model: VideoModel, picture: ByteArray, audio: ByteArray, audioExtension: String): String {
        val endpoint = model.falEndpoint ?: throw PipelineError("${model.name} isn't a fal.ai model.")
        val imageUrl = upload(picture, "image/jpeg", "avatar.jpg")
        val ext = audioExtension.lowercase()
        val audioUrl = upload(audio, audioContentType(ext), "pearl.$ext")

        val body = mutableMapOf("image_url" to imageUrl, "audio_url" to audioUrl, "prompt" to AvatarVideo.DIRECTIONS)
        body.putAll(model.falInput)
        val request = authorized(Request.Builder().url("$queueBase/$endpoint"))
            .header("X-Fal-Object-Lifecycle-Preference", lifetime(VIDEO_LIFETIME))
            .post(json(JsonObject(body.mapValues { JsonPrimitive(it.value) })))
            .build()
        val reply = PipelineHttp.send(request, "fal.ai couldn't start the video", mapOf(401 to BAD_KEY_MESSAGE))
        return read(reply)?.str("request_id")?.takeIf { it.isNotEmpty() } ?: throw PipelineError("fal.ai didn't say which video it started.")
    }

    /** Where a video fal.ai is making has got to. */
    suspend fun checkVideo(id: String, endpoint: String): VideoGeneration {
        val requestUrl = requestUrl(id, endpoint)
        val statusRequest = authorized(Request.Builder().url("$requestUrl/status")).build()
        val status = read(PipelineHttp.send(statusRequest, "Couldn't check on the video", mapOf(401 to BAD_KEY_MESSAGE)))
            ?: throw PipelineError("fal.ai's reply about the video couldn't be read.")
        if (status.str("status") != "COMPLETED") return VideoGeneration.Working
        status.str("error")?.takeIf { it.isNotEmpty() }?.let {
            throw VideoFailed("fal.ai couldn't make the video: $it Failed videos aren't charged.")
        }
        val result = read(PipelineHttp.send(authorized(Request.Builder().url(requestUrl)).build(), "Couldn't fetch the finished video"))
        val link = result?.obj("video")?.str("url")?.takeIf { it.isNotEmpty() }
            ?: throw VideoFailed("fal.ai finished but didn't send back a video.")
        return VideoGeneration.Finished(link)
    }

    /** Checks on a video until it's finished, then downloads it. */
    suspend fun waitForVideo(id: String, endpoint: String, pollMillis: Long = AvatarVideo.POLL_INTERVAL_MS): ByteArray {
        while (true) {
            val state = checkVideo(id, endpoint)
            if (state is VideoGeneration.Finished) {
                return PipelineHttp.send(Request.Builder().url(state.url).build(), "Couldn't download the video")
            }
            delay(pollMillis)
        }
    }

    // Plumbing

    /** Puts a file in fal's storage and returns the address models read it from. */
    private suspend fun upload(data: ByteArray, contentType: String, fileName: String): String {
        val url = "$restBase/storage/upload/initiate".toHttpUrl().newBuilder().addQueryParameter("storage_type", "fal-cdn-v3").build()
        val initiate = authorized(Request.Builder().url(url))
            .header("X-Fal-Object-Lifecycle", lifetime(UPLOAD_LIFETIME))
            .post(json(JsonObject(mapOf("content_type" to JsonPrimitive(contentType), "file_name" to JsonPrimitive(fileName)))))
            .build()
        val slot = read(PipelineHttp.send(initiate, "Couldn't upload to fal.ai", mapOf(401 to BAD_KEY_MESSAGE)))
        val uploadUrl = slot?.str("upload_url")
        val fileUrl = slot?.str("file_url")
        if (uploadUrl == null || fileUrl == null || runCatching { uploadUrl.toHttpUrl() }.isFailure) {
            throw PipelineError("fal.ai gave an upload address Pearl Forge couldn't read.")
        }
        val put = Request.Builder().url(uploadUrl).put(data.toRequestBody(contentType.toMediaType())).build()
        PipelineHttp.send(put, "Couldn't upload to fal.ai")
        return fileUrl
    }

    /** A JSON object reply, or null when fal sent something else. */
    private fun read(data: ByteArray): JsonObject? = runCatching { parseJson(data).asObject() }.getOrNull()

    private fun authorized(builder: Request.Builder) = builder.header("Authorization", "Key $apiKey")

    /** JSON sent as plain "application/json", as the iPhone app sends it. */
    private fun json(body: JsonObject) = body.toString().toByteArray().toRequestBody(jsonType)

    /** A queued request's address. fal files requests under the owner and app only ("fal-ai/kling-video"), not the model's full path. */
    private fun requestUrl(id: String, endpoint: String): String {
        val app = endpoint.split("/").take(2).joinToString("/")
        return "$queueBase/$app/requests/$id"
    }
}
