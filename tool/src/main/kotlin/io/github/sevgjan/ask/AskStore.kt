package io.github.sevgjan.ask

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FormItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** Questions allowed per day, counted from local midnight. */
object DailyLimit {
    const val UNLIMITED = 0

    fun remaining(limit: Int, usedOn: Long, used: Int, today: Long): Int =
        if (limit == UNLIMITED) Int.MAX_VALUE else (limit - if (usedOn == today) used else 0).coerceAtLeast(0)

    fun label(limit: Int) = if (limit == UNLIMITED) "∞" else limit.toString()
}

/** The question and answer kept by Ask 0.1, which had no chats; moved into one chat once. */
data class LegacyAnswer(val question: String, val answer: String, val followUp: String?, val followUpAnswer: String?)

/** Ask's settings and keys, in the tool's DataStore. Keys are encrypted (see [Secrets]). */
class AskStore(private val context: SealedLightContext) {
    private val secrets = Secrets(context.dataStore)
    private val data = context.dataStore.data

    fun apiKey(provider: Provider): Flow<String?> = secrets.observe(secretName(provider)).distinctUntilChanged()
    suspend fun setApiKey(provider: Provider, value: String?) = secrets.set(secretName(provider), value)
    suspend fun hasKey(provider: Provider) = apiKey(provider).first() != null

    /** The model a new chat starts with. Before chats, Ask kept a provider and a model per provider. */
    val newChatModel: Flow<Model> = data.map { p ->
        Model.of(p[NEW_CHAT_MODEL]) ?: when (p[OLD_PROVIDER]) {
            Provider.OPENAI.name -> Model.of(p[OLD_OPENAI_MODEL])?.takeIf { it.provider == Provider.OPENAI } ?: Model.ASTRA
            else -> Model.of(p[OLD_MODEL])?.takeIf { it.provider == Provider.ANTHROPIC } ?: Model.OPUS
        }
    }.distinctUntilChanged()
    suspend fun setNewChatModel(value: Model) = context.dataStore.edit { it[NEW_CHAT_MODEL] = value.name }

    val depth: Flow<Depth> = data.map { p -> Depth.entries.firstOrNull { it.name == p[DEPTH] } ?: Depth.QUICK }
    suspend fun setDepth(value: Depth) = context.dataStore.edit { it[DEPTH] = value.name }

    val webSearch: Flow<Boolean> = data.map { it[WEB_SEARCH] ?: true }
    suspend fun setWebSearch(on: Boolean) = context.dataStore.edit { it[WEB_SEARCH] = on }

    val imageModel: Flow<ImageModel> = data.map { p -> ImageModel.entries.firstOrNull { it.name == p[IMAGE_MODEL] } ?: ImageModel.FLARE }
    suspend fun setImageModel(value: ImageModel) = context.dataStore.edit { it[IMAGE_MODEL] = value.name }

    val limit: Flow<Int> = data.map { it[LIMIT] ?: DailyLimit.UNLIMITED }
    suspend fun setLimit(value: Int) = context.dataStore.edit { it[LIMIT] = value }
    val remainingToday: Flow<Int> = data.map { p ->
        DailyLimit.remaining(p[LIMIT] ?: DailyLimit.UNLIMITED, p[USED_ON] ?: 0, p[USED] ?: 0, LocalDate.now().toEpochDay())
    }

    /** Uses one question from today's allowance; false when none are left. */
    suspend fun useQuestion(): Boolean {
        if (remainingToday.first() <= 0) return false
        val today = LocalDate.now().toEpochDay()
        context.dataStore.edit { p ->
            val used = if (p[USED_ON] == today) p[USED] ?: 0 else 0
            p[USED_ON] = today
            p[USED] = used + 1
        }
        return true
    }

    /** The keyboard's switches, read once at start into [KeyboardPrefs]. */
    val keyboard: Flow<Preferences> = data
    suspend fun setKeyboard(name: String, on: Boolean) = context.dataStore.edit { it[booleanPreferencesKey("keyboard_$name")] = on }
    suspend fun setRecentEmoji(list: List<String>) = context.dataStore.edit { it[RECENT_EMOJI] = list.joinToString(" ") }

    /** Ask 0.1's last question, once: it's removed as it's read. */
    suspend fun takeLegacy(): LegacyAnswer? {
        var found: LegacyAnswer? = null
        context.dataStore.edit { p ->
            val q = p[LEGACY_Q]
            if (q != null) found = LegacyAnswer(q, p[LEGACY_A].orEmpty(), p[LEGACY_FOLLOW_Q], p[LEGACY_FOLLOW_A])
            listOf(LEGACY_Q, LEGACY_A, LEGACY_FOLLOW_Q, LEGACY_FOLLOW_A).forEach { p.remove(it) }
        }
        return found
    }

    /** Keys sent from a computer (the Tool Manager's form, or the same form over USB). Returns the providers that got one. */
    suspend fun applySetupForm(): List<Provider> {
        val values = Setup.consume(context.fileShare) ?: return emptyList()
        val given = Provider.entries.filter { values[secretName(it)] != null }
        given.forEach { setApiKey(it, values.getValue(secretName(it))) }
        // A key for only one provider, and none for the other: new chats should use the one that works.
        val only = given.singleOrNull()
        if (only != null && !hasKey(Provider.entries.first { it != only }) && newChatModel.first().provider != only) {
            setNewChatModel(only.models.first())
        }
        return given
    }

    companion object {
        const val API_KEY = "api_key"
        const val OPENAI_API_KEY = "openai_api_key"
        private val NEW_CHAT_MODEL = stringPreferencesKey("new_chat_model")
        private val OLD_PROVIDER = stringPreferencesKey("provider")
        private val OLD_MODEL = stringPreferencesKey("model")
        private val OLD_OPENAI_MODEL = stringPreferencesKey("model_openai")
        private val DEPTH = stringPreferencesKey("depth")
        private val WEB_SEARCH = booleanPreferencesKey("web_search")
        private val IMAGE_MODEL = stringPreferencesKey("image_model")
        private val LIMIT = intPreferencesKey("daily_limit")
        private val USED_ON = longPreferencesKey("used_on")
        private val USED = intPreferencesKey("used")
        val RECENT_EMOJI = stringPreferencesKey("recent_emoji")
        private val LEGACY_Q = stringPreferencesKey("last_question")
        private val LEGACY_A = stringPreferencesKey("last_answer")
        private val LEGACY_FOLLOW_Q = stringPreferencesKey("last_follow_up")
        private val LEGACY_FOLLOW_A = stringPreferencesKey("last_follow_up_answer")

        fun secretName(provider: Provider) = if (provider == Provider.OPENAI) OPENAI_API_KEY else API_KEY
    }
}

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override fun getToolManagerManifest(): ClientToolManifest = Setup.manifest(
        title = "Ask",
        header = "Paste an API key from console.anthropic.com or platform.openai.com, or both. The OpenAI key also makes " +
            "pictures and turns speech into text. Keys are encrypted on the phone and this form's file is deleted.",
        items = Provider.entries.map { FormItem(id = AskStore.secretName(it), label = it.keyLabel, hidden = true) },
    )

    override suspend fun onToolManagerDataUpdate() {
        Setup.updates.value = System.currentTimeMillis()
    }
}
