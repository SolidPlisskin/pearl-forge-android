// Pearl Forge's refinement instructions, carried over word for word from the iPhone app (and from
// there the working Pinokio build: pearl-ui/pipeline.py SYSTEM_BASE and MODE_INSTRUCTIONS). The
// wording is the product — change it only on purpose. RefinementPromptsTest checks it against the
// iPhone app's own output.

package com.solidplisskin.pearlforge.pipeline

/** How much refinement is applied. Raw values match the iPhone and Pinokio builds' mode keys. */
enum class RefinementMode(val raw: String) {
    CLEAN("clean"),
    TIGHTEN("tighten"),
    SUMMARIZE("summarize"),
    /** Typed text spoken exactly as written (or only translated), not polished. */
    AS_WRITTEN("aswritten"),
    /** A lecture researched and written for you; never offered as a polish. */
    LECTURE("lecture");

    val title: String
        get() = when (this) {
            CLEAN -> "Clean"
            TIGHTEN -> "Tighten"
            SUMMARIZE -> "Summarize"
            AS_WRITTEN -> "As written"
            LECTURE -> "Lecture"
        }

    /** The short description shown next to the mode name. */
    val subtitle: String
        get() = when (this) {
            CLEAN -> "light tidy-up, keeps almost everything"
            TIGHTEN -> "reorganise and de-clutter, keep the nuance"
            SUMMARIZE -> "condensed to the core, still your view"
            AS_WRITTEN -> "your exact words, not polished"
            LECTURE -> "researched and written for you"
        }

    /** The subtitle as a sentence: "Light tidy-up, keeps almost everything." */
    val subtitleSentence: String get() = subtitle.replaceFirstChar { it.uppercase() } + "."

    val instruction: String
        get() = when (this) {
            CLEAN -> RefinementPrompts.clean
            TIGHTEN -> RefinementPrompts.tighten
            SUMMARIZE -> RefinementPrompts.summarize
            AS_WRITTEN, LECTURE -> ""
        }

    companion object {
        /** The modes that polish, offered for recordings and new pearls. */
        val polishingModes = listOf(CLEAN, TIGHTEN, SUMMARIZE)

        fun from(raw: String?): RefinementMode? = entries.firstOrNull { it.raw == raw }
    }
}

object RefinementPrompts {
    /**
     * The full system prompt for one refinement request. Without notes it is exactly the Pinokio
     * build's prompt; your notes for a pearl are added after it.
     */
    fun system(mode: RefinementMode, notes: String? = null): String {
        val prompt = systemBase + "\n\n" + mode.instruction
        val trimmed = notes?.trim()
        if (trimmed.isNullOrEmpty()) return prompt
        return prompt + "\n\n" + speakerRequest(trimmed)
    }

    /**
     * What you asked to be different for one pearl. Added only when you write a request. The
     * request goes in after trimIndent, so a request with line breaks can't upset the indentation.
     */
    fun speakerRequest(notes: String): String = """
        SPEAKER'S REQUEST FOR THIS VERSION:
        {{NOTES}}

        Apply this request on top of the mode above. Everything else in the rules above still holds; depart from a rule only where the request explicitly asks you to.
        """.trimIndent().replace("{{NOTES}}", notes)

    val systemBase = """
        You are Pearl Forge. You are given the verbatim transcript of a voice note in which the speaker is thinking out loud. Your job is to improve HOW CLEARLY the thought is expressed, WITHOUT changing the thought itself. The result will be read back aloud in the speaker's own voice, in the first person, so it must sound like the speaker on an articulate day — not like a different narrator.

        These rules are absolute. Never break them, in any mode:
        - Never increase certainty. If the speaker hedges ("maybe", "I think", "I'm not sure", "probably", "it seems"), keep the hedge. A possibility must never become a flat claim.
        - Never remove qualifications, caveats, conditions, or the sense that the speaker is exploring rather than concluding.
        - Never add facts, opinions, conclusions, analogies, or examples the speaker did not say. Do not insert your own analysis or your own conclusions.
        - Keep the speaker's personality: their humour, their analogies, their unusual associations, and digressions that genuinely add to the idea. Do not flatten the writing into generic or corporate prose.
        - Keep it first person and in the speaker's own words as much as possible. Improve the expression; do not replace the voice.

        Output ONLY the refined text. No preamble, no title, no notes, no quotation marks wrapping the whole thing, no explanation of what you changed.
        """.trimIndent()

    val clean = """
        MODE: Clean.
        Make only light, mechanical edits — the way a careful transcriptionist tidies up dictation. Remove filler words ("um", "uh", "like", "you know"), false starts, stutters, and word-for-word repetition. Fix obvious grammar and broken run-on sentences. KEEP the original order of ideas, the conversational tone, and nearly all of the substance. Do NOT reorganise, condense, or rewrite in a more polished register.
        """.trimIndent()

    val tighten = """
        MODE: Tighten.
        Improve how the explanation is built. Combine sentences that make the same point, move closely related points next to each other, fix awkward ordering, and smooth the transitions so the argument flows. KEEP every important nuance and qualification, and keep the speaker's distinctive phrasing and the digressions that add value. Do not shorten by dropping ideas — only by removing waste and repetition.
        """.trimIndent()

    val summarize = """
        MODE: Summarize.
        Produce a genuinely condensed version, in the speaker's first-person voice, containing the central argument, the major supporting points, the important qualifications, and the final conclusions. This mode deliberately sacrifices detail — but it must still represent the speaker's ACTUAL position and their uncertainty, never a more confident, tidier, or more generic version of it.
        """.trimIndent()
}
