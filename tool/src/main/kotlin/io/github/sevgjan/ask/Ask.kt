package io.github.sevgjan.ask

import androidx.datastore.preferences.core.booleanPreferencesKey
import com.thelightphone.sdk.LightVibrator
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * What every screen shares: the chat archive, the settings, and a scope that outlives screens, so a reply keeps
 * being written while you look at something else. Started by the first screen.
 */
object Ask {
    const val PACKAGE = "io.github.sevgjan.ask"

    @Volatile private var started = false
    lateinit var context: SealedLightContext
        private set
    lateinit var store: AskStore
        private set
    lateinit var db: ChatDao
        private set
    lateinit var files: File
        private set
    private var vibrator: LightVibrator? = null

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** A short message for whichever screen is showing (tap it to dismiss). */
    val notice = MutableStateFlow<String?>(null)

    fun start(context: SealedLightContext, vibrator: LightVibrator) {
        if (started) return
        started = true
        this.context = context
        this.vibrator = vibrator
        files = context.filesDir
        store = AskStore(context)
        db = context.buildDatabase(AskDb::class.java, "ask.db") { addMigrations(AskDb.costMigration) }.chats()
        KeyboardPrefs.load(store)
        scope.launch {
            db.markInterruptedCosts()
            db.stopPending()
            moveLegacyAnswer()
            Pictures.tidy(db.allImages().flatMap { it.lines() }.filter { it.isNotBlank() }.toSet())
        }
        scope.launch { Setup.updates.collect { keysArrived(store.applySetupForm()) } }
        scope.launch { keysArrived(store.applySetupForm()) }
    }

    /** Looks for keys waiting on a computer over USB (see [UsbSetup]). Says so on screen when [quiet] is off. */
    fun checkComputer(quiet: Boolean) {
        scope.launch {
            val got = withContext(Dispatchers.IO) {
                runCatching { UsbSetup.check(PACKAGE, ToolEntryPoint.getToolManagerManifest(), context.fileShare) }.getOrDefault(0)
            }
            if (got > 0) keysArrived(store.applySetupForm())
            else if (!quiet) notice.value = "Nothing waiting. Plug the phone into the computer and run: python3 scripts/light-setup.py"
        }
    }

    private fun keysArrived(given: List<Provider>) {
        if (given.isEmpty()) return
        notice.value = "Saved your ${given.joinToString(" and ") { it.label }} key${if (given.size > 1) "s" else ""}, encrypted on the phone."
    }

    /** A short tick for a key press (lighter than LightOS's 45 ms button click, for typing fast). */
    fun keyClick() {
        runCatching { vibrator?.vibrate(18.milliseconds) }
    }

    fun today(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))

    /** Ask 0.1 kept one question and its answer; they become the first chat. */
    private suspend fun moveLegacyAnswer() {
        val old = store.takeLegacy() ?: return
        if (db.chatCount() > 0) return
        val now = System.currentTimeMillis()
        val model = store.newChatModel.first()
        val id = db.insert(Chat(title = Chats.titleFor(old.question), model = model.name, created = now, updated = now))
        db.insert(Message(chatId = id, role = Message.USER, text = old.question, created = now))
        db.insert(Message(chatId = id, role = Message.ASSISTANT, text = old.answer, model = model.name, created = now))
        if (old.followUp != null) {
            db.insert(Message(chatId = id, role = Message.USER, text = old.followUp, created = now))
            db.insert(Message(chatId = id, role = Message.ASSISTANT, text = old.followUpAnswer.orEmpty(), model = model.name, created = now))
        }
    }
}

/** The keyboard's switches (Settings › Keyboard), kept in memory for the keys to read as you type. */
object KeyboardPrefs {
    /** Shorter rows, for more of the chat on screen while typing. */
    val compact = MutableStateFlow(false)
    /** A capital at the start of a message and after . ! ? (on by default). */
    val capitals = MutableStateFlow(true)
    /** A row of word completions over the keys (off by default). */
    val suggestions = MutableStateFlow(false)
    /** Swipe across the letters to type a word (on by default). */
    val swipe = MutableStateFlow(true)
    /** Two spaces after a word type ". " (on by default). */
    val doubleSpace = MutableStateFlow(true)
    /** The emoji used last, newest first. */
    val recentEmoji = MutableStateFlow<List<String>>(emptyList())

    private val switches = mapOf("compact" to compact, "capitals" to capitals, "suggestions" to suggestions, "swipe" to swipe, "double_space" to doubleSpace)

    internal fun load(store: AskStore) {
        Ask.scope.launch {
            val p = store.keyboard.first()
            switches.forEach { (name, flow) -> p[booleanPreferencesKey("keyboard_$name")]?.let { flow.value = it } }
            recentEmoji.value = p[AskStore.RECENT_EMOJI]?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
        }
    }

    fun set(flow: MutableStateFlow<Boolean>, on: Boolean) {
        flow.value = on
        val name = switches.entries.firstOrNull { it.value === flow }?.key ?: return
        Ask.scope.launch { Ask.store.setKeyboard(name, on) }
    }

    fun setRecent(list: List<String>) {
        recentEmoji.value = list
        Ask.scope.launch { Ask.store.setRecentEmoji(list) }
    }
}
