package com.solidplisskin.pearlforge.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.solidplisskin.pearlforge.pipeline.AIModel
import com.solidplisskin.pearlforge.pipeline.AnthropicClient
import com.solidplisskin.pearlforge.pipeline.Credential
import com.solidplisskin.pearlforge.pipeline.ElevenLabsClient
import com.solidplisskin.pearlforge.pipeline.OpenAICompatibleClient
import com.solidplisskin.pearlforge.pipeline.PipelineConfig
import com.solidplisskin.pearlforge.pipeline.PolisherChoice
import com.solidplisskin.pearlforge.pipeline.PolisherProvider
import com.solidplisskin.pearlforge.pipeline.Voice
import com.solidplisskin.pearlforge.pipeline.VoiceCredits
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Whether a saved key has been checked against its service. */
sealed interface KeyStatus {
    data object NotSet : KeyStatus
    data object Saved : KeyStatus
    data object Checking : KeyStatus
    data object Works : KeyStatus
    data class Failed(val message: String) : KeyStatus
}

/**
 * Keeps API keys encrypted on this tablet with a key held by Android's Keystore, which never
 * leaves the device. Keys are never in the app's code, never backed up, and never copied off.
 */
class CredentialStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    fun read(credential: Credential): String? = runCatching {
        val stored = prefs.getString(credential.id, null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8).ifEmpty { null }
    }.getOrNull()

    fun save(value: String, credential: Credential) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit { putString(credential.id, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
    }

    fun delete(credential: Credential) = prefs.edit { remove(credential.id) }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "pearl-forge-credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

/** The ElevenLabs key, the account's voices, the chosen voice, and the plan's credits. */
class SettingsModel(context: Context, private val store: CredentialStore) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var elevenLabsStatus by mutableStateOf(if (store.read(Credential.ELEVENLABS_KEY) == null) KeyStatus.NotSet else KeyStatus.Saved)
        private set
    var voices by mutableStateOf<List<Voice>>(emptyList())
        private set
    /** Your ElevenLabs plan's credits this month, when the key may read them. */
    var credits by mutableStateOf<VoiceCredits?>(null)
        private set
    var selectedVoiceId by mutableStateOf(prefs.getString("selectedVoiceID", "") ?: "")
        private set
    /** Remembered so the chosen voice can be named before the voice list has loaded. */
    var selectedVoiceName by mutableStateOf(prefs.getString("selectedVoiceName", "") ?: "")
        private set

    fun selectVoice(voice: Voice) {
        selectedVoiceId = voice.id
        selectedVoiceName = voice.name
        prefs.edit { putString("selectedVoiceID", voice.id); putString("selectedVoiceName", voice.name) }
    }

    /** Checks the saved key if it hasn't been checked yet this launch. */
    suspend fun checkSavedKeys() {
        if (elevenLabsStatus == KeyStatus.Saved) loadVoices()
    }

    suspend fun saveElevenLabsKey(key: String) {
        store.save(key, Credential.ELEVENLABS_KEY)
        elevenLabsStatus = KeyStatus.Saved
        voices = emptyList()
        loadVoices()
    }

    fun removeElevenLabsKey() {
        store.delete(Credential.ELEVENLABS_KEY)
        elevenLabsStatus = KeyStatus.NotSet
        voices = emptyList()
        credits = null
    }

    /** Loading the voice list doubles as the ElevenLabs key check. */
    suspend fun loadVoices() {
        val key = store.read(Credential.ELEVENLABS_KEY) ?: run { elevenLabsStatus = KeyStatus.NotSet; return }
        elevenLabsStatus = KeyStatus.Checking
        try {
            voices = ElevenLabsClient(key).listVoices()
            elevenLabsStatus = KeyStatus.Works
            // Keep the saved choice if it still exists; otherwise start on the first voice, which is one of yours when you have any.
            val saved = voices.firstOrNull { it.id == selectedVoiceId }
            if (saved != null) selectVoice(saved) else voices.firstOrNull()?.let(::selectVoice)
        } catch (error: Exception) {
            elevenLabsStatus = KeyStatus.Failed(error.message ?: "The key didn't work.")
        }
    }

    /** Reads how many credits your plan has left; quietly unknown if the key can't read your account. */
    suspend fun refreshCredits() {
        val key = store.read(Credential.ELEVENLABS_KEY) ?: run { credits = null; return }
        credits = runCatching { ElevenLabsClient(key).credits() }.getOrNull()
    }
}

