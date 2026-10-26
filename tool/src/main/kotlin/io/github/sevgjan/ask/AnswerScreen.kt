package io.github.sevgjan.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

data class Exchange(val question: String, val answer: String? = null, val note: String? = null)

/** One question and at most one follow-up. The conversation is not kept once the screen closes. */
class AnswerViewModel(
    private val store: AskStore,
    question: String,
    previous: LastAnswer?,
) : LightViewModel<Unit>() {
    val exchanges = MutableStateFlow(listOf(Exchange(question, previous?.answer)))
    val loading = MutableStateFlow(false)
    val canFollowUp = MutableStateFlow(false)
    private val messages = mutableListOf<JsonObject>()

    init {
        if (previous == null) {
            viewModelScope.launch {
                if (store.useQuestion()) send(question, isFollowUp = false) else note("No questions left today.")
            }
        }
    }

    fun followUp(text: String) {
        if (!canFollowUp.value || text.isBlank()) return
        canFollowUp.value = false
        exchanges.value = exchanges.value + Exchange(text.trim())
        viewModelScope.launch { send(text.trim(), isFollowUp = true) }
    }

    private suspend fun send(question: String, isFollowUp: Boolean) {
        val key = store.apiKey.first() ?: return note("Add your API key in settings.")
        messages += ClaudeApi.userMessage(question)
        loading.value = true
        val result = runCatching { ClaudeApi.send(key, store.baseUrl.first(), store.model.first(), messages) }
        loading.value = false
        result.fold(
            onSuccess = { reply ->
                when (reply) {
                    is Reply.Answer -> {
                        // Sent back unchanged so thinking blocks stay valid for the follow-up.
                        messages += ClaudeApi.assistantMessage(reply.assistantContent)
                        val text = reply.text.ifEmpty { "(no answer)" } + if (reply.truncated) "\n\n(cut short)" else ""
                        update { it.copy(answer = text) }
                        if (!isFollowUp) {
                            canFollowUp.value = true
                            store.saveLast(question, text)
                        }
                    }
                    is Reply.Refused -> {
                        messages.removeAt(messages.lastIndex)
                        note("Claude declined to answer this one.")
                    }
                }
            },
            onFailure = { error ->
                messages.removeAt(messages.lastIndex)
                note(if (error is ClaudeException) error.message.orEmpty() else "No connection. Try again when you're online.")
            },
        )
    }

    private fun note(text: String) = update { it.copy(note = text) }

    private fun update(change: (Exchange) -> Exchange) {
        val list = exchanges.value
        exchanges.value = list.dropLast(1) + change(list.last())
    }
}

class AnswerScreen(
    sealedActivity: SealedLightActivity,
    private val store: AskStore,
    private val question: String,
    private val previous: LastAnswer?,
) : LightScreen<Unit, AnswerViewModel>(sealedActivity) {

    override val viewModelClass: Class<AnswerViewModel>
        get() = AnswerViewModel::class.java

    override fun createViewModel() = AnswerViewModel(store, question, previous)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val exchanges by viewModel.exchanges.collectAsState()
        val loading by viewModel.loading.collectAsState()
        val canFollowUp by viewModel.canFollowUp.collectAsState()

        LightTheme(colors = themeColors) {
            Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Answer"),
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )
                LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                    exchanges.forEachIndexed { i, exchange ->
                        LightText(
                            text = exchange.question,
                            variant = LightTextVariant.Copy,
                            lighten = true,
                            modifier = Modifier.padding(top = if (i == 0) 0f.gridUnitsAsDp() else 1.5f.gridUnitsAsDp()),
                        )
                        val body = exchange.answer ?: exchange.note ?: if (loading) "Thinking…" else ""
                        LightText(
                            text = body,
                            variant = LightTextVariant.Copy,
                            lighten = exchange.answer == null,
                            modifier = Modifier.padding(top = 0.6f.gridUnitsAsDp()),
                        )
                    }
                }
                if (canFollowUp) {
                    LightBottomBar(
                        listOf(
                            LightBarButton.Text(text = "FOLLOW UP", onClick = {
                                navigateTo(screenFactory = { TextEditorScreen(it, title = "Follow-up", submitLabel = "ASK") }) {
                                    viewModel.followUp(it)
                                }
                            }),
                        ),
                    )
                }
            }
        }
    }
}
