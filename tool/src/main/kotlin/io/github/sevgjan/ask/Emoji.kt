package io.github.sevgjan.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

/** The emoji, in the order phone keyboards show them: faces, hands, hearts, then the rest. */
private val EMOJI = """
😀 😃 😄 😁 😆 😅 😂 🤣 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 💀 💩 🤡 👻 👽 🤖 🙈 🙉 🙊
👋 🤚 ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💪 👀 🧠 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵 🤷 🤦 🙋 🙇 💃 🕺 👪
❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💯 💢 💥 💫 💦 💨 💬 💤 ✨ 🔥 ⭐ 🌟 ⚡ 🎉 🎊 🎁 🎂 🎈
🐶 🐱 🐭 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🦅 🦉 🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🐙 🐬 🐳 🐟 🌸 🌹 🌻 🌷 🌱 🌲 🌳 🍀 🍁 🌍 🌙 ☀️ ⛅ 🌧️ ❄️ 🌈 🌊
🍎 🍊 🍋 🍌 🍉 🍇 🍓 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🥦 🌽 🥕 🥔 🍞 🧀 🥚 🍳 🥓 🍔 🍟 🍕 🌭 🥪 🌮 🥗 🍝 🍜 🍣 🍰 🧁 🍫 🍬 🍩 🍪 ☕ 🍵 🥤 🍺 🍷 🥂
⚽ 🏀 🏈 🎾 🏐 🎱 🏓 🏸 🥊 🏃 🚶 🚴 🏊 🧘 🎮 🎲 🎯 🎵 🎶 🎸 🎧 🎤 🎬 📷 📱 💻 ⌚ 📞 ✉️ 📦 📚 ✏️ 📌 📎 🔑 🔒 💡 💰 💳 🛒 🏠 🏢 🕌 ⛪ 🚗 🚕 🚌 🚲 ✈️ 🚀 ⏰ ⌛
✅ ❌ ❓ ❗ ⚠️ 🚫 ➕ ➖ ➡️ ⬅️ ⬆️ ⬇️ 🔄 🆗 🆕 🔴 🟢 🔵 ⚪ ⚫ 🏁 🇦🇱 🇽🇰
""".trim().split(Regex("\\s+"))

private const val MAX_RECENT = 16
private const val COLUMNS = 8

/**
 * Emoji in the keyboard's place, with the ones you used last at the top. ABC goes back to the letters;
 * space and backspace stay at the bottom so a message can carry on without switching.
 */
@Composable
internal fun EmojiPanel(keyboard: TypingKeyboard, font: FontFamily) {
    val colors = LightThemeTokens.colors
    val compact by KeyboardPrefs.compact.collectAsState()
    val rowHeight = if (compact) 42.dp else 49.dp
    val haptics = com.thelightphone.sdk.ui.LocalHapticsEnabled.current
    val recent by KeyboardPrefs.recentEmoji.collectAsState()
    fun click() { if (haptics) Ask.keyClick() }

    fun pick(e: String) {
        click()
        keyboard.type(e)
        KeyboardPrefs.setRecent((listOf(e) + recent.filter { it != e }).take(MAX_RECENT))
    }

    // As tall as the letter keyboard (three rows and the shorter bottom row), so the chat doesn't jump.
    Column(
        modifier = Modifier.fillMaxWidth().height(rowHeight * (3 + BOTTOM_ROW) + 6.dp).zIndex(2f).background(colors.background),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 3.dp),
        ) {
            if (recent.isNotEmpty()) {
                item(span = { GridItemSpan(COLUMNS) }) { Label("Recent", font) }
                items(recent, key = { "r$it" }) { Cell(it, ::pick) }
                item(span = { GridItemSpan(COLUMNS) }) { Label("All", font) }
            }
            items(EMOJI, key = { it }) { Cell(it, ::pick) }
        }
        Row(modifier = Modifier.fillMaxWidth().height(rowHeight * BOTTOM_ROW), verticalAlignment = Alignment.CenterVertically) {
            BarKey("ABC", 1.4f, font) { click(); keyboard.page.value = TypingKeyboard.Page.Letters }
            Box(
                modifier = Modifier.weight(6.2f).fillMaxHeight().lightClickable { click(); keyboard.editor.onSpecialKeyPressed(SpecialKey.Space) },
                contentAlignment = Alignment.Center,
            ) {
                // LightOS's space bar: a thin line across.
                Box(modifier = Modifier.fillMaxWidth(0.6f).height(1.5.dp).background(colors.content))
            }
            BarKey("⌫", 2.4f, font) { click(); keyboard.editor.onSpecialKeyReleased(SpecialKey.Backspace) }
        }
    }
}

@Composable
private fun Cell(emoji: String, onPick: (String) -> Unit) {
    Box(modifier = Modifier.size(44.dp).lightClickable { onPick(emoji) }, contentAlignment = Alignment.Center) {
        BasicText(text = emoji, style = TextStyle(fontSize = 26.sp))
    }
}

@Composable
private fun Label(text: String, font: FontFamily) {
    BasicText(
        text = text,
        style = TextStyle(color = LightThemeTokens.colors.content.copy(alpha = 0.55f), fontSize = 12.sp, fontFamily = font),
        modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarKey(label: String, weight: Float, font: FontFamily, onClick: () -> Unit) {
    Box(modifier = Modifier.weight(weight).fillMaxHeight().lightClickable(onClick = onClick), contentAlignment = Alignment.Center) {
        BasicText(text = label, style = TextStyle(color = LightThemeTokens.colors.content, fontSize = 17.sp, fontFamily = font))
    }
}
