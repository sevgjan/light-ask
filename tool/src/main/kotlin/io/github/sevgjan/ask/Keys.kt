package io.github.sevgjan.ask

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Ask's own keyboard: the LP3 keyboard's look (Akkurat letters, black and white) with the spacing of
 * a phone keyboard: taller rows, gaps you can see between keys (that still count as the nearest key, so
 * no tap is lost), a bubble over your finger, accents on long press, swipe typing, and a space bar you
 * can drag to move the cursor.
 */
class TypingKeyboard internal constructor(
    internal val state: TextFieldState,
    internal val editor: ChatKeyboardCallback,
) {
    internal val decoder = SwipeDecoder()
    internal val shift = MutableStateFlow(Shift.Once)
    internal val page = MutableStateFlow(Page.Letters)
    internal var lastShiftTap = 0L

    enum class Shift { Off, Once, Lock }
    enum class Page { Letters, Symbols, More, Emoji }

    /** At the start of a message or sentence, the next letter is a capital (unless caps lock is on). */
    internal fun autoShift() {
        if (shift.value == Shift.Lock) return
        // Capitals turned off in the keyboard settings: lowercase until Shift.
        if (!KeyboardPrefs.capitals.value) { shift.value = Shift.Off; return }
        val before = state.text.subSequence(0, state.selection.min.coerceIn(0, state.text.length))
        val trimmed = before.trimEnd()
        val start = trimmed.isEmpty() || (before.length > trimmed.length && trimmed.last() in ".!?\n")
        shift.value = if (start) Shift.Once else Shift.Off
    }

    internal fun type(text: String) {
        text.codePoints().forEach { editor.onKeyReleased(it) }
    }

    /** Moves the cursor [by] characters (dragging the space bar). */
    internal fun moveCursor(by: Int) {
        state.edit {
            val at = (selection.min + by).coerceIn(0, length)
            selection = TextRange(at)
        }
    }
}

/** The keyboard typing into [state]: Return calls [onReturn], every key [onType]. */
@Composable
internal fun rememberTypingKeyboard(state: TextFieldState, onType: () -> Unit = {}, onReturn: () -> Unit): TypingKeyboard {
    val ret by rememberUpdatedState(onReturn)
    val typing by rememberUpdatedState(onType)
    return remember(state) { TypingKeyboard(state, ChatKeyboardCallback(state, onReturn = { ret() }, onType = { typing() })) }
}

private enum class Kind { Char, Shift, Backspace, Mode, Space, Return, Close, Emoji }

/** One key: what it types (or does), its label, and its width in key units. */
private data class Key(val kind: Kind, val text: String = "", val width: Float = 1f, val label: String = text, val to: TypingKeyboard.Page? = null)

private fun chars(s: String) = s.map { Key(Kind.Char, it.toString()) }

private fun bottomRow(mode: Key, second: Key = Key(Kind.Char, ",")) = listOf(
    // No "." next to space (too easy to hit instead): double space types ". ", and holding "," types ".".
    // No close key: tapping the conversation puts the keyboard away.
    mode, second, Key(Kind.Space, width = 6.2f), Key(Kind.Return, width = 1.4f),
)

private val LETTERS = listOf(
    chars("qwertyuiop"),
    chars("asdfghjkl"),
    listOf(Key(Kind.Shift, width = 1.5f)) + chars("zxcvbnm") + Key(Kind.Backspace, width = 1.5f),
    // The letters have an emoji key where "," was; "," is on the ?123 page.
    bottomRow(Key(Kind.Mode, width = 1.4f, label = "?123", to = TypingKeyboard.Page.Symbols), Key(Kind.Emoji)),
)

private val SYMBOLS = listOf(
    chars("1234567890"),
    chars("@#€_&-+()/"),
    listOf(Key(Kind.Mode, width = 1.5f, label = "=\\<", to = TypingKeyboard.Page.More)) + chars("*\"':;!?") + Key(Kind.Backspace, width = 1.5f),
    bottomRow(Key(Kind.Mode, width = 1.4f, label = "ABC", to = TypingKeyboard.Page.Letters)),
)

