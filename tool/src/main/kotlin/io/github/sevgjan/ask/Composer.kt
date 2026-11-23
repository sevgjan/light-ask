package io.github.sevgjan.ask

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * The question line: photos waiting to go, then a picture icon, the underlined field and send (or the microphone
 * while it's empty, or stop while a reply is being written). Tapping the field brings up the keyboard under it,
 * in the chat, so the conversation moves up; Return sends. [panel] takes the keyboard's place (dictation).
 */
@Composable
fun Composer(
    state: TextFieldState,
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    attachments: List<String>,
    onRemove: (String) -> Unit,
    busy: Boolean,
    canSpeak: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onSpeak: () -> Unit,
    panel: (@Composable () -> Unit)? = null,
) {
    val keyboard = rememberTypingKeyboard(state) { onSend() }
    Column(modifier = Modifier.fillMaxWidth().background(LightThemeTokens.colors.background)) {
        if (attachments.isNotEmpty()) AttachmentStrip(attachments, onRemove)
        Row(
            // Sits right on the keyboard while typing, leaving the most room for the conversation. Drawn above the
            // keyboard, whose empty top strip it overlaps.
            modifier = Modifier.zIndex(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())
                .padding(top = if (open) 0.2f.gridUnitsAsDp() else 0.6f.gridUnitsAsDp(), bottom = if (open || panel != null) 0.dp else 0.6f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(LightIcons.MEDIA, onClick = onAttach)
            Field(state, open, Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp()), hint = "Ask anything", onOpen = onOpen)
            val hasContent = state.text.isNotBlank() || attachments.isNotEmpty()
            when {
                busy -> IconButton(LightIcons.STOP, size = 1.5f, onClick = onStop)
                hasContent -> IconButton(LightIcons.SEND, size = 2.2f, onClick = onSend)
                canSpeak -> IconButton(LightIcons.MICROPHONE, size = 2f, onClick = onSpeak)
                else -> LightIcon(icon = LightIcons.SEND, size = 2.2f, modifier = Modifier.graphicsLayer { alpha = 0.35f })
            }
        }
        when {
            panel != null -> panel()
            open -> KeyboardPanel(keyboard, onClose)
        }
    }
}

/** Photos about to be sent, small, each with a × to take it off. */
@Composable
private fun AttachmentStrip(names: List<String>, onRemove: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 1f.gridUnitsAsDp()).padding(top = 0.5f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.spacedBy(0.6f.gridUnitsAsDp()),
    ) {
        names.forEach { name ->
            val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, name) { value = Pictures.bitmap(name, 240) }
            Box(modifier = Modifier.size(4.5f.gridUnitsAsDp()).clip(RoundedCornerShape(6.dp)).lightClickable { onRemove(name) }) {
                bitmap?.let { Image(bitmap = it, contentDescription = "Photo to send", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(1.4f.gridUnitsAsDp())
                        .background(LightThemeTokens.colors.background.copy(alpha = 0.75f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { LightIcon(icon = LightIcons.CLOSE, size = 0.9f) }
            }
        }
    }
}

/** The text with an underline, a cursor while typing, and a hint when empty. LightsApp's message field. */
@Composable
internal fun Field(state: TextFieldState, open: Boolean, modifier: Modifier, hint: String, onOpen: () -> Unit = {}) {
    val colors = LightThemeTokens.colors
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val text = state.text.toString()
    // Hold the field: Paste (and Copy / Select all), as a small row just above it.
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(menu) { if (menu) { kotlinx.coroutines.delay(6000); menu = false } }
    // Taps around the text (not on it) open the keyboard, and drop a selection: the cursor goes to the end.
    Column(modifier = modifier.lightClickable {
        if (!state.selection.collapsed) state.edit { selection = TextRange(length) }
        onOpen()
    }) {
        if (menu) EditMenu(state) { menu = false }
        // Up to about three lines, then it scrolls, keeping the cursor in view.
        val scroll = rememberScrollState()
        LaunchedEffect(state.selection, state.text, layout) {
            val l = layout ?: return@LaunchedEffect
            val rect = l.getCursorRect(state.selection.max.coerceIn(0, l.layoutInput.text.length))
            val view = scroll.viewportSize
            if (view <= 0) return@LaunchedEffect
            if (rect.bottom > scroll.value + view) scroll.scrollTo((rect.bottom - view).toInt() + 1)
            else if (rect.top < scroll.value) scroll.scrollTo(rect.top.toInt())
        }
        Box(modifier = Modifier.fillMaxWidth().heightIn(max = 6f.gridUnitsAsDp()).verticalScroll(scroll)) {
            if (text.isEmpty()) LightText(text = hint, variant = LightTextVariant.Copy, lighten = true)
            val selected = state.selection
            BasicText(
                text = text,
                style = typingStyle(colors.content),
                onTextLayout = { layout = it },
                modifier = Modifier.fillMaxWidth()
                    // The selected word, highlighted behind the text.
                    .drawBehind {
                        val l = layout ?: return@drawBehind
                        if (open && !selected.collapsed && selected.max <= l.layoutInput.text.length) {
                            drawPath(l.getPathForRange(selected.min, selected.max), colors.content.copy(alpha = 0.3f))
                        }
                    }
                    // Tap: the cursor goes where you tap. Double tap: select the word, to delete or retype it.
                    .pointerInput(state) {
                        detectTapGestures(
                            onPress = { pos ->
                                val l = layout
                                if (l != null && state.text.isNotEmpty() && state.selection.collapsed) {
                                    val at = l.getOffsetForPosition(pos).coerceIn(0, state.text.length)
                                    state.edit { selection = TextRange(at) }
                                }
                                onOpen()
                            },
                            onTap = { pos ->
                                val l = layout
                                if (l != null && !state.selection.collapsed) {
                                    val at = l.getOffsetForPosition(pos).coerceIn(0, state.text.length)
                                    state.edit { selection = TextRange(at) }
                                }
                                menu = false
                            },
                            onLongPress = { menu = true; onOpen() },
                            onDoubleTap = { pos ->
                                val l = layout
                                if (l != null && state.text.isNotEmpty()) {
                                    val at = l.getOffsetForPosition(pos).coerceIn(0, state.text.length)
                                    state.edit { selection = wordAt(state.text, at) }
                                }
                                onOpen()
                            },
                        )
                    },
            )
            val l = layout
            if (open && l != null && selected.collapsed) {
                val rect = l.getCursorRect(state.selection.min.coerceIn(0, l.layoutInput.text.length))
                Box(
                    modifier = Modifier
                        .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                        .width(2.dp)
                        .height(with(LocalDensity.current) { rect.height.toDp() })
                        .background(colors.content),
                )
            }
        }
        Spacer(Modifier.height(0.15f.gridUnitsAsDp()))
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(colors.content))
    }
}

/** Paste, Copy and Select all, over the field. */
@Composable
private fun EditMenu(state: TextFieldState, close: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val clip = remember { runCatching { clipboard.getText()?.text }.getOrNull()?.takeIf { it.isNotEmpty() } }
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 0.3f.gridUnitsAsDp()), horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp())) {
        if (clip != null) LightText(text = "PASTE", variant = LightTextVariant.Fine, modifier = Modifier.lightClickable {
            state.edit { val at = selection.min; replace(at, selection.max, clip); selection = TextRange(at + clip.length) }
            close()
        })
        if (!state.selection.collapsed) LightText(text = "COPY", variant = LightTextVariant.Fine, modifier = Modifier.lightClickable {
            clipboard.setText(AnnotatedString(state.text.substring(state.selection.min, state.selection.max)))
            close()
        })
        if (state.text.isNotEmpty()) LightText(text = "SELECT ALL", variant = LightTextVariant.Fine, modifier = Modifier.lightClickable {
            state.edit { selection = TextRange(0, length) }
        })
        if (clip == null && state.text.isEmpty()) LightText(text = "Nothing to paste", variant = LightTextVariant.Fine, lighten = true)
    }
}

