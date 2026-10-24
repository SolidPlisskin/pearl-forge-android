package com.solidplisskin.pearlforge.pipeline

import com.solidplisskin.pearlforge.recordings.Reference
import kotlin.math.abs

/** The lecture lengths on offer, and what each one means in words, ElevenLabs credits and searches. */
object LectureLength {
    val options = listOf(3, 5, 10, 15, 20)
    const val STANDARD = 10

    /** Spoken English runs at about 150 words a minute. */
    fun words(minutes: Int) = minutes * 150

    /** The longest a lecture should run: 10% over. */
    fun maxWords(minutes: Int) = words(minutes) * 11 / 10

    /**
     * ElevenLabs credits for speaking it, one per character. Lectures run about six characters a word
     * (counting spaces and punctuation), and in live tests Claude wrote up to its 10% allowance, so
     * about 1,000 a minute.
     */
    fun characters(minutes: Int) = minutes * 1_000

    /** How many web searches Claude may run. Longer lectures cover more ground; PubMed searches are free and come on top. */
    fun webSearches(minutes: Int) = when {
        minutes < 5 -> 4
        minutes < 10 -> 5
        minutes < 15 -> 7
        minutes < 20 -> 8
        else -> 10
    }

    /** The offered length closest to the one asked for. */
    fun nearest(minutes: Int) = options.minByOrNull { abs(it - minutes) } ?: STANDARD
}

