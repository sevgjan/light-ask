package io.github.sevgjan.ask

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Sending and answering. A reply is written in [Ask.scope], so it carries on while you leave the chat; what it has
 * so far is in [live] (by chat) and lands in the archive when it's done, stopped or failed.
 */
object Chats {
    /** A reply being written: its text so far, what the model is doing, and a picture still being drawn. */
    data class Live(val text: String = "", val status: String? = "Thinking…", val preview: ImageBitmap? = null)

    private val liveFlow = MutableStateFlow<Map<Long, Live>>(emptyMap())
    val live: StateFlow<Map<Long, Live>> = liveFlow
    private val jobs = ConcurrentHashMap<Long, Job>()

    fun busy(chatId: Long) = jobs[chatId]?.isActive == true

    fun titleFor(text: String): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return "Photo"
        return if (line.length <= 48) line else line.take(47).trimEnd() + "…"
    }

    /**
     * Sends [text] and [images] in chat [chatId], or in a new chat with [model] when it's null. Returns the chat's id,
     * or null (with [Ask.notice] saying why) when it couldn't be sent.
     */
    suspend fun send(chatId: Long?, model: Model, text: String, images: List<String>): Long? {
        val words = text.trim()
        if (words.isEmpty() && images.isEmpty()) return null
        val chat = chatId?.let { Ask.db.chatNow(it) }
        val use = chat?.let { Model.of(it.model) } ?: model
        if (!Ask.store.hasKey(use.provider)) {
            Ask.notice.value = "Add your ${use.provider.keyLabel} in settings first."
            return null
        }
        if (!Ask.store.useQuestion()) {
            Ask.notice.value = "No questions left today. Change the limit in settings."
            return null
        }
        val now = System.currentTimeMillis()
        val id = chat?.id ?: Ask.db.insert(Chat(title = titleFor(words), model = use.name, created = now, updated = now))
        Ask.db.insert(Message(chatId = id, role = Message.USER, text = words, images = images.joinToString("\n"), created = now))
        Ask.db.touch(id, now)
        answer(id)
        return id
    }

    /** Stops the reply being written in [chatId], keeping what it has so far. */
    fun stop(chatId: Long) {
        jobs[chatId]?.cancel()
    }

    /** Asks again for the last answer in [chatId] (after an error, a stop, or to get a different one). */
    fun retry(chatId: Long) {
        if (busy(chatId)) return
        Ask.scope.launch {
            val last = Ask.db.messagesNow(chatId).lastOrNull() ?: return@launch
            if (!last.isUser) Ask.db.deleteFrom(chatId, last.id)
            answer(chatId)
        }
    }

    /** Takes back the question [message] and everything after it; returns it, to change in the field and send again. */
    suspend fun takeBack(message: Message): Message? {
        if (!message.isUser || busy(message.chatId)) return null
        Ask.db.deleteFrom(message.chatId, message.id)
        return message
    }

    suspend fun delete(chatId: Long) {
        stop(chatId)
        Ask.db.delete(chatId)
        Pictures.tidy(Ask.db.allImages().flatMap { it.lines() }.filter { it.isNotBlank() }.toSet())
    }

    suspend fun deleteAll() {
        jobs.keys.forEach(::stop)
        Ask.db.deleteAll()
        Pictures.tidy(emptySet())
    }

    /** The archive as the providers see it: questions, and answers that came through. */
    private fun turns(messages: List<Message>): List<Turn> = messages.mapNotNull { m ->
        val pictures = m.imageList.map(Pictures::forModel)
        if (m.isUser) Turn(Role.USER, m.text, images = pictures)
        else if (m.state in setOf(Status.DONE, Status.STOPPED) && (m.text.isNotBlank() || pictures.isNotEmpty())) Turn(Role.ASSISTANT, m.text, made = pictures)
        else null
    }

    /** Writes the reply to the last question in [chatId]. */
    private fun answer(chatId: Long) {
        if (busy(chatId)) return
        val job = Ask.scope.launch(Dispatchers.Default) {
            val chat = Ask.db.chatNow(chatId) ?: return@launch
            val model = Model.of(chat.model) ?: Model.OPUS
            val history = turns(Ask.db.messagesNow(chatId))
            if (history.lastOrNull()?.role != Role.USER) return@launch
            val started = Message(chatId = chatId, role = Message.ASSISTANT, text = "", status = Status.PENDING.name, model = model.name, created = System.currentTimeMillis())
            val replyId = Ask.db.insert(started)
            val key = Ask.store.apiKey(model.provider).first()
            if (key == null) {
                Ask.db.update(started.copy(id = replyId, status = Status.ERROR.name, note = "Add your ${model.provider.keyLabel} in settings."))
                return@launch
            }
            liveFlow.update { it + (chatId to Live()) }
            val text = StringBuilder()
            val images = mutableListOf<String>()
            var madePrompt: String? = null
            val sources = LinkedHashMap<String, String>()
            var cost = ChatCost()

            fun publish(change: (Live) -> Live) = liveFlow.update { all -> all[chatId]?.let { all + (chatId to change(it)) } ?: all }
            fun saved(status: Status, note: String?) = started.copy(
                id = replyId,
                text = Markdown.plain(text.toString()).trim(),
                images = images.joinToString("\n"),
                sources = sources.entries.joinToString("\n") { (url, title) -> "${title.replace(Regex("[\t\n]"), " ")}\t$url" },
                status = status.name,
                note = note,
                imagePrompt = madePrompt,
            )

            val sink = object : Sink {
                override fun cost(value: ChatCost) { cost += value }

                override fun text(delta: String) {
                    text.append(delta)
                    val now = text.toString()
                    publish { it.copy(text = now, status = null) }
                }
                override fun status(text: String?) = publish { it.copy(status = text) }
                override fun preview(bytes: ByteArray) {
                    val bitmap = Pictures.preview(bytes) ?: return
                    publish { it.copy(preview = bitmap) }
                }
                override suspend fun image(bytes: ByteArray, prompt: String?) {
                    val name = Pictures.addMade(bytes) ?: return
                    images += name
                    if (prompt != null) madePrompt = prompt
                    // Kept at once, so a picture isn't lost if the rest of the reply fails.
                    Ask.db.update(saved(Status.PENDING, null))
                    publish { it.copy(preview = null) }
                }
                override fun source(title: String, url: String) {
                    if (url !in sources) sources[url] = title
                }
            }

            val options = Options(
                model = model,
                depth = Ask.store.depth.first(),
                webSearch = Ask.store.webSearch.first(),
                imageModel = Ask.store.imageModel.first(),
                openAiKey = Ask.store.apiKey(Provider.OPENAI).first(),
                today = Ask.today(),
            )
            var result: Message? = null
            try {
                val finish = model.provider.api.stream(key, options, history, sink)
                result = saved(if (finish.refused) Status.REFUSED else Status.DONE, if (finish.truncated) "Cut short." else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                result = saved(Status.ERROR, e.message)
            } catch (e: Exception) {
                result = saved(Status.ERROR, "No connection. Try again when you're online.")
            } finally {
                withContext(NonCancellable) {
                    Ask.db.finish(result ?: saved(Status.STOPPED, null), cost)
                    liveFlow.update { it - chatId }
                    jobs.remove(chatId)
                    Ask.db.touch(chatId, System.currentTimeMillis())
                }
            }
        }
        jobs[chatId] = job
    }
}
