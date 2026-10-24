package com.solidplisskin.pearlforge.pipeline

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject

/** What Claude calls used, as the API reports it, for working out what they cost. */
data class ClaudeUsage(
    var input: Int = 0,
    var cacheWrite: Int = 0,
    var cacheRead: Int = 0,
    var output: Int = 0,
    var webSearches: Int = 0,
) {
    /** Adds one response's "usage". */
    fun add(usage: JsonObject?) {
        if (usage == null) return
        input += usage.int("input_tokens") ?: 0
        cacheWrite += usage.int("cache_creation_input_tokens") ?: 0
        cacheRead += usage.int("cache_read_input_tokens") ?: 0
        output += usage.int("output_tokens") ?: 0
        webSearches += usage.obj("server_tool_use")?.int("web_search_requests") ?: 0
    }
}

/** Anthropic's list prices in dollars per million tokens (platform.claude.com/docs/en/about-claude/pricing, October 2026). */
data class ClaudePrice(
    val input: Double,
    /** Writing to the 5-minute prompt cache. */
    val cacheWrite: Double,
    val cacheRead: Double,
    val output: Double,
) {
    fun cost(usage: ClaudeUsage): Double {
        val tokens = usage.input * input + usage.cacheWrite * cacheWrite + usage.cacheRead * cacheRead + usage.output * output
        return tokens / 1_000_000 + usage.webSearches * PER_WEB_SEARCH
    }

    companion object {
        /** Web search is $10 per 1,000 searches, on top of the tokens it reads. */
        const val PER_WEB_SEARCH = 0.01

        val byModel = mapOf(
            "claude-opus-5-5" to ClaudePrice(4.0, 5.0, 0.20, 20.0),
            "claude-sonnet-5-5" to ClaudePrice(2.0, 2.50, 0.10, 10.0),
            "claude-fable-5-1" to ClaudePrice(10.0, 12.50, 0.25, 50.0),
        )
    }
}

/** What a lecture should cost, worked out before anything is spent. */
data class LectureEstimate(
    /** Claude's research and writing. A range, because how much it reads varies from topic to topic. */
    val claudeLow: Double,
    val claudeHigh: Double,
    /** The middle of the range before past lectures adjusted it; saved with the lecture so later estimates learn. */
    val claudeBase: Double,
    /** ElevenLabs credits for speaking it: one per character. */
    val credits: Int,
    /** What those credits are worth on your plan. */
    val voiceDollars: Double?,
    /** How many of your past lectures adjusted the Claude estimate. */
    val learnedFrom: Int,
) {
    val totalLow: Double get() = claudeLow + (voiceDollars ?: 0.0)
    val totalHigh: Double get() = claudeHigh + (voiceDollars ?: 0.0)

    companion object {
        /**
         * Typical Claude usage for a lecture of a given length, from live runs with Claude Sonnet 5.5
         * (October 2026): a 5-minute lecture researched on PubMed alone used 11,000 tokens written to
         * the cache, 12,000 read back and 3,600 out ($0.07); a 10-minute one with 5 web searches used
         * 43,500, 31,000 and 6,100 ($0.22). Each web search adds what it read; the writing (and the
         * thinking behind it) grows with the length.
         */
        fun typicalUsage(minutes: Int): ClaudeUsage {
            val searches = LectureLength.webSearches(minutes) * 0.6
            val scriptTokens = LectureLength.words(minutes) * 1.4
            return ClaudeUsage(
                input = 50,
                cacheWrite = (7_000 + searches * 6_500 + scriptTokens * 1.5).toInt(),
                cacheRead = (9_000 + searches * 4_500).toInt(),
                output = (1_000 + scriptTokens * 2.4).toInt(),
                webSearches = searches.roundToInt(),
            )
        }

        /**
         * The estimate for a lecture. `pastRatios` are past lectures' actual Claude cost over their
         * unadjusted estimate, newest first; they nudge this estimate toward what your lectures really cost.
         */
        fun make(minutes: Int, model: String, credits: VoiceCredits?, pastRatios: List<Double>): LectureEstimate? {
            val price = ClaudePrice.byModel[model] ?: return null
            val base = price.cost(typicalUsage(minutes))
            val recent = pastRatios.take(5).sorted()
            val factor = if (recent.isEmpty()) 1.0 else recent[recent.size / 2].coerceIn(0.25, 4.0)
            val middle = base * factor
            // Wide until a few real lectures have shown how far off it runs.
            val (low, high) = if (recent.size >= 2) 0.75 to 1.3 else 0.5 to 1.5
            val characters = LectureLength.characters(minutes)
            val rate = if (credits != null) credits.dollarsPerThousand else VoiceCredits.dollarsPerThousand(null)
            return LectureEstimate(middle * low, middle * high, base, characters, rate?.let { characters / 1000.0 * it }, recent.size)
        }
    }
}

/** "$0.27", "$1.60", or "under 1¢". */
val Double.dollars: String
    get() = if (this < 0.01) "under 1¢" else NumberFormat.getCurrencyInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(this)

/** "$0.20–0.45", or a single amount when both ends round the same. */
fun dollarRange(low: Double, high: Double): String {
    val lowText = low.dollars
    val highText = high.dollars
    if (lowText == highText) return lowText
    return lowText + "–" + highText.replace("$", "")
}
