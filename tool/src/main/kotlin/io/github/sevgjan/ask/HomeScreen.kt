package io.github.sevgjan.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/** Whether Ask has [permission]: from LightOS, or granted over adb (which only Android itself records). */
suspend fun granted(permission: String): Boolean = io.github.sevgjan.ask.engine.Permissions.granted(permission) ||
    com.thelightphone.sdk.checkPermission(permission).asKotlinResult
        .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
        .getOrDefault(false)

/** The adb command that grants [permission] for good, for a LightOS that won't ask. */
fun grantCommand(permission: String) = "adb shell pm grant ${Ask.PACKAGE} $permission"

/** The chats, newest first. NEW CHAT starts one; SPEAK starts one by voice. */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    init {
        Ask.start(lightContext, sealedActivity.vibrator)
    }

    // Keys waiting on a computer (tools/light-setup.py): picked up whenever the list shows.
    override fun willShow() = Ask.checkComputer(quiet = true)

    @Composable
    override fun Content() {
        val chats by remember { Ask.db.chats() }.collectAsState(initial = null)
        val newest by remember { Ask.db.newest().map { list -> list.associateBy { it.chatId } } }.collectAsState(initial = emptyMap())
        val live by Chats.live.collectAsState()
        val keys by remember {
            Ask.store.apiKey(Provider.ANTHROPIC).combine(Ask.store.apiKey(Provider.OPENAI)) { a, o -> (a != null) to (o != null) }
        }.collectAsState(initial = true to true)
        val limit by Ask.store.limit.collectAsState(initial = DailyLimit.UNLIMITED)
        val remaining by Ask.store.remainingToday.collectAsState(initial = Int.MAX_VALUE)
        val anyKey = keys.first || keys.second
        var revealedChat by remember { mutableStateOf<Long?>(null) }

        Page(
            title = "Ask",
            right = LightBarButton.LightIcon(icon = LightIcons.SETTINGS, onClick = { navigateTo({ SettingsScreen(it) }) }),
            bottom = when {
                !anyKey -> listOf(LightBarButton.Text(text = "SET UP", onClick = { navigateTo({ SettingsScreen(it) }) }))
                keys.second -> listOf(
                    LightBarButton.Text(text = "NEW CHAT", onClick = { navigateTo({ ChatScreen(it, null) }) }),
                    LightBarButton.Text(text = "SPEAK", onClick = { navigateTo({ ChatScreen(it, null, speakNow = true) }) }),
                )
                else -> listOf(LightBarButton.Text(text = "NEW CHAT", onClick = { navigateTo({ ChatScreen(it, null) }) }))
            },
            scroll = false,
        ) {
            NoticeLine()
            if (limit != DailyLimit.UNLIMITED) Note(if (remaining == 1) "1 question left today" else "$remaining of $limit questions left today")
            val list = chats
            when {
                !anyKey -> CenteredNote(
                    "Add an Anthropic or OpenAI API key in settings. Or plug the phone into your computer and run:\n\npython3 scripts/light-setup.py",
                    Modifier.weight(1f),
                )
                list == null -> Unit
                list.isEmpty() -> CenteredNote(
                    "Ask Claude or GPT anything.\n\nSend a photo to ask about it, ask for a picture to have one drawn, or tap SPEAK and talk.",
                    Modifier.weight(1f),
                )
                else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(list, key = { it.id }) { chat ->
                        ChatRow(chat, newest[chat.id], writing = live.containsKey(chat.id), revealed = revealedChat == chat.id) {
                            revealedChat = if (it) chat.id else null
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ChatRow(chat: Chat, last: Message?, writing: Boolean, revealed: Boolean, reveal: (Boolean) -> Unit) {
        val detail = when {
            writing -> "Writing…"
            last == null -> ""
            last.isUser -> "You: " + last.text.ifBlank { "Photo" }
            last.state == Status.ERROR -> "Couldn't answer"
            last.text.isBlank() && last.imageList.isNotEmpty() -> "Picture"
            else -> last.text
        }.replace('\n', ' ')
        val panelWidth = 128.dp
        val extent = with(LocalDensity.current) { panelWidth.toPx() }
        var drag by remember(chat.id) { mutableFloatStateOf(Float.NaN) }
        val resting = if (revealed) -extent else 0f
        val cost = ChatCost(chat.costMicros, chat.costPartial)
        val background = LightThemeTokens.colors.background
        val panelBackground = LightThemeTokens.colors.content.copy(alpha = 0.12f)

        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier.fillMaxWidth().clipToBounds()
                .semantics {
                    customActions = listOf(CustomAccessibilityAction(if (revealed) "Hide cost" else "Show cost") {
                        reveal(!revealed)
                        true
                    })
                }
                .pointerInput(chat.id, extent, revealed) {
                    detectHorizontalDragGestures(
                        onDragStart = { drag = if (revealed) -extent else 0f },
                        onDragCancel = { drag = Float.NaN },
                        onDragEnd = {
                            val threshold = if (revealed) extent * 0.65f else extent * 0.35f
                            reveal(drag < -threshold)
                            drag = Float.NaN
                        },
                    ) { change, delta ->
                        change.consume()
                        drag = (drag + delta).coerceIn(-extent, 0f)
                    }
                },
        ) {
            if (revealed || drag < 0f) {
                Box(Modifier.matchParentSize().background(panelBackground))
                Column(Modifier.width(panelWidth).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LightText(text = cost.amount(), variant = LightTextVariant.Copy, maxLines = 1)
                }
            }
            Column(
                modifier = Modifier.offset { IntOffset((if (drag.isNaN()) resting else drag).roundToInt(), 0) }
                    .fillMaxWidth().background(background)
                    .pointerInput(chat.id, revealed) {
                        detectTapGestures(
                            onTap = { _ ->
                                if (revealed) reveal(false)
                                else navigateTo({ ChatScreen(it, chat.id) })
                            },
                            onLongPress = { Ask.keyClick(); navigateTo({ ChatInfoScreen(it, chat.id) }) },
                        )
                    }
                    .padding(vertical = 0.55f.gridUnitsAsDp()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LightText(text = chat.title, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    LightText(text = shortTime(chat.updated), variant = LightTextVariant.Fine, lighten = true, modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()))
                }
                if (detail.isNotEmpty()) LightText(text = detail, variant = LightTextVariant.Fine, lighten = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
