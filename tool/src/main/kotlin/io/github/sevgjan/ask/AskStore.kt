package io.github.sevgjan.ask

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

data class LastAnswer(val question: String, val answer: String)

/** Questions allowed per day, counted from local midnight. */
object DailyLimit {
    fun remaining(limit: Int, usedOn: Long, used: Int, today: Long): Int = (limit - if (usedOn == today) used else 0).coerceAtLeast(0)
}

class AskStore(private val context: SealedLightContext) {
    private val secrets = Secrets(context.dataStore)
    private val data = context.dataStore.data

    val apiKey: Flow<String?> = secrets.observe(API_KEY)
    val baseUrl: Flow<String> = data.map { it[BASE_URL] ?: ClaudeApi.DEFAULT_BASE_URL }
    val model: Flow<ClaudeModel> = data.map { p -> ClaudeModel.entries.firstOrNull { it.name == p[MODEL] } ?: ClaudeModel.OPUS }
    val limit: Flow<Int> = data.map { it[LIMIT] ?: 5 }
    val remainingToday: Flow<Int> = data.map { p ->
        DailyLimit.remaining(p[LIMIT] ?: 5, p[USED_ON] ?: 0, p[USED] ?: 0, LocalDate.now().toEpochDay())
    }
    val last: Flow<LastAnswer?> = data.map { p ->
        val q = p[LAST_Q] ?: return@map null
        LastAnswer(q, p[LAST_A].orEmpty())
    }

    suspend fun setApiKey(value: String) = secrets.set(API_KEY, value)
    suspend fun setBaseUrl(value: String) = context.dataStore.edit {
        val url = value.trim().trimEnd('/')
        if (url.isEmpty()) it.remove(BASE_URL) else it[BASE_URL] = url
    }
    suspend fun setModel(value: ClaudeModel) = context.dataStore.edit { it[MODEL] = value.name }
    suspend fun setLimit(value: Int) = context.dataStore.edit { it[LIMIT] = value }

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

    suspend fun saveLast(question: String, answer: String) = context.dataStore.edit {
        it[LAST_Q] = question
        it[LAST_A] = answer
    }

    suspend fun applySetupForm() {
        val values = Setup.consume(context.fileShare) ?: return
        values[API_KEY]?.let { setApiKey(it) }
        values[BASE_URL_FIELD]?.let { setBaseUrl(it) }
    }

    companion object {
        const val API_KEY = "api_key"
        const val BASE_URL_FIELD = "base_url"
        private val BASE_URL = stringPreferencesKey("base_url")
        private val MODEL = stringPreferencesKey("model")
        private val LIMIT = intPreferencesKey("daily_limit")
        private val USED_ON = longPreferencesKey("used_on")
        private val USED = intPreferencesKey("used")
        private val LAST_Q = stringPreferencesKey("last_question")
        private val LAST_A = stringPreferencesKey("last_answer")
    }
}

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override fun getToolManagerManifest(): ClientToolManifest = Setup.manifest(
        title = "Ask",
        header = "Paste an Anthropic API key from console.anthropic.com. It is encrypted on the phone and this form's file is deleted. " +
            "Questions you ask are sent to Anthropic.",
        items = listOf(
            FormItem(id = AskStore.API_KEY, label = "Anthropic API key", hidden = true, required = true),
            FormItem(id = AskStore.BASE_URL_FIELD, label = "API address (optional, leave blank for api.anthropic.com)"),
        ),
    )

    override suspend fun onToolManagerDataUpdate() {
        Setup.updates.value = System.currentTimeMillis()
    }
}
