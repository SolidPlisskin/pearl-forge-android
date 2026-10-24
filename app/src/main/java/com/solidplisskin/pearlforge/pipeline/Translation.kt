package com.solidplisskin.pearlforge.pipeline

/** A language your voice can speak. */
data class SpeechLanguage(val code: String, val name: String)

/** The languages ElevenLabs' Multilingual v2 voice model speaks, the model Pearl Forge uses for your voice. */
object SpeechLanguages {
    val all: List<SpeechLanguage> = listOf(
        "ar" to "Arabic", "bg" to "Bulgarian", "zh" to "Chinese (Mandarin)", "hr" to "Croatian", "cs" to "Czech",
        "da" to "Danish", "nl" to "Dutch", "en" to "English", "fil" to "Filipino", "fi" to "Finnish",
        "fr" to "French", "de" to "German", "el" to "Greek", "hi" to "Hindi", "id" to "Indonesian",
        "it" to "Italian", "ja" to "Japanese", "ko" to "Korean", "ms" to "Malay", "pl" to "Polish",
        "pt" to "Portuguese", "ro" to "Romanian", "ru" to "Russian", "sk" to "Slovak", "es" to "Spanish",
        "sv" to "Swedish", "ta" to "Tamil", "tr" to "Turkish", "uk" to "Ukrainian",
    ).map { SpeechLanguage(it.first, it.second) }

    fun named(code: String?): SpeechLanguage? = all.firstOrNull { it.code == code }

    /** The request added to a pearl's instructions to have it written in another language. */
    fun request(language: SpeechLanguage): String = "Say it in ${language.name}, the way a native speaker would."
}

/** Faithful translation of text you typed, for speaking it in your voice without polishing it first. */
object TranslationPrompt {
    fun system(language: SpeechLanguage): String = """
        Translate the user's text into {{L}}. It will be read aloud in the user's own cloned voice, so it must sound like them speaking {{L}} naturally.

        - Keep the meaning exactly. Don't add, drop, soften or strengthen anything; keep any uncertainty or hedging, the first person, and the user's tone and register.
        - Prefer the phrasing a native {{L}} speaker would actually use over a word-for-word rendering.
        - Keep names of people and places as they are.
        - If the text is already in {{L}}, return it unchanged.

        Output only the translation: no quotation marks, notes, alternatives or pronunciation guides.
        """.trimIndent().replace("{{L}}", language.name)
}

/** What a recording is about: its broad topics and a one-line gist. */
data class RecordingInsights(val categories: List<String>, val gist: String)

/**
 * Sorts recordings into topics such as "Relationships" or "Work & Career", reusing the topics you
 * already have so related thoughts gather together.
 */
object TopicSorter {
    /** Topics offered to the AI as a starting vocabulary. */
    val starterTopics = listOf(
        "Relationships", "Work & Career", "Parenting & Family", "Friendship",
        "Health & Body", "Mind & Emotions", "Money", "Creativity",
        "Learning & Ideas", "Philosophy & Meaning", "Faith & Spirituality",
        "Personal Growth", "Technology", "Society & Politics",
    )

    const val MAX_TOPICS = 3

    fun system(existing: List<String>): String {
        val known = if (existing.isEmpty()) starterTopics else existing + starterTopics.filter { starter ->
            existing.none { it.equals(starter, ignoreCase = true) }
        }
        return """
            You sort a person's voice notes into broad life topics so that related thoughts can be found together later. You are given the verbatim transcript of one voice note.

            Choose 1 to {{MAX}} topics that the note is substantially about. Prefer these topics, spelled exactly as written, whenever one fits:
            {{KNOWN}}
            Only create a new topic if none of these fits; keep a new one broad, in Title Case, and at most three words.

            Also write a gist: one sentence, in the first person, saying what the speaker is thinking about. Keep their uncertainty (if they say "maybe" or "I'm not sure", so does the gist). At most 25 words.

            Reply with only this JSON and nothing else:
            {"categories": ["Topic"], "gist": "I'm wondering whether..."}
            """.trimIndent()
            .replace("{{MAX}}", MAX_TOPICS.toString())
            .replace("{{KNOWN}}", known.joinToString("\n") { "- $it" })
    }

    /** Reads the AI's reply, matching topics to the existing spelling. */
    fun parse(reply: String, existing: List<String>): RecordingInsights {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        val raw = (if (start in 0 until end) runCatching { PearlJson.parseToJsonElement(reply.substring(start, end + 1)).asObject() }.getOrNull() else null)
            ?: throw PipelineError("The topics came back in an unexpected format. Please try again.")
        val known = existing + starterTopics
        val categories = mutableListOf<String>()
        for (name in raw.arr("categories").orEmpty().mapNotNull { it.stringValue() }) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) continue
            val canonical = known.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
            if (categories.none { it.equals(canonical, ignoreCase = true) }) categories.add(canonical)
        }
        if (categories.isEmpty()) throw PipelineError("No topics came back. Please try again.")
        return RecordingInsights(categories.take(MAX_TOPICS), (raw.str("gist") ?: "").trim())
    }
}