/** "Give a lecture on the science of sleep", spotted in what you said or typed. */
data class LectureRequest(val topic: String, val minutes: Int?) {
    companion object {
        private const val LEAD =
            """^\s*(?:(?:hey|hi|ok|okay|so|um|uh|er|erm|well|alright|all\s+right|right|now)[\s,]+)*""" +
                """(?:pearl(?:\s+forge)?[\s,:]+)?(?:please[\s,]+)?"""

        private const val LENGTH_WORDS = "one|two|three|four|five|six|seven|eight|nine|ten|twelve|fifteen|twenty|thirty"

        private val pattern = Regex(
            LEAD +
                """(?:""" +
                """(?:""" +
                """(?:(?:can|could|would|will)\s+you\s+(?:please\s+)?)?""" +
                """(?:give|do|make|write|prepare|create|deliver|record|play|read)\s+(?:(?:me|us)\s+)?""" +
                """|i\s*(?:want|need|would\s+like|['’]?d\s+like)\s+(?:to\s+hear\s+)?""" +
                """)""" +
                """(?:a|an|the)?\s*""" +
                """(?:(?<length>\d{1,2}|$LENGTH_WORDS)[\s-]*min(?:ute)?s?(?:[\s-]+long)?\s+""" +
                """|(?<size>short|quick|brief|little|long|longer|full|detailed|in[\s-]depth|deep)\s+)?""" +
                """lecture\b""" +
                """(?:[\s,:;\-–—]+(?:on|about|regarding|covering|concerning|explaining|into|of|for)\b)?""" +
                """[\s,:;\-–—]*(?<topic>\S.*?)""" +
                """|lecture\s+(?:me|us)\s+(?:on|about)\s+(?<topic2>\S.*?)""" +
                """)""" +
                """[\s.!?…]*$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        private val trailingLength = Regex(
            """[\s,;\-–—]+(?:(?:for|lasting|taking|that['’]?s|that\s+is|of)\s+)?(?:(?:about|around|roughly|maybe)\s+)?""" +
                """(?<length>\d{1,2}|$LENGTH_WORDS)[\s-]*min(?:ute)?s?(?:\s+long)?$""",
            RegexOption.IGNORE_CASE,
        )

        private val prepositions = setOf("on", "about", "regarding", "covering", "concerning", "explaining", "into", "of", "for")

        /**
         * The request in `text`, when the text starts by asking for a lecture: "Give a lecture on…",
         * "Can you give me a 5-minute lecture about…", "Lecture me on…". A thought that merely
         * mentions a lecture isn't one.
         */
        fun parse(input: String): LectureRequest? {
            val text = input.trim()
            val match = pattern.find(text) ?: return null
            var topic = match.groups["topic"]?.value ?: match.groups["topic2"]?.value ?: ""
            var minutes = match.groups["length"]?.value?.let(::number) ?: match.groups["size"]?.value?.let(::sizeMinutes)

            // "…on jazz for about ten minutes": the length said at the end.
            trailingLength.find(topic)?.let { tail ->
                number(tail.groups["length"]?.value ?: "")?.let { value ->
                    minutes = value
                    topic = topic.removeRange(tail.range)
                }
            }
            topic = topic.replace(Regex("""^(?:(?:uh|um|er|erm|like|so)\b[\s,]*)+""", RegexOption.IGNORE_CASE), "")
                .trim { it.isWhitespace() || it in " ,;:-–—.!?…\"“”" }
            // "Give a lecture on" with nothing after it isn't a request yet.
            if (topic.length < 2 || topic.lowercase() in prepositions) return null
            return LectureRequest(topic, minutes)
        }

        private fun number(text: String): Int? {
            val words = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
                              "nine" to 9, "ten" to 10, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30)
            return (text.toIntOrNull() ?: words[text.lowercase()])?.takeIf { it > 0 }
        }

        private fun sizeMinutes(size: String) = if (size.lowercase() in setOf("short", "quick", "brief", "little")) 5 else 20
    }
}

/** Instructions for researching and writing a lecture. */
object LecturePrompt {
    fun system(minutes: Int, language: SpeechLanguage?): String {
        val languageRule = language?.let {
            "- Write the lecture in ${it.name}, the way a native speaker would, even when your sources are in other languages."
        } ?: "- Write the lecture in the language the request is in."
        return """
            You are Pearl Forge's lecture writer. Someone asked for a lecture on a topic. Research it, then write the lecture. It will be read aloud in their own cloned voice, so it should sound like them giving a clear, engaging talk.

            Research first, as much as you need to get the substance right:
            - Use pubmed_search when the topic touches on anything researchers study: health, medicine, the body, the mind, emotions, relationships, parenting, behaviour, learning, sleep or nutrition. Read the abstracts, and give more weight to systematic reviews, meta-analyses and large studies than to single small ones.
            - Use web search for background, history, context and the current state of understanding. Prefer universities, established publications, government and professional bodies, and respected writers over SEO content, product pages and forums.

            Then write the lecture and call write_lecture exactly once.

            The lecture:
            - About {{WORDS}} words, which is about {{MINUTES}} minutes spoken, and no more than {{MAX}} words.
            - Written for the ear: plain, natural spoken sentences. No headings, lists, bullet points, tables, emoji, markdown, URLs or citation markers. Write numbers, dates and units the way they're said aloud.
            - Shaped like a good talk: open with why the topic matters, say briefly where you're going, take the main ideas in a sensible order with concrete examples, and close with a short recap of the key points.
            - Accurate and honest: every factual claim must come from your research. Say plainly where evidence is mixed, early or disputed, and never overstate certainty. Name sources in passing where it helps ("a 2019 review of 30 studies found…", "according to the CDC…"). Never invent studies, numbers, quotes or sources.
            - Spoken as the person giving the talk, in the first person where it's natural, but never invent experiences, credentials or stories for them.
            - Educational rather than personal advice. For health, legal or money topics, explain what's known and mention once that individual situations differ.
            {{LANGUAGE}}

            In write_lecture, list the sources the lecture actually draws on, using only items from your search results, with their exact URLs.
            """.trimIndent()
            .replace("{{WORDS}}", LectureLength.words(minutes).toString())
            .replace("{{MINUTES}}", minutes.toString())
            .replace("{{MAX}}", LectureLength.maxWords(minutes).toString())
            .replace("{{LANGUAGE}}", languageRule)
    }

    fun user(topic: String) = "Give a lecture on: $topic"

    val writeTool: Map<String, Any?> = mapOf(
        "name" to "write_lecture",
        "description" to "Hand over the finished lecture. Call this exactly once, after researching.",
        "strict" to true,
        "input_schema" to mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("title", "summary", "script", "sources"),
            "properties" to mapOf(
                "title" to mapOf("type" to "string", "description" to "A short title for the lecture, at most eight words."),
                "summary" to mapOf("type" to "string", "description" to "One sentence on what the lecture covers."),
                "script" to mapOf("type" to "string", "description" to "The whole lecture exactly as it should be spoken, with paragraphs separated by blank lines."),
                "sources" to mapOf("type" to "array", "items" to ResearchLoop.sourceSchema("One sentence on what the lecture uses from this source.")),
            ),
        ),
    )
}

/** A researched lecture, ready to speak. */
data class WrittenLecture(
    val title: String,
    val summary: String,
    val script: String,
    /** The sources it draws on, each one checked against the search results. */
    val references: List<Reference>,
    /** Sources Claude named that couldn't be matched to a search result. */
    val unverified: Int,
    val usage: ClaudeUsage,
    /** The model that wrote it. */
    val model: String,
) {
    /** What Claude charged, from the usage it reported; null for a model whose prices Pearl Forge doesn't know. */
    val cost: Double? get() = ClaudePrice.byModel[model]?.cost(usage)
}

/** Researches a topic with Claude (the web and PubMed) and writes a lecture about it, with its sources. */
object LectureWriter {
    suspend fun write(client: AnthropicClient, model: String, topic: String, minutes: Int, language: SpeechLanguage?,
                      onProgress: (suspend (ResearchProgress) -> Unit)? = null): WrittenLecture {
        val loop = ResearchLoop(client, model, LecturePrompt.system(minutes, language), LecturePrompt.writeTool,
                                maxWebSearches = LectureLength.webSearches(minutes), maxSteps = 16,
                                failure = "The lecture failed",
                                nudge = "Please write the lecture now and call write_lecture with it.",
                                onProgress = onProgress)
        val outcome = try {
            loop.run(LecturePrompt.user(topic))
        } catch (stopped: ResearchLoop.Stopped) {
            throw PipelineError(stopped.message(
                refusal = "Claude declined to research this topic.",
                cutOff = "The lecture ran longer than Claude can write in one go. Try a shorter length.",
                unfinished = "Claude stopped before writing the lecture. Please try again.",
                tooManySteps = "The research took too many steps. Please try again.",
            ))
        }
        fun field(key: String) = (outcome.input.str(key) ?: "").trim()
        val script = forSpeaking(field("script"))
        if (script.length < 200) throw PipelineError("The lecture came back empty. Please try again.")
        val checked = ResearchLoop.verify(outcome.input.arr("sources").orEmpty().mapNotNull { it.asObject() }, outcome.seenUrls, outcome.papers)
        val title = field("title")
        return WrittenLecture(title.ifEmpty { topic.replaceFirstChar { it.uppercase() } }, field("summary"), script,
                              checked.references, checked.unverified, outcome.usage, outcome.answeredBy)
    }

    /** Drops any markdown that slipped in (headings, bold), which would otherwise be read out or trip up the voice. */
    fun forSpeaking(script: String): String = script
        .replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")
        .replace("**", "")
        .replace("__", "")
        .trim()
}
