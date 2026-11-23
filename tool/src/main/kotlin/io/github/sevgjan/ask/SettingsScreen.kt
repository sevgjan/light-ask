package io.github.sevgjan.ask

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.textentry.LightTextEditResult
import io.github.sevgjan.ask.engine.ScreenColors
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val LIMITS = listOf(DailyLimit.UNLIMITED, 5, 10, 20, 50)

class SettingsScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    private val work = MainScope()

    override fun onScreenDestroy() = work.cancel()

    @Composable
    override fun Content() {
        val store = Ask.store
        val newModel by remember { store.newChatModel }.collectAsState(initial = Model.OPUS)
        val depth by remember { store.depth }.collectAsState(initial = Depth.QUICK)
        val web by remember { store.webSearch }.collectAsState(initial = true)
        val imageModel by remember { store.imageModel }.collectAsState(initial = ImageModel.FLARE)
        val limit by remember { store.limit }.collectAsState(initial = DailyLimit.UNLIMITED)
        val anthropic by remember { store.apiKey(Provider.ANTHROPIC).map { it != null } }.collectAsState(initial = false)
        val openai by remember { store.apiKey(Provider.OPENAI).map { it != null } }.collectAsState(initial = false)
        val colors by ScreenColors.always.collectAsState()
        var confirmDelete by remember { mutableStateOf(false) }

        Page(title = "Settings", left = back { goBack() }) {
            NoticeLine()
            Section("Answers")
            MenuRow("New chats use", newModel.short) { navigateTo({ NewChatModelScreen(it) }) }
            OptionRow("Thinking", depth.label) {
                work.launch { store.setDepth(if (depth == Depth.QUICK) Depth.DEEP else Depth.QUICK) }
            }
            ToggleRow("Search the web", web, detail = "When a question needs today's news, prices or facts") {
                work.launch { store.setWebSearch(!web) }
            }
            OptionRow("Pictures", imageModel.label) {
                work.launch { store.setImageModel(ImageModel.entries[(imageModel.ordinal + 1) % ImageModel.entries.size]) }
            }
            OptionRow("Questions per day", DailyLimit.label(limit)) {
                work.launch { store.setLimit(LIMITS[(LIMITS.indexOf(limit) + 1).mod(LIMITS.size)]) }
            }

            Section("API keys")
            OptionRow("Anthropic", if (anthropic) "saved" else "not set") { enterKey(Provider.ANTHROPIC) }
            OptionRow("OpenAI", if (openai) "saved" else "not set") { enterKey(Provider.OPENAI) }
            ActionRow("Get keys from a computer", "Plug in the phone, then on the computer: python3 scripts/light-setup.py") {
                Ask.checkComputer(quiet = false)
            }
            Note("Keys are encrypted with a key kept in the phone's Android Keystore. The OpenAI key also draws pictures and turns speech into text, whichever model answers.")

            Section("Screen")
            ToggleRow("Colors", colors, detail = "Ask in colour while it's open") {
                ScreenColors.setAlways(!colors)?.let { Ask.notice.value = it }
            }
            MenuRow("Keyboard") { navigateTo({ KeyboardSettingsScreen(it) }) }

            Section("Chats")
            ActionRow(if (confirmDelete) "Tap again to delete every chat" else "Delete all chats", "Questions, answers and pictures") {
                if (!confirmDelete) confirmDelete = true
                else work.launch { Chats.deleteAll(); confirmDelete = false; Ask.notice.value = "All chats deleted." }
            }
            Note(
                "Questions, photos and chats go to the provider of the chat's model with your key, billed to your account " +
                    "(OpenAI with store: false). Chats are kept only on this phone. Speech goes to OpenAI as you speak, and isn't stored.",
            )
        }
    }

    /** Types or pastes a key on the phone. An empty one removes it. */
    private fun enterKey(provider: Provider) {
        editText(title = provider.keyLabel, text = "", singleLine = true, autoCapitalize = false, password = true, submitLabel = "SAVE") { result ->
            val value = (result as? LightTextEditResult.Submitted)?.text?.trim() ?: return@editText
            work.launch {
                Ask.store.setApiKey(provider, value.ifEmpty { null })
                Ask.notice.value = if (value.isEmpty()) "${provider.label} key removed." else "${provider.label} key saved, encrypted."
            }
        }
    }
}

/** Which model a new chat starts with. */
class NewChatModelScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    private val work = MainScope()

    override fun onScreenDestroy() = work.cancel()

    @Composable
    override fun Content() {
        val current by remember { Ask.store.newChatModel }.collectAsState(initial = Model.OPUS)
        Page(title = "New chats use", left = back { goBack() }) {
            ModelChoices(current) { m -> work.launch { Ask.store.setNewChatModel(m); goBack() } }
            Note("Any chat can switch: tap its title.")
        }
    }
}

/** Everything about Ask's own keyboard (LightsApp's). Android's keyboard settings don't reach it. */
class KeyboardSettingsScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    @Composable
    override fun Content() {
        val compact by KeyboardPrefs.compact.collectAsState()
        val capitals by KeyboardPrefs.capitals.collectAsState()
        val suggestions by KeyboardPrefs.suggestions.collectAsState()
        val swipe by KeyboardPrefs.swipe.collectAsState()
        val doubleSpace by KeyboardPrefs.doubleSpace.collectAsState()
        Page(title = "Keyboard", left = back { goBack() }) {
            Section("Look")
            OptionRow("Size", if (compact) "Compact" else "Comfortable") { KeyboardPrefs.set(KeyboardPrefs.compact, !compact) }
            ToggleRow("Suggestions row", suggestions) { KeyboardPrefs.set(KeyboardPrefs.suggestions, !suggestions) }
            Section("Typing")
            ToggleRow("Capitals", capitals) { KeyboardPrefs.set(KeyboardPrefs.capitals, !capitals) }
            ToggleRow("Double space", doubleSpace, detail = "Types a full stop") { KeyboardPrefs.set(KeyboardPrefs.doubleSpace, !doubleSpace) }
            ToggleRow("Swipe typing", swipe) { KeyboardPrefs.set(KeyboardPrefs.swipe, !swipe) }
            Note(
                "Suggestions and swipe typing learn from the questions you ask. Hold a letter for accents or its digit, hold " +
                    "Backspace to delete word by word, and drag the space bar to move the cursor. The ☺ key opens emoji; comma is " +
                    "on the ?123 page, and holding it types a full stop.",
            )
        }
    }
}
