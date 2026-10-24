package com.solidplisskin.pearlforge.pipeline

import com.solidplisskin.pearlforge.recordings.RecordingEntry
import java.io.File

/** The steps a voice note goes through, for progress messages. */
enum class ForgeStep(val progressLabel: String) {
    TRANSCRIBING("Transcribing…"),
    REFINING("Refining (keeping your meaning)…"),
    TRANSLATING("Translating (keeping your meaning)…"),
    RESEARCHING("Researching and writing your lecture…"),
    SPEAKING("Speaking it back in your voice…"),
}

/** The cloud calls, using the keys saved on this device. */
object Forge {
    /** Reads a saved key; set when the app starts. */
    var credentials: (Credential) -> String? = { null }

    suspend fun transcribe(audio: File): String = elevenLabs().transcribe(audio)

    suspend fun speak(text: String, voiceId: String): ByteArray = elevenLabs().synthesize(text, voiceId)

    /** Polishes the transcript with the chosen AI, using the mode's instructions plus any notes you gave. */
    suspend fun refine(transcript: String, mode: RefinementMode, notes: String?, choice: PolisherChoice): Refinement {
        if (transcript.isEmpty()) {
            throw PipelineError("The transcript for this recording couldn't be loaded. Pull down on History to refresh, then try again.")
        }
        val system = RefinementPrompts.system(mode, notes)
        return when (choice.provider) {
            PolisherProvider.ANTHROPIC -> anthropic(choice.provider).refine(transcript, system, choice.model)
            else -> compatible(choice.provider).refine(transcript, system, choice.model)
        }
    }

    /** Translates text into a language with the chosen AI, keeping its meaning. */
    suspend fun translate(text: String, language: SpeechLanguage, choice: PolisherChoice): Refinement {
        val reply = complete(TranslationPrompt.system(language), text, choice, "Translation failed")
        if (reply.stopReason == "refusal") throw PipelineError("${choice.provider.displayName} declined to translate this.")
        if (reply.wasCutOff) throw PipelineError("The translation was cut off before the end. Try a shorter text.")
        if (reply.text.isEmpty()) throw PipelineError("The translation came back empty. Please try again.")
        return Refinement(reply.text, choice.provider, reply.model)
    }

    /** Sorts a transcript into topics using the chosen AI. */
    suspend fun sortIntoTopics(transcript: String, existing: List<String>, choice: PolisherChoice): RecordingInsights {
        if (transcript.isEmpty()) throw PipelineError("This recording's transcript couldn't be loaded.")
        val reply = complete(TopicSorter.system(existing), transcript, choice, "Sorting into topics failed")
        if (reply.stopReason == "refusal") throw PipelineError("${choice.provider.displayName} declined to sort this recording.")
        return TopicSorter.parse(reply.text, existing)
    }

    /** Finds reading related to a recording, using the Claude model chosen in the AI tab. */
    suspend fun findReferences(entry: RecordingEntry, claudeModel: String): VerifiedSources {
        val key = credentials(Credential.ANTHROPIC_KEY)
            ?: throw PipelineError("Finding references uses Claude's web search. Add your Claude key in the AI tab first.")
        if (entry.transcript.isEmpty()) throw PipelineError("This recording's transcript couldn't be loaded.")
        return ReferenceFinder.find(AnthropicClient(key, credentials(Credential.ANTHROPIC_WORKSPACE_ID)), claudeModel,
                                    entry.transcript, entry.meta.gist, entry.meta.categories.orEmpty())
    }

    /** Researches and writes a lecture with the given Claude model. */
    suspend fun writeLecture(topic: String, minutes: Int, language: SpeechLanguage?, model: String,
                             onProgress: (suspend (ResearchProgress) -> Unit)? = null): WrittenLecture {
        val key = credentials(Credential.ANTHROPIC_KEY)
            ?: throw PipelineError("Lectures are researched with Claude's web search. Add your Claude key in the AI tab first.")
        return LectureWriter.write(AnthropicClient(key, credentials(Credential.ANTHROPIC_WORKSPACE_ID)), model, topic, minutes, language, onProgress)
    }

    private suspend fun complete(system: String, user: String, choice: PolisherChoice, failure: String): Completion =
        when (choice.provider) {
            PolisherProvider.ANTHROPIC -> anthropic(choice.provider).complete(system, user, choice.model, failure)
            else -> compatible(choice.provider).complete(system, user, choice.model, failure)
        }

    private fun anthropic(provider: PolisherProvider): AnthropicClient {
        val key = credentials(provider.credential) ?: throw PipelineError("Add your ${provider.displayName} key in the AI tab first.")
        return AnthropicClient(key, credentials(Credential.ANTHROPIC_WORKSPACE_ID))
    }

    private fun compatible(provider: PolisherProvider): OpenAICompatibleClient {
        val key = credentials(provider.credential) ?: throw PipelineError("Add your ${provider.displayName} key in the AI tab first.")
        return OpenAICompatibleClient(provider, key)
    }

    private fun elevenLabs(): ElevenLabsClient {
        val key = credentials(Credential.ELEVENLABS_KEY) ?: throw PipelineError("Add your ElevenLabs key in Settings first.")
        return ElevenLabsClient(key)
    }
}