private val MORE = listOf(
    chars("~`|^°=%{}\\"),
    chars("$£¥<>[]•…"),
    listOf(Key(Kind.Mode, width = 1.5f, label = "?123", to = TypingKeyboard.Page.Symbols)) + chars("©®™✓¿¡§") + Key(Kind.Backspace, width = 1.5f),
    bottomRow(Key(Kind.Mode, width = 1.4f, label = "ABC", to = TypingKeyboard.Page.Letters)),
)

/** Long press: the digit on the top row, then the accents used in Albanian, Gorani, Bosnian and Western Europe. */
private val ALTERNATES = mapOf(
    "q" to "1", "w" to "2", "e" to "3ëéèêē", "r" to "4", "t" to "5", "y" to "6ý", "u" to "7üúùû", "i" to "8íìîï", "o" to "9öóôòø", "p" to "0",
    "a" to "àáâäãå", "s" to "šśß", "d" to "đ", "g" to "ğ", "l" to "ł",
    "z" to "žźż", "c" to "çčć", "n" to "ñń",
    "," to ".,?!:;'\"-",
)

/** A key laid out: its whole slot (where touches count) and its type. */
private class Placed(val key: Key, val row: Int, val left: Float, val right: Float, val top: Float, val bottom: Float) {
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2
}

/** A finger on the keyboard. */
private class Touch(val key: Placed?, val down: Offset, val at: Long) {
    var pos = down
    var swiping by mutableStateOf(false)
    var held = false
    var job: Job? = null
    var spaceMoved = 0f
    var alternates by mutableStateOf<List<String>?>(null)
    var choice by mutableStateOf(0)
    val trail = ArrayList<Offset>()
}

/** The keyboard, with ↓ (bottom right) to put it away. */
@Composable
internal fun KeyboardPanel(keyboard: TypingKeyboard, onClose: () -> Unit) {
    // LightOS's own typeface (Akkurat), as on the LP3 keyboard.
    val font = LightThemeTokens.typography.copy.fontFamily ?: androidx.compose.ui.text.font.FontFamily.Default
    val page by keyboard.page.collectAsState()
    val suggestions by KeyboardPrefs.suggestions.collectAsState()
    if (page == TypingKeyboard.Page.Emoji) EmojiPanel(keyboard, font)
    else androidx.compose.foundation.layout.Column {
        if (suggestions) SuggestionRow(keyboard, font)
        Keys(keyboard, onClose, font)
    }
}

