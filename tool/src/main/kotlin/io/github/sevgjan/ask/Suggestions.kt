package io.github.sevgjan.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

private val PARTIAL_WORD = Regex("""\p{L}+(?:['’]\p{L}*)?$""")

/** The word being typed: the letters right before the cursor, and where they start. */
private fun partialWord(keyboard: TypingKeyboard): Pair<Int, String>? {
    val sel = keyboard.state.selection
    if (!sel.collapsed) return null
    val before = keyboard.state.text.subSequence(0, sel.min.coerceIn(0, keyboard.state.text.length))
    val m = PARTIAL_WORD.find(before) ?: return null
    return m.range.first to m.value
}

/**
 * Up to three words for the one being typed: typo fixes and words that finish it, from the same
 * vocabulary swipe typing uses (the user's own chats plus common English). Nothing is changed unless
 * you tap. Tapping one puts it in place of the letters typed so far, with
 * a space after. The row keeps its height when empty, so the keys don't move as you type.
 */
@Composable
internal fun SuggestionRow(keyboard: TypingKeyboard, font: FontFamily) {
    val colors = LightThemeTokens.colors
    val haptics = com.thelightphone.sdk.ui.LocalHapticsEnabled.current
    val words by remember(keyboard) {
        derivedStateOf {
            val p = partialWord(keyboard)?.second
            if (p == null || p.isEmpty()) emptyList() else Vocabulary.suggest(p).map { matchCase(it, p) }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(24.dp).zIndex(2f).background(colors.background),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        words.forEachIndexed { i, w ->
            if (i > 0) Box(modifier = Modifier.height(12.dp).width(1.dp).background(colors.content.copy(alpha = 0.3f)))
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().lightClickable {
                    if (haptics) Ask.keyClick()
                    val (start, _) = partialWord(keyboard) ?: return@lightClickable
                    keyboard.state.edit {
                        val end = selection.min
                        replace(start, end, "$w ")
                        selection = TextRange(start + w.length + 1)
                    }
                    keyboard.autoShift()
                },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = w,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = colors.content, fontSize = 14.sp, fontFamily = font),
                )
            }
        }
    }
}

/** Keeps a capital the user typed: "Tom" + "tomorrow" gives "Tomorrow". */
private fun matchCase(word: String, typed: String): String = when {
    typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
    typed.first().isUpperCase() -> word.replaceFirstChar { it.uppercase() }
    else -> word
}
