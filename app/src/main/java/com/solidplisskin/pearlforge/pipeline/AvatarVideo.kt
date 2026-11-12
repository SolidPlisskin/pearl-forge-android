package com.solidplisskin.pearlforge.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.os.Build
import com.solidplisskin.pearlforge.recordings.Pearl
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Video pearls: a picture of your ElevenLabs avatar, lip-synced to a pearl's audio by one of the
 * models in VideoModel. ElevenLabs' own Avatars page has no API yet, so the app works from a
 * picture of the avatar instead.
 */
object AvatarVideo {
    const val MODEL = "creatify-aurora"
    const val RESOLUTION = "720p"
    /** ElevenLabs asks apps to check on a video no more than every 10 seconds. */
    const val POLL_INTERVAL_MS = 10_000L
    const val CHOSEN_MODEL_KEY = "videoModel"
    /**
     * Sent with every fal.ai video. Left to themselves the models invent gestures, such as a
     * floating hand waving in from the bottom of a picture cut off at the chest.
     */
    const val DIRECTIONS = "A person speaking directly to the camera with a calm, natural expression and subtle head movement. Hands and arms stay still and out of view. Steady framing, no camera movement."

    const val NEEDS_PRO_MESSAGE = "Making videos from the app needs ElevenLabs Pro or higher. On your plan, make the video in ElevenLabs Avatars instead, then use Add video."

    /** Whether your plan lets apps make videos: false below Pro, null when the plan isn't known (the attempt then tells you). */
    fun planAllowsApp(tier: String?): Boolean? {
        val name = tier?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return listOf("free", "starter", "creator").none { name.startsWith(it) }
    }

    // The avatar picture

    fun pictureFile(folder: File) = File(folder, "AvatarPicture.jpg")

    fun picture(folder: File): ByteArray? = runCatching { pictureFile(folder).readBytes() }.getOrNull()

    /** Saves a picture of your avatar as a JPEG no bigger than 1280 pixels on its longer side, plenty for a 720p video. */
    fun savePicture(data: ByteArray, folder: File) {
        val image = runCatching {
            // ImageDecoder turns photos the right way up; the older decoder doesn't know how.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else BitmapFactory.decodeByteArray(data, 0, data.size)
        }.getOrNull() ?: throw PipelineError("That picture couldn't be opened. Try saving it again as a photo.")
        val longest = max(image.width, image.height)
        val scale = min(1.0, 1280.0 / max(longest, 1))
        val resized = if (scale < 1) {
            Bitmap.createScaledBitmap(image, (image.width * scale).roundToInt(), (image.height * scale).roundToInt(), true)
        } else image
        val jpeg = ByteArrayOutputStream().also {
            if (!resized.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw PipelineError("That picture couldn't be saved.")
        }.toByteArray()
        folder.mkdirs()
        val temporary = File(folder, "AvatarPicture.jpg.saving")
        temporary.writeBytes(jpeg)
        if (!temporary.renameTo(pictureFile(folder))) throw PipelineError("That picture couldn't be saved.")
    }

    fun removePicture(folder: File) {
        pictureFile(folder).delete()
    }

    // Cost

    /** How long the pearl's audio plays for. */
    fun seconds(audio: File): Double? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audio.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000)
        } finally {
            retriever.release()
        }
    }.getOrNull()?.takeIf { it > 0 }

    /** Credits per second of speech, from the videos Pearl Forge made before (the middle of the last five), or null before the first one. */
    fun creditsPerSecond(pearls: List<Pearl>): Double? {
        val rates = pearls
            .filter { it.videoGenerationId == null }
            .sortedByDescending { it.created }
            .mapNotNull { pearl ->
                val credits = pearl.videoCredits ?: return@mapNotNull null
                val seconds = pearl.videoSeconds?.takeIf { it > 0 } ?: return@mapNotNull null
                credits / seconds
            }
            .take(5)
            .sorted()
        return if (rates.isEmpty()) null else rates[rates.size / 2]
    }
}

/**
 * What animates the avatar picture: ElevenLabs (Pro plans and up), or one of the avatar models on
 * fal.ai, paid per second of video with no subscription.
 */
data class VideoModel(
    /** Saved as the choice in Settings. */
    val id: String,
    val name: String,
    /** What it's good for, under its name in the picker. */
    val strength: String,
    /** fal.ai's model, or null for ElevenLabs. */
    val falEndpoint: String?,
    /** Extra settings sent with the picture and audio. */
    val falInput: Map<String, String> = emptyMap(),
    /** fal.ai's list price per second of video, as of November 2026. */
    val dollarsPerSecond: Double? = null,
    /** Creatify bills whole seconds: a 9.4-second video costs as much as 10. */
    val billsWholeSeconds: Boolean = false,
    /** The longest pearl the model can take, in seconds. */
    val maxSeconds: Double? = null,
) {
    val isFal: Boolean get() = falEndpoint != null

    /** What a video of this many seconds of speech should cost, in dollars. */
    fun cost(seconds: Double): Double? = dollarsPerSecond?.let { (if (billsWholeSeconds) ceil(seconds) else seconds) * it }

    /** "About $3.37 a minute", or what ElevenLabs needs. */
    val priceLine: String
        get() = cost(60.0)?.let { "About ${it.dollars} a minute" } ?: "Needs ElevenLabs Pro or higher"

    companion object {
        val ELEVENLABS = VideoModel("elevenlabs", "ElevenLabs (Creatify Aurora)", "Uses your ElevenLabs plan's credits", null)

        val FAL: List<VideoModel> = listOf(
            VideoModel("kling-standard", "Kling Avatar v2 Standard", "Everyday pearls, the cheapest",
                       "fal-ai/kling-video/ai-avatar/v2/standard", dollarsPerSecond = 0.0562),
            VideoModel("kling-pro", "Kling Avatar v2 Pro", "Sharper Kling",
                       "fal-ai/kling-video/ai-avatar/v2/pro", dollarsPerSecond = 0.115),
            VideoModel("aurora-480p", "Creatify Aurora, 480p", "The model ElevenLabs uses, smaller picture",
                       "fal-ai/creatify/aurora", mapOf("resolution" to "480p"), dollarsPerSecond = 0.07, billsWholeSeconds = true),
            VideoModel("aurora-720p", "Creatify Aurora, 720p", "The model ElevenLabs uses",
                       "fal-ai/creatify/aurora", mapOf("resolution" to "720p"), dollarsPerSecond = 0.14, billsWholeSeconds = true),
            VideoModel("omnihuman", "OmniHuman 1.5", "Most lifelike gestures; pearls up to 1 minute",
                       "fal-ai/bytedance/omnihuman/v1.5", mapOf("resolution" to "720p"), dollarsPerSecond = 0.16, maxSeconds = 60.0),
        )

        val ALL: List<VideoModel> = FAL + ELEVENLABS
        val STANDARD: VideoModel = FAL[0]

        fun withId(id: String?): VideoModel = ALL.firstOrNull { it.id == id } ?: STANDARD

        /** The name of the fal.ai model a video was made with. */
        fun nameOfEndpoint(endpoint: String): String {
            val name = FAL.firstOrNull { it.falEndpoint == endpoint }?.name ?: endpoint
            // Both Aurora sizes share one model.
            return name.split(",").first()
        }
    }
}

/** ElevenLabs or fal.ai gave up on a video. Unlike a lost connection, there's nothing left to collect. */
class VideoFailed(message: String) : Exception(message)

/** A video ElevenLabs or fal.ai is making: still going, or finished with a download link. */
sealed interface VideoGeneration {
    data object Working : VideoGeneration
    data class Finished(val url: String) : VideoGeneration
}