@Composable
private fun Keys(keyboard: TypingKeyboard, onClose: () -> Unit, font: androidx.compose.ui.text.font.FontFamily) {
    val close by rememberUpdatedState(onClose)
    val compact by KeyboardPrefs.compact.collectAsState()
    val page by keyboard.page.collectAsState()
    val shift by keyboard.shift.collectAsState()
    val rows = when (page) { TypingKeyboard.Page.Letters, TypingKeyboard.Page.Emoji -> LETTERS; TypingKeyboard.Page.Symbols -> SYMBOLS; TypingKeyboard.Page.More -> MORE }
    val density = LocalDensity.current
    val rowHeight = if (compact) 42.dp else 49.dp
    val side = 3.dp
    val gapX = 5.dp
    val gapY = 7.dp
    var size by remember { mutableStateOf(IntSize.Zero) }
    val colors = LightThemeTokens.colors
    val haptics = com.thelightphone.sdk.ui.LocalHapticsEnabled.current
    val swipeOn by KeyboardPrefs.swipe.collectAsState()
    val capitals by KeyboardPrefs.capitals.collectAsState()
    LaunchedEffect(capitals) { keyboard.autoShift() }

    // Capital at the start of a sentence, as the text and the cursor change.
    LaunchedEffect(keyboard) {
        snapshotFlow { keyboard.state.text.toString() to keyboard.state.selection }.collect { keyboard.autoShift() }
    }

    val placed = remember(rows, size) {
        if (size.width == 0) emptyList() else {
            val rowPx = with(density) { rowHeight.toPx() }
            val sidePx = with(density) { side.toPx() }
            val unit = (size.width - 2 * sidePx) / 10f
            rows.flatMapIndexed { r, keys ->
                val total = keys.sumOf { it.width.toDouble() }.toFloat()
                var x = sidePx + (10f - total) * unit / 2 // the middle row sits half a key in
                keys.map { k ->
                    val w = k.width * unit
                    // The bottom row (space, return) is a little shorter than the letter rows.
                    val top = r * rowPx
                    val bottom = if (r == rows.size - 1) top + rowPx * BOTTOM_ROW else top + rowPx
                    Placed(k, r, x, x + w, top, bottom).also { x += w }
                }
            }
        }
    }

    // Tell the swipe decoder where the letters are (in pixels, and how tall a row is next to a key's width).
    LaunchedEffect(placed, page) {
        if (page != TypingKeyboard.Page.Letters || placed.isEmpty()) return@LaunchedEffect
        val letters = placed.filter { it.key.kind == Kind.Char && it.key.text[0] in 'a'..'z' }
        val unit = letters.first().right - letters.first().left
        keyboard.decoder.rowToKey = (placed.first().bottom - placed.first().top) / unit
        keyboard.decoder.onSwipeLayoutReady(
            letters.joinToString("") { it.key.text },
            FloatArray(letters.size) { letters[it].cx },
            FloatArray(letters.size) { letters[it].cy },
        )
    }

    val touches = remember { mutableStateMapOf<PointerId, Touch>() }
    val trail = remember { mutableStateListOf<Offset>() }

    fun hit(p: Offset): Placed? {
        if (placed.isEmpty()) return null
        val row = (p.y / (placed.first().bottom - placed.first().top)).toInt().coerceIn(0, rows.size - 1) // rows start evenly
        return placed.filter { it.row == row }.minByOrNull { abs(it.cx - p.x) }
    }

    fun click() { if (haptics) Ask.keyClick() }

    fun commit(t: Touch) {
        val p = t.key ?: return
        val k = p.key
        val ed = keyboard.editor
        t.alternates?.let { alts -> keyboard.type(caseFor(alts[t.choice], keyboard.shift.value)); afterChar(keyboard); return }
        when (k.kind) {
            Kind.Char -> { keyboard.type(caseFor(k.text, keyboard.shift.value)); afterChar(keyboard) }
            Kind.Space -> if (t.spaceMoved == 0f) ed.onSpecialKeyPressed(SpecialKey.Space)
            Kind.Backspace -> ed.onSpecialKeyReleased(SpecialKey.Backspace)
            Kind.Return -> ed.onSpecialKeyReleased(SpecialKey.Return)
            Kind.Close -> close()
            Kind.Emoji -> keyboard.page.value = TypingKeyboard.Page.Emoji
            Kind.Mode -> keyboard.page.value = k.to ?: TypingKeyboard.Page.Letters
            Kind.Shift -> {
                val now = System.currentTimeMillis()
                keyboard.shift.value = when (keyboard.shift.value) {
                    TypingKeyboard.Shift.Off -> TypingKeyboard.Shift.Once
                    // Twice quickly: caps lock.
                    TypingKeyboard.Shift.Once -> if (now - keyboard.lastShiftTap < 400) TypingKeyboard.Shift.Lock else TypingKeyboard.Shift.Off
                    TypingKeyboard.Shift.Lock -> TypingKeyboard.Shift.Off
                }
                keyboard.lastShiftTap = now
            }
        }
    }

    fun finishSwipe(t: Touch) {
        val pts = t.trail
        val words = keyboard.decoder.onSwipeCompleted(FloatArray(pts.size) { pts[it].x }, FloatArray(pts.size) { pts[it].y }, FloatArray(pts.size))
        val word = words.firstOrNull() ?: return
        keyboard.editor.onSubmitWord(caseFor(word, keyboard.shift.value))
        keyboard.type(" ")
        afterChar(keyboard)
    }

    val swipeStart = with(density) { 28.dp.toPx() }
    val spaceStep = with(density) { 11.dp.toPx() }

    val height = rowHeight * (rows.size - 1 + BOTTOM_ROW) + 6.dp
    Box(
        modifier = Modifier.fillMaxWidth().height(height).zIndex(2f).background(colors.background)
            .onSizeChanged { size = it }
            .pointerInput(placed) {
                coroutineScope {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (ch in event.changes) {
                                when {
                                    ch.changedToDown() -> {
                                        val p = hit(ch.position)
                                        val t = Touch(p, ch.position, System.currentTimeMillis())
                                        touches[ch.id] = t
                                        click()
                                        when (p?.key?.kind) {
                                            Kind.Backspace -> t.job = launch {
                                                delay(400)
                                                t.held = true
                                                keyboard.editor.onSpecialKeyLongPressed(SpecialKey.Backspace)
                                                while (true) { delay(260); keyboard.editor.onSpecialKeyRepeated(SpecialKey.Backspace) }
                                            }
                                            Kind.Char -> ALTERNATES[p!!.key.text]?.let { alts ->
                                                t.job = launch {
                                                    delay(380)
                                                    if (!t.swiping) {
                                                        t.alternates = alts.map { it.toString() }
                                                        t.choice = 0
                                                        click()
                                                    }
                                                }
                                            }
                                            else -> Unit
                                        }
                                    }
                                    ch.changedToUp() -> {
                                        val t = touches.remove(ch.id) ?: continue
                                        t.job?.cancel()
                                        if (t.swiping) finishSwipe(t) else commit(t)
                                        trail.clear()
                                    }
                                    ch.pressed && ch.positionChanged() -> {
                                        val t = touches[ch.id] ?: continue
                                        val prev = t.pos
                                        t.pos = ch.position
                                        val k = t.key?.key
                                        val alts = t.alternates
                                        when {
                                            alts != null -> {
                                                val cell = (t.key!!.right - t.key.left)
                                                val startX = alternatesLeft(t.key, alts.size, size.width.toFloat())
                                                t.choice = ((ch.position.x - startX) / cell).toInt().coerceIn(0, alts.size - 1)
                                            }
                                            k?.kind == Kind.Space -> {
                                                // Drag the space bar to move the cursor.
                                                t.spaceMoved += ch.position.x - prev.x
                                                while (abs(t.spaceMoved) >= spaceStep) {
                                                    val dir = if (t.spaceMoved > 0) 1 else -1
                                                    keyboard.moveCursor(dir)
                                                    t.spaceMoved -= dir * spaceStep
                                                    if (t.spaceMoved == 0f) t.spaceMoved = 0.01f * dir
                                                }
                                            }
                                            k?.kind == Kind.Char && page == TypingKeyboard.Page.Letters && swipeOn && !t.swiping &&
                                                hypot(ch.position.x - t.down.x, ch.position.y - t.down.y) > swipeStart -> {
                                                t.swiping = true
                                                t.job?.cancel()
                                                t.trail += t.down
                                                t.trail += ch.position
                                                trail.clear(); trail += t.trail
                                            }
                                            t.swiping -> {
                                                t.trail += ch.position
                                                trail += ch.position
                                            }
                                        }
                                    }
                                }
                                ch.consume()
                            }
                        }
                    }
                }
            },
    ) {
        val px = { v: Float -> with(density) { v.toDp() } }
        val gx = with(density) { gapX.toPx() }
        val gy = with(density) { gapY.toPx() }
        val pressedKeys = touches.values.filter { !it.swiping }.mapNotNull { it.key }.toSet()
        for (p in placed) {
            val k = p.key
            val pressed = p in pressedKeys
            // Plain black keys, like the LP3 keyboard; a faint shade only under a finger.
            val fill = if (pressed) colors.content.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent
            Box(
                modifier = Modifier
                    .offset { IntOffset((p.left + gx / 2).toInt(), (p.top + gy / 2 + 3.dp.toPx()).toInt()) }
                    .size(px(p.right - p.left - gx), px(p.bottom - p.top - gy))
                    .clip(RoundedCornerShape(6.dp))
                    .background(fill),
                contentAlignment = Alignment.Center,
            ) {
                when (k.kind) {
                    Kind.Char -> {
                        BasicText(text = caseFor(k.text, shift), style = TextStyle(color = colors.content, fontSize = if (compact) 20.sp else 22.sp, fontFamily = font, textAlign = TextAlign.Center))
                        // The digit you get by holding a top-row key, small in the corner.
                        val hint = ALTERNATES[k.text]?.firstOrNull()?.takeIf { it.isDigit() }
                        if (hint != null) BasicText(
                            text = hint.toString(),
                            style = TextStyle(color = colors.content.copy(alpha = 0.45f), fontSize = 10.sp, fontFamily = font),
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-3).dp, y = 1.dp),
                        )
                    }
                    // LightOS's space bar: a thin line across.
                    Kind.Space -> Box(
                        modifier = Modifier.align(Alignment.Center).offset(y = 4.dp)
                            .size(px((p.right - p.left - gx) * 0.6f), 1.5.dp)
                            .background(colors.content),
                    )
                    Kind.Mode -> BasicText(text = k.label, style = TextStyle(color = colors.content, fontSize = 15.sp, fontFamily = font))
                    // A plain smiley in the key's own colour, like the other key labels.
                    Kind.Emoji -> BasicText(text = "\u263A\uFE0E", style = TextStyle(color = colors.content, fontSize = 22.sp, fontFamily = font))
                    Kind.Shift -> Icon(if (shift == TypingKeyboard.Shift.Off) com.thelightphone.lp3Keyboard.ui.R.drawable.up_lp3 else com.thelightphone.lp3Keyboard.ui.R.drawable.caps_lp3, 20.dp)
                    Kind.Backspace -> Icon(com.thelightphone.lp3Keyboard.ui.R.drawable.back_lp3, 22.dp)
                    Kind.Return -> Icon(com.thelightphone.lp3Keyboard.ui.R.drawable.return_lp3, 22.dp)
                    Kind.Close -> Icon(com.thelightphone.lp3Keyboard.ui.R.drawable.down_lp3, 18.dp)
                }
            }
        }

        // Holding a key: its accents (or digit) in a row above it, slide to pick one.
        for (t in touches.values) {
            val p = t.key ?: continue
            if (t.swiping || p.key.kind != Kind.Char) continue
            val alts = t.alternates
            val keyW = p.right - p.left - gx
            val bubbleH = (p.bottom - p.top) * 1.05f
            if (alts != null) {
                val cell = p.right - p.left
                val left = alternatesLeft(p, alts.size, size.width.toFloat())
                Row(
                    modifier = Modifier
                        .offset { IntOffset(left.toInt(), (p.top - bubbleH + 2.dp.toPx()).toInt()) }
                        .size(px(cell * alts.size), px(bubbleH))
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.background)
                        .background(colors.content.copy(alpha = 0.12f)),
                ) {
                    alts.forEachIndexed { i, a ->
                        Box(
                            modifier = Modifier.size(px(cell), px(bubbleH))
                                .background(if (i == t.choice) colors.content.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(text = caseFor(a, shift), style = TextStyle(color = colors.content, fontSize = 22.sp, fontFamily = font))
                        }
                    }
                }
            }
        }

        // The swipe trail.
        if (trail.size > 1) {
            val color = colors.content.copy(alpha = 0.55f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val path = Path().apply {
                    moveTo(trail[0].x, trail[0].y)
                    for (i in 1 until trail.size) lineTo(trail[i].x, trail[i].y)
                }
                drawPath(path, color, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Where the long-press row starts: over the key, kept on screen. */
private fun alternatesLeft(p: Placed, count: Int, width: Float): Float {
    val cell = p.right - p.left
    return (p.left).coerceAtMost(width - cell * count).coerceAtLeast(0f)
}

private fun caseFor(text: String, shift: TypingKeyboard.Shift): String = when (shift) {
    TypingKeyboard.Shift.Off -> text
    TypingKeyboard.Shift.Once -> text.replaceFirstChar { it.uppercase() }
    TypingKeyboard.Shift.Lock -> text.uppercase()
}

/** A letter was typed: a one-time capital is used up. */
private fun afterChar(keyboard: TypingKeyboard) {
    if (keyboard.shift.value == TypingKeyboard.Shift.Once) keyboard.shift.value = TypingKeyboard.Shift.Off
    keyboard.autoShift()
}

@Composable
private fun Icon(res: Int, size: androidx.compose.ui.unit.Dp) {
    Image(
        painter = painterResource(res),
        contentDescription = null,
        colorFilter = ColorFilter.tint(LightThemeTokens.colors.content),
        modifier = Modifier.size(size),
    )
}

/** The bottom row's height next to a letter row's. */
internal const val BOTTOM_ROW = 0.82f