/**
 * The AI tab's state: each service's key and models, the model chosen for each, and which service
 * polishes by default. Keys are encrypted; the choices aren't secret, so they're plain settings.
 */
class PolishersModel(context: Context, private val store: CredentialStore) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    companion object {
        /** Find reading uses Claude Sonnet 5.5 unless you choose otherwise: it costs about half as much per search as Opus 5.5. */
        const val DEFAULT_READING_MODEL = "claude-sonnet-5-5"
    }

    val status = mutableStateMapOf<PolisherProvider, KeyStatus>()
    val models = mutableStateMapOf<PolisherProvider, List<AIModel>>()
    val selectedModels = mutableStateMapOf<PolisherProvider, String>()
    private val selectedModelNames = mutableStateMapOf<PolisherProvider, String>()

    /** The Claude model that finds reading for your recordings, chosen separately from the polishing model. */
    var readingModel by mutableStateOf(prefs.getString("reading.model", null) ?: DEFAULT_READING_MODEL)
        private set
    private var readingModelName by mutableStateOf(prefs.getString("reading.modelName", null)
        ?: if (readingModel == DEFAULT_READING_MODEL) "Claude Sonnet 5.5" else null)

    /** The service that polishes new recordings and new pearls by default. */
    var active by mutableStateOf(PolisherProvider.from(prefs.getString("polisher", null)) ?: PolisherProvider.ANTHROPIC)
        private set

    init {
        for (provider in PolisherProvider.entries) {
            status[provider] = if (store.read(provider.credential) == null) KeyStatus.NotSet else KeyStatus.Saved
            (prefs.getString("polisher.model.${provider.raw}", null) ?: provider.defaultModel)?.let { selectedModels[provider] = it }
            (prefs.getString("polisher.modelName.${provider.raw}", null) ?: if (provider == PolisherProvider.ANTHROPIC) "Claude Opus 5.5" else null)
                ?.let { selectedModelNames[provider] = it }
        }
    }

    fun polishWith(provider: PolisherProvider) {
        active = provider
        prefs.edit { putString("polisher", provider.raw) }
    }

    /** Has a key and a model, so it can polish. */
    fun isReady(provider: PolisherProvider) = status[provider] != KeyStatus.NotSet && selectedModels[provider] != null

    fun choice(provider: PolisherProvider): PolisherChoice? =
        if (isReady(provider)) selectedModels[provider]?.let { PolisherChoice(provider, it) } else null

    /** The default polisher, if it's set up. */
    val activeChoice: PolisherChoice? get() = choice(active)

    val readyProviders: List<PolisherProvider> get() = PolisherProvider.entries.filter(::isReady)

    fun selectModel(id: String, name: String?, provider: PolisherProvider) {
        selectedModels[provider] = id
        if (name != null) selectedModelNames[provider] = name else selectedModelNames.remove(provider)
        prefs.edit { putString("polisher.model.${provider.raw}", id); putString("polisher.modelName.${provider.raw}", name) }
    }

    fun selectReadingModel(id: String, name: String?) {
        readingModel = id
        readingModelName = name
        prefs.edit { putString("reading.model", id); putString("reading.modelName", name) }
    }

    /** "Claude Sonnet 5.5": the Find reading model's name. */
    val readingModelLabel: String
        get() = models[PolisherProvider.ANTHROPIC]?.firstOrNull { it.id == readingModel }?.name ?: readingModelName ?: readingModel

    /** "Claude Opus 5.5" for a known model, else the model id itself. */
    fun modelName(id: String, provider: PolisherProvider): String =
        models[provider]?.firstOrNull { it.id == id }?.name
            ?: selectedModelNames[provider]?.takeIf { selectedModels[provider] == id }
            ?: knownNames[id]
            ?: id

    /** Names for Pearl Forge's usual Claude models, for before the model list has loaded. */
    private val knownNames = mapOf("claude-opus-5-5" to "Claude Opus 5.5", "claude-sonnet-5-5" to "Claude Sonnet 5.5")

    /** "Claude · Claude Opus 5.5" */
    fun label(choice: PolisherChoice) = "${choice.provider.displayName} · ${modelName(choice.model, choice.provider)}"

    fun workspaceId(): String = store.read(Credential.ANTHROPIC_WORKSPACE_ID) ?: ""

    /** Checks any saved key that hasn't been checked yet this launch. */
    suspend fun checkSavedKeys() = coroutineScope {
        PolisherProvider.entries.filter { status[it] == KeyStatus.Saved }.map { async { check(it) } }.awaitAll()
    }

    /** Saves a newly typed key (null keeps the current one) and, for Claude, the optional workspace id; then checks it. */
    suspend fun saveKey(key: String?, workspaceId: String, provider: PolisherProvider) {
        if (key != null) store.save(key, provider.credential)
        if (provider == PolisherProvider.ANTHROPIC) {
            if (workspaceId.isEmpty()) store.delete(Credential.ANTHROPIC_WORKSPACE_ID) else store.save(workspaceId, Credential.ANTHROPIC_WORKSPACE_ID)
        }
        status[provider] = KeyStatus.Saved
        models.remove(provider)
        check(provider)
    }

    fun removeKey(provider: PolisherProvider) {
        store.delete(provider.credential)
        status[provider] = KeyStatus.NotSet
        models.remove(provider)
        if (active == provider) readyProviders.firstOrNull()?.let(::polishWith)
    }

    /** Checks the key and loads the service's models. */
    suspend fun check(provider: PolisherProvider) {
        val key = store.read(provider.credential) ?: run { status[provider] = KeyStatus.NotSet; return }
        status[provider] = KeyStatus.Checking
        try {
            val list = when (provider) {
                PolisherProvider.ANTHROPIC -> AnthropicClient(key, store.read(Credential.ANTHROPIC_WORKSPACE_ID)).listModels()
                else -> {
                    val client = OpenAICompatibleClient(provider, key)
                    if (provider == PolisherProvider.OPENROUTER) client.checkKey()
                    client.listModels()
                }
            }
            models[provider] = list
            status[provider] = KeyStatus.Works
            if (provider == PolisherProvider.ANTHROPIC) {
                list.firstOrNull { it.id == readingModel }?.let { selectReadingModel(it.id, it.name) }
            }
            val selected = selectedModels[provider]
            val listed = list.firstOrNull { it.id == selected }
            if (listed != null) selectModel(listed.id, listed.name, provider)
            else if (selected == null) suggestedModel(provider, list)?.let { selectModel(it.id, it.name, provider) }
        } catch (error: Exception) {
            status[provider] = KeyStatus.Failed(error.message ?: "The key didn't work.")
        }
    }

    /** A sensible first pick until you choose: the newest full-size model. OpenRouter offers too many to guess. */
    private fun suggestedModel(provider: PolisherProvider, list: List<AIModel>): AIModel? = when (provider) {
        PolisherProvider.ANTHROPIC -> list.firstOrNull { it.id == PipelineConfig.REFINEMENT_MODEL } ?: list.firstOrNull()
        PolisherProvider.OPENAI -> list.firstOrNull { model -> listOf("mini", "nano", "preview").none { model.id.contains(it) } } ?: list.firstOrNull()
        PolisherProvider.GEMINI -> list.firstOrNull { it.id.contains("pro") && !it.id.contains("preview") } ?: list.firstOrNull { it.id.contains("gemini") }
        PolisherProvider.OPENROUTER -> null
    }
}
