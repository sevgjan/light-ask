package io.github.sevgjan.ask

import android.Manifest
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.PermissionRequestLauncher
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.CaptureConfig
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.MicSource
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.sevgjan.ask.engine.Links
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * One conversation, LightsApp-style: questions on the right in faint bubbles, answers across the screen as they're
 * written, pictures you can open, and the question line with LightsApp's keyboard at the bottom. [chatId] null is a
 * new chat, created when its first question goes. [speakNow] starts dictating at once (SPEAK on the chat list).
 */
class ChatScreen(
    private val sealedActivity: SealedLightActivity,
    chatId: Long?,
    private val speakNow: Boolean = false,
) : SimpleLightScreen<Unit>(sealedActivity) {
    private val current = MutableStateFlow(chatId)
    /** The model for a chat that isn't created yet (null: the one in settings). */
    private val pendingModel = MutableStateFlow<Model?>(null)
    private val draft = TextFieldState()
    private val composing = MutableStateFlow(false)
    private val attachments = MutableStateFlow<List<String>>(emptyList())
    private val dictation = MutableStateFlow<Dictation?>(null)
    /** Outlives this screen's composition, which other screens (photos, camera) replace for a while. */
    private val work = MainScope()
    /** Bumped when a question goes, so the list brings it to the top. */
    private val sentAt = MutableStateFlow(0L)
    private var spoke = false
    private var micLauncher: PermissionRequestLauncher? = null

    override fun willHide() = cancelDictation()
    override fun onAppPause() = cancelDictation()
    override fun onScreenDestroy() {
        cancelDictation()
        work.cancel()
    }

    @Composable
    override fun Content() {
        micLauncher = rememberPermissionRequestLauncher(Manifest.permission.RECORD_AUDIO)
        val id by current.collectAsState()
        val chat by remember(id) { id?.let { Ask.db.chat(it) } ?: flowOf(null) }.collectAsState(initial = null)
        val messages by remember(id) { id?.let { Ask.db.messages(it) } ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
        val liveAll by Chats.live.collectAsState()
        val live = id?.let { liveAll[it] }
        val defaultModel by remember { Ask.store.newChatModel }.collectAsState(initial = Model.OPUS)
        val chosen by pendingModel.collectAsState()
        val model = chat?.let { Model.of(it.model) } ?: chosen ?: defaultModel
        val canSpeak by remember { Ask.store.apiKey(Provider.OPENAI).map { it != null } }.collectAsState(initial = false)
        val open by composing.collectAsState()
        val files by attachments.collectAsState()
        val dict by dictation.collectAsState()
        val sent by sentAt.collectAsState()

        LaunchedEffect(Unit) { if (speakNow && !spoke) { spoke = true; startDictation() } }

        Page(
            title = chat?.title ?: "New chat",
            subtitle = model.short,
            left = back { goBack() },
            right = if (id != null) LightBarButton.LightIcon(icon = LightIcons.COMPOSE_MESSAGE, onClick = ::newChat) else null,
            onTitleClick = {
                navigateTo({ ChatInfoScreen(it, id, model) }) { result ->
                    when {
                        result == ChatInfoScreen.DELETED -> goBack()
                        result.startsWith(ChatInfoScreen.MODEL) -> Model.of(result.removePrefix(ChatInfoScreen.MODEL))?.let { m ->
                            val chatNow = current.value
                            if (chatNow == null) pendingModel.value = m else work.launch { Ask.db.setModel(chatNow, m.name) }
                        }
                    }
                }
            },
            scroll = false,
            padded = false,
        ) {
            NoticeLine(Modifier.padding(horizontal = 1f.gridUnitsAsDp()))
            Conversation(messages, live, model, open, sent, Modifier.weight(1f))
            Composer(
                state = draft,
                open = open && dict == null,
                onOpen = { if (dictation.value == null) composing.value = true },
                onClose = { composing.value = false },
                attachments = files,
                onRemove = { name -> attachments.value = attachments.value - name },
                busy = live != null,
                canSpeak = canSpeak,
                onSend = ::send,
                onStop = { current.value?.let(Chats::stop) },
                onAttach = { navigateTo({ AttachScreen(it) }) { name -> attachments.value = attachments.value + name } },
                onSpeak = ::startDictation,
                panel = dict?.let { d -> { DictationPanel(d) } },
            )
        }
    }

    @Composable
    private fun Conversation(messages: List<Message>, live: Chats.Live?, model: Model, keyboardOpen: Boolean, sent: Long, modifier: Modifier) {
        val state = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var placed by remember { mutableStateOf(false) }
        val swipeBack = with(LocalDensity.current) { 4f.gridUnitsAsDp().toPx() }
        val count = messages.size

        // Opening a chat: at its end. Sending: the question at the top, the answer growing under it.
        LaunchedEffect(count > 0) {
            if (count > 0 && !placed) { state.scrollToItem(count - 1); state.scrollBy(100_000f); placed = true }
        }
        LaunchedEffect(sent, count) {
            if (sent == 0L) return@LaunchedEffect
            val question = messages.indexOfLast { it.isUser }
            if (question >= 0) state.scrollToItem(question)
        }
        LaunchedEffect(keyboardOpen) { if (keyboardOpen && count > 0) { state.scrollToItem(count - 1); state.scrollBy(100_000f) } }

        Box(
            modifier = modifier.fillMaxWidth()
                // Swipe right anywhere on the conversation to go back to the chats.
                .pointerInput(Unit) {
                    var dx = 0f
                    detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx > swipeBack) goBack() }) { change, d ->
                        dx += d
                        change.consume()
                    }
                },
        ) {
            if (messages.isEmpty()) {
                // With the keyboard out there's room for one line only.
                CenteredNote(if (keyboardOpen) "Ask ${model.label} anything." else emptyHint(model))
                return@Box
            }
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(horizontal = 0.4f.gridUnitsAsDp())) {
                items(messages, key = { it.id }) { m ->
                    if (m.isUser) Question(m) else Answer(m, if (m.state == Status.PENDING) live else null)
                }
                item { Box(Modifier.height(0.6f.gridUnitsAsDp())) }
            }
            // Scrolled up: a round ↓ to jump back to the end.
            if (state.canScrollForward && count > 0) RoundDown(Modifier.align(Alignment.BottomEnd).padding(1f.gridUnitsAsDp())) {
                scope.launch { state.scrollToItem(count - 1); state.scrollBy(100_000f) }
            }
        }
    }

    private fun emptyHint(model: Model) = buildString {
        append("Ask ${model.label} anything.")
        append("\n\nAdd a photo to ask about it, or ask for a picture.")
        append("\n\nTap the title to switch models.")
    }

    /** A tap on a message: puts the keyboard away if it's out, else opens what you can do with it. */
    private fun tapped(m: Message) {
        if (composing.value) { composing.value = false; return }
        // A failed answer at the end: tapping it asks again.
        if (!m.isUser && m.state == Status.ERROR) {
            work.launch {
                if (Ask.db.messagesNow(m.chatId).lastOrNull()?.id == m.id) Chats.retry(m.chatId) else actions(m)
            }
            return
        }
        actions(m)
    }

    private fun actions(m: Message) {
        navigateTo({ MessageScreen(it, m) }) { action ->
            when (action) {
                MessageScreen.RETRY -> Chats.retry(m.chatId)
                MessageScreen.EDIT -> work.launch {
                    val back = Chats.takeBack(m) ?: return@launch
                    draft.setTextAndPlaceCursorAtEnd(back.text)
                    attachments.value = back.imageList
                    composing.value = true
                }
            }
        }
    }

    @Composable
    private fun Question(m: Message) {
        Column(
            modifier = Modifier.fillMaxWidth().lightClickable { tapped(m) }
                .padding(start = 4f.gridUnitsAsDp()).padding(top = 1f.gridUnitsAsDp(), bottom = 0.3f.gridUnitsAsDp()),
            horizontalAlignment = Alignment.End,
        ) {
            if (m.imageList.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(0.4f.gridUnitsAsDp()), modifier = Modifier.padding(bottom = 0.3f.gridUnitsAsDp())) {
                m.imageList.take(4).forEach { name ->
                    ChatPicture(name, Modifier.size(6f.gridUnitsAsDp()).clip(RoundedCornerShape(6.dp)).lightClickable { navigateTo({ ViewerScreen(it, m.chatId, name) }) }, crop = true)
                }
            }
            if (m.text.isNotBlank()) Box(
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(LightThemeTokens.colors.content.copy(alpha = 0.08f))
                    .padding(horizontal = 0.5f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
            ) { LightText(text = m.text, variant = LightTextVariant.Copy) }
        }
    }

    @Composable
    private fun Answer(m: Message, live: Chats.Live?) {
        val text = Markdown.plain(live?.text ?: m.text).trim()
        val model = Model.of(m.model)
        Column(modifier = Modifier.fillMaxWidth().lightClickable { tapped(m) }.padding(top = 0.6f.gridUnitsAsDp(), bottom = 0.3f.gridUnitsAsDp())) {
            m.imageList.forEach { name ->
                ChatPicture(
                    name,
                    Modifier.fillMaxWidth().padding(vertical = 0.3f.gridUnitsAsDp()).clip(RoundedCornerShape(6.dp))
                        .lightClickable { navigateTo({ ViewerScreen(it, m.chatId, name) }) },
                )
            }
            live?.preview?.let { preview ->
                Image(bitmap = preview, contentDescription = "Picture being drawn", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(preview.width.toFloat() / preview.height).padding(vertical = 0.3f.gridUnitsAsDp()).clip(RoundedCornerShape(6.dp)))
            }
            if (text.isNotEmpty()) LightText(text = text, variant = LightTextVariant.Copy)
            when {
                live != null -> {
                    val status = live.status ?: if (text.isEmpty()) "Thinking…" else null
                    status?.let { LightText(text = it, variant = LightTextVariant.Fine, lighten = true, modifier = Modifier.padding(top = 0.2f.gridUnitsAsDp())) }
                }
                m.state == Status.PENDING -> Unit
                m.state == Status.ERROR -> Note("${m.note ?: "Something went wrong."} Tap to try again.")
                m.state == Status.REFUSED -> Note("${model?.label ?: "The model"} declined to answer this one.")
                m.state == Status.STOPPED -> Note("Stopped.")
                m.note != null -> Note(m.note)
            }
            val sources = m.sourceList
            if (live == null && sources.isNotEmpty()) {
                LightText(text = "Sources", variant = LightTextVariant.Fine, lighten = true, modifier = Modifier.padding(top = 0.4f.gridUnitsAsDp()))
                sources.take(5).forEach { (title, url) ->
                    LightText(
                        text = "↗ " + title.ifBlank { url },
                        variant = LightTextVariant.Fine,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().lightClickable { if (!Links.open(url)) Ask.notice.value = "No browser on this phone opens links." }
                            .padding(vertical = 0.15f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }

    // --- Sending ---

    private fun send() {
        val text = draft.text.toString()
        val files = attachments.value
        if (text.isBlank() && files.isEmpty()) return
        val id = current.value
        if (id != null && Chats.busy(id)) {
            Ask.notice.value = "Still answering. Wait for it, or tap stop."
            return
        }
        work.launch {
            val model = pendingModel.value ?: Ask.store.newChatModel.first()
            val chat = Chats.send(id, model, text, files) ?: return@launch
            Vocabulary.learn(text)
            draft.clearText()
            attachments.value = emptyList()
            composing.value = false
            current.value = chat
            sentAt.value = System.currentTimeMillis()
        }
    }

    private fun newChat() {
        cancelDictation()
        current.value = null
        pendingModel.value = null
        draft.clearText()
        attachments.value = emptyList()
    }

    // --- Dictation: OpenAI's live transcription, words appearing in the field as you speak ---

    private class Dictation(val prefix: String) {
        val stop = CompletableDeferred<Unit>()
        val finishing = MutableStateFlow(false)
        val error = MutableStateFlow<String?>(null)
        val since = System.currentTimeMillis()
        var sendAfter = false
        var job: Job? = null

        fun finish(send: Boolean = false) {
            sendAfter = sendAfter || send
            finishing.value = true
            stop.complete(Unit)
        }
    }

    private fun startDictation() {
        if (dictation.value != null) return
        work.launch {
            if (!granted(Manifest.permission.RECORD_AUDIO)) {
                Ask.notice.value = "Allow the microphone, then tap the mic again. If LightOS doesn't ask, run on a computer: " +
                    grantCommand(Manifest.permission.RECORD_AUDIO)
                micLauncher?.launch()
                return@launch
            }
            val key = Ask.store.apiKey(Provider.OPENAI).first()
            if (key == null) {
                Ask.notice.value = "Speaking needs an OpenAI API key (settings)."
                return@launch
            }
            composing.value = false
            val before = draft.text.toString().trimEnd()
            val d = Dictation(if (before.isEmpty()) "" else "$before ")
            dictation.value = d
            // The processed mic path keeps gain and noise suppression, which suits speech.
            val capture = DefaultLightAudio(sealedActivity).newCapture(
                CaptureConfig(sampleRate = LiveDictation.SAMPLE_RATE, bufferFrames = LiveDictation.SAMPLE_RATE / 10, source = MicSource.Mic),
            )
            d.job = work.launch {
                val timer = launch { delay(3.minutes); d.finish() }
                try {
                    val final = LiveDictation.transcribe(key, capture.asFlow(), d.stop) { words -> draft.setTextAndPlaceCursorAtEnd(d.prefix + words) }
                    if (final.isNotBlank()) draft.setTextAndPlaceCursorAtEnd(d.prefix + final)
                    if (dictation.value === d) dictation.value = null
                    if (d.sendAfter) send()
                } catch (e: ApiException) {
                    d.error.value = e.message
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    d.error.value = "Dictation stopped unexpectedly."
                } finally {
                    timer.cancel()
                }
            }
        }
    }

    /** Stops listening and keeps the words so far. */
    private fun cancelDictation() {
        val d = dictation.value ?: return
        d.job?.cancel()
        dictation.value = null
    }

    @Composable
    private fun DictationPanel(d: Dictation) {
        val finishing by d.finishing.collectAsState()
        val error by d.error.collectAsState()
        val seconds by produceState(0, d) { while (true) { value = ((System.currentTimeMillis() - d.since) / 1000).toInt(); delay(500) } }
        Column(modifier = Modifier.fillMaxWidth().padding(top = 0.8f.gridUnitsAsDp())) {
            LightText(
                text = error ?: if (finishing) "Finishing…" else "Listening…  %d:%02d".format(seconds / 60, seconds % 60),
                variant = LightTextVariant.Fine,
                lighten = error == null,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.6f.gridUnitsAsDp()),
            )
            LightBottomBar(
                if (error != null) listOf(LightBarButton.Text(text = "CLOSE", onClick = { dictation.value = null }))
                else listOf(
                    LightBarButton.Text(text = "CANCEL", onClick = {
                        d.job?.cancel()
                        draft.setTextAndPlaceCursorAtEnd(d.prefix.trimEnd())
                        dictation.value = null
                    }),
                    LightBarButton.Text(text = "DONE", onClick = { d.finish() }),
                    LightBarButton.Text(text = "SEND", onClick = { d.finish(send = true) }),
                ),
            )
        }
    }
}

/** One of Ask's pictures, shaped like itself (or cropped to its box). */
@Composable
fun ChatPicture(name: String, modifier: Modifier = Modifier, crop: Boolean = false, maxSide: Int = 1080) {
    val bitmap by produceState<ImageBitmap?>(null, name, maxSide) { value = Pictures.bitmap(name, maxSide) }
    val b = bitmap
    if (b == null) {
        Box(modifier.let { if (crop) it else it.aspectRatio(1f) }.background(LightThemeTokens.colors.content.copy(alpha = 0.06f)))
        return
    }
    Image(
        bitmap = b,
        contentDescription = "Picture",
        contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
        modifier = if (crop) modifier else modifier.aspectRatio(b.width.toFloat() / b.height),
    )
}

/** A round ↓ over the bottom of the conversation. */
@Composable
private fun RoundDown(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.size(2.6f.gridUnitsAsDp()).clip(CircleShape)
            .background(LightThemeTokens.colors.background)
            .background(LightThemeTokens.colors.content.copy(alpha = 0.12f))
            .lightClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { LightIcon(icon = LightIcons.DOWN, size = 1.3f) }
}
