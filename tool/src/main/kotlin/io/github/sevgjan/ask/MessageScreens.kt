package io.github.sevgjan.ask

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.textentry.LightTextEditResult
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import io.github.sevgjan.ask.engine.Gallery
import io.github.sevgjan.ask.engine.Links
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** What you can do with one message: copy it, ask again, change a question, save a picture, open a source. */
class MessageScreen(sealedActivity: SealedLightActivity, private val message: Message) : SimpleLightScreen<String>(sealedActivity) {
    private val work = MainScope()

    override fun onScreenDestroy() = work.cancel()

    @Composable
    override fun Content() {
        val clipboard = LocalClipboardManager.current
        val m = message
        val isLast by produceState(false, m.id) { value = Ask.db.messagesNow(m.chatId).lastOrNull()?.id == m.id }
        val lastQuestion by produceState(false, m.id) { value = Ask.db.messagesNow(m.chatId).lastOrNull { it.isUser }?.id == m.id }
        val busy = Chats.busy(m.chatId)
        Page(title = if (m.isUser) "Your question" else Model.of(m.model)?.label ?: "Answer", subtitle = shortTime(m.created), left = back { goBack() }) {
            val text = if (m.isUser) m.text else Markdown.plain(m.text)
            if (text.isNotBlank()) LightText(text = text, variant = LightTextVariant.Copy, maxLines = 6)
            Section("Actions")
            if (text.isNotBlank()) ActionRow("Copy text") {
                clipboard.setText(AnnotatedString(text))
                Ask.notice.value = "Copied. Hold the question field to paste."
                goBack()
            }
            if (!m.isUser && isLast && !busy) ActionRow("Ask again", "A new answer in place of this one") { goBack(RETRY) }
            if (m.isUser && lastQuestion && !busy) ActionRow("Change and ask again", "Puts it back in the field") { goBack(EDIT) }
            m.imageList.forEachIndexed { i, name ->
                val label = if (m.imageList.size > 1) " ${i + 1}" else ""
                ActionRow("View picture$label") { navigateTo({ ViewerScreen(it, m.chatId, name) }) }
                ActionRow("Save picture$label to the phone", "Pictures/Ask, where the LightOS camera's photos are") {
                    work.launch {
                        val ok = Gallery.save(Pictures.file(name), Pictures.mediaType(name), "Ask-$name")
                        Ask.notice.value = if (ok) "Saved to Pictures/Ask." else "Couldn't save it."
                    }
                }
            }
            val sources = m.sourceList
            if (sources.isNotEmpty()) {
                Section("Sources")
                sources.forEach { (title, url) -> ActionRow(title.ifBlank { url }, url) { if (!Links.open(url)) Ask.notice.value = "No browser on this phone opens links." } }
            }
            m.imagePrompt?.let {
                Section("What was drawn")
                Note(it)
            }
            NoticeLine()
        }
    }

    companion object {
        const val RETRY = "retry"
        const val EDIT = "edit"
    }
}

/**
 * A chat's settings (tap its title, or hold it in the list): the model that answers, its name, and deleting it.
 * For a chat that isn't created yet ([chatId] null), only the model. Returns [MODEL] + a model name, or [DELETED].
 */
class ChatInfoScreen(
    sealedActivity: SealedLightActivity,
    private val chatId: Long?,
    private val current: Model? = null,
) : SimpleLightScreen<String>(sealedActivity) {
    private val work = MainScope()

    override fun onScreenDestroy() = work.cancel()

    @Composable
    override fun Content() {
        val chat by remember { chatId?.let { Ask.db.chat(it) } ?: flowOf(null) }.collectAsState(initial = null)
        val model = chat?.let { Model.of(it.model) } ?: current ?: Model.OPUS
        var confirm by remember { mutableStateOf(false) }
        Page(title = chat?.title ?: "New chat", left = back { goBack() }) {
            ModelChoices(model) { chosen ->
                if (chatId == null) goBack(MODEL + chosen.name)
                else work.launch { Ask.db.setModel(chatId, chosen.name); goBack(MODEL + chosen.name) }
            }
            if (chatId != null) {
                Section("Chat")
                ActionRow("Rename") {
                    editText(title = "Chat name", text = chat?.title.orEmpty(), singleLine = true, submitLabel = "SAVE") { result ->
                        val name = (result as? LightTextEditResult.Submitted)?.text?.trim()
                        if (!name.isNullOrEmpty()) work.launch { Ask.db.rename(chatId, name.take(80)) }
                    }
                }
                ActionRow(if (confirm) "Tap again to delete this chat" else "Delete chat", "Its questions, answers and pictures") {
                    if (!confirm) confirm = true
                    else work.launch { Chats.delete(chatId); goBack(DELETED) }
                }
            }
        }
    }

    companion object {
        const val MODEL = "model:"
        const val DELETED = "deleted"
    }
}

/** Every model, by provider, with a tick on [chosen]. Switching mid-chat keeps the conversation. */
@Composable
fun ModelChoices(chosen: Model, onPick: (Model) -> Unit) {
    val keys by produceState(mapOf<Provider, Boolean>()) {
        value = Provider.entries.associateWith { Ask.store.hasKey(it) }
    }
    Provider.entries.forEach { provider ->
        Section(provider.label + if (keys[provider] == false) " (no key yet)" else "")
        provider.models.forEach { m ->
            ChoiceRow(m.label, m == chosen, detail = describe(m)) { onPick(m) }
        }
    }
}

private fun describe(m: Model) = when (m) {
    Model.OPUS -> "Anthropic's best for most things"
    Model.SONNET -> "Fast and capable"
    Model.HAIKU -> "Quickest and cheapest"
    Model.ASTRA -> "OpenAI's best"
    Model.SOL -> "Balances smarts and cost"
    Model.TERRA -> "Faster, cheaper"
    Model.LUNA -> "Quickest and cheapest"
}