/** The word around [at] (letters, digits and apostrophes), or just the cursor there when it's on a space. */
private fun wordAt(text: CharSequence, at: Int): TextRange {
    fun part(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’'
    var start = at
    var end = at
    while (start > 0 && part(text[start - 1])) start--
    while (end < text.length && part(text[end])) end++
    return TextRange(start, end)
}

/** Like the SDK's text editor callback, but Return sends instead of starting a new line. LightsApp's. */
internal class ChatKeyboardCallback(
    private val state: TextFieldState,
    private val onReturn: () -> Unit,
    private val onType: () -> Unit,
) : Lp3RepeatableKeyboardCallback {
    override fun onKeyPressed(code: Int) = Unit
    private var lastSpaceAt = 0L

    override fun onSpecialKeyPressed(key: SpecialKey) {
        if (key != SpecialKey.Space) return
        // Double-tap space after a word: ". " (Light's keyboard has no such option of its own).
        val now = System.currentTimeMillis()
        val before = state.text.subSequence(0, state.selection.min)
        if (KeyboardPrefs.doubleSpace.value && now - lastSpaceAt < 800 && before.length >= 2 && before.last() == ' ' && before[before.length - 2].isLetterOrDigit()) {
            state.edit {
                val at = selection.min
                replace(at - 1, at, ". ")
                selection = TextRange(at + 1)
            }
            lastSpaceAt = 0L
            onType()
            return
        }
        lastSpaceAt = now
        insert(" ")
    }
    override fun onKeyReleased(code: Int) = insert(buildString { appendCodePoint(code) })
    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> {
                // A held backspace already deleted words while held: letting go doesn't take another letter.
                if (heldBackspace) { heldBackspace = false; return }
                // A selected word goes in one go.
                if (!state.selection.collapsed) {
                    state.edit { val start = selection.min; delete(start, selection.max); selection = TextRange(start) }
                    onType()
                    return
                }
                val before = state.text.subSequence(0, state.selection.min)
                deleteBefore(lastCharacterLength(before))
            }
            SpecialKey.Return -> onReturn()
            else -> Unit
        }
    }
    override fun onKeyLongPressed(code: Int) = Unit
    /** Backspace is being held: it deletes a word at a time, then everything. */
    private var heldBackspace = false
    private var wordRepeats = 0

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) {
            heldBackspace = true
            wordRepeats = 0
            deleteWord()
        }
    }

    private fun deleteWord() {
        val before = state.text.subSequence(0, state.selection.min)
        val trimmed = before.trimEnd()
        val lastSpace = trimmed.indexOfLast { it.isWhitespace() }
        deleteBefore(before.length - if (lastSpace >= 0) lastSpace + 1 else 0)
    }
    override fun onKeyRepeated(code: Int) = insert(buildString { appendCodePoint(code) })
    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        when (specialKey) {
            SpecialKey.Space -> insert(" ")
            SpecialKey.Backspace -> {
                // Still held: a word on each repeat; after three words, all of it.
                heldBackspace = true
                wordRepeats++
                if (wordRepeats >= 3) deleteBefore(state.selection.min) else deleteWord()
            }
            else -> Unit
        }
    }
    /** A swiped word: a space before it unless the cursor already follows one (the keyboard adds the one after). */
    override fun onSubmitWord(word: CharSequence) {
        val before = state.text.subSequence(0, state.selection.min)
        val lead = if (before.isEmpty() || before.last().isWhitespace() || before.last() in "([\"'“‘") "" else " "
        insert("$lead$word")
    }

    private fun insert(text: String) {
        state.edit {
            val start = selection.min
            replace(start, selection.max, text)
            selection = TextRange(start + text.length)
        }
        onType()
    }

    private fun deleteBefore(count: Int) {
        if (count <= 0) return
        state.edit {
            val end = selection.min
            if (end == 0) return@edit
            val start = (end - count).coerceAtLeast(0)
            delete(start, end)
            selection = TextRange(start)
        }
    }
}

/** The "copy" style scaled for the LP3 screen the way LightText scales it, so typing matches the messages. */
@Composable
private fun typingStyle(color: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.TextStyle {
    val base = LightThemeTokens.typography.copy
    val unset = androidx.compose.ui.unit.TextUnit.Unspecified
    val size = if (base.fontSize == unset) unset else base.fontSize.value.designVerticalPxToSp()
    val line = if (base.lineHeight == unset) unset else base.lineHeight.value.designVerticalPxToSp()
    val spacing = if (base.letterSpacing == unset) unset else base.letterSpacing.value.designVerticalPxToSp()
    return base.copy(color = color, fontSize = size, lineHeight = line, letterSpacing = spacing)
}

/** How many chars the last character before the cursor takes: one for a letter, more for an emoji like ❤️ or 👍🏽. */
internal fun lastCharacterLength(before: CharSequence): Int {
    if (before.isEmpty()) return 0
    val text = before.toString()
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(text)
    val start = it.preceding(text.length)
    return if (start == java.text.BreakIterator.DONE) 1 else text.length - start
}
