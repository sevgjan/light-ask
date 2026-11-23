package io.github.sevgjan.ask.engine

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Colour on a phone that's otherwise grayscale: lifts Android's grayscale display filter (the accessibility
 * "daltonizer" set to monochromacy) while Ask asks for it, and always puts it back when Ask leaves the screen.
 * Needs WRITE_SECURE_SETTINGS, granted over adb. The same approach as LightsApp's viewer.
 */
object ScreenColors {
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"
    private const val MONOCHROMACY = 0

    private val prefs get() = Engine.app.getSharedPreferences("ask_colors", Context.MODE_PRIVATE)

    /** True while we've lifted the filter and owe a restore. Persisted, in case the process dies meanwhile. */
    private var lifted: Boolean
        get() = prefs.getBoolean("lifted", false)
        set(value) { prefs.edit().putBoolean("lifted", value).commit() }

    private val alwaysFlow = MutableStateFlow(false)
    /** Settings › Colors: all of Ask in colour while it's on screen (off by default). */
    val always: StateFlow<Boolean> = alwaysFlow

    /** Screens that want colour right now whatever the setting says (a picture with COLORS ON). */
    private val holds = ConcurrentHashMap.newKeySet<String>()
    private val holdingFlow = MutableStateFlow(false)
    /** Whether some screen holds colour at the moment. */
    val holding: StateFlow<Boolean> = holdingFlow

    @Volatile private var foreground = false
    private val main = Handler(Looper.getMainLooper())
    private val leave = Runnable { foreground = false; settle() }

    internal fun attach(app: Application) {
        alwaysFlow.value = runCatching { prefs.getBoolean("always", false) }.getOrDefault(false)
        // A previous run lifted the filter and never put it back (killed in colour): put it back now.
        restore()
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { main.removeCallbacks(leave); foreground = true; settle() }
            // A short grace, so a brief pause (a permission dialog) doesn't flash grayscale.
            override fun onActivityPaused(a: Activity) { main.removeCallbacks(leave); main.postDelayed(leave, 400) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** Turns the Colors setting on or off. Null when done; otherwise why it can't, for the screen. */
    fun setAlways(on: Boolean): String? {
        if (on) unavailableReason()?.let { return it }
        prefs.edit().putBoolean("always", on).apply()
        alwaysFlow.value = on
        settle()
        return null
    }

    /** Asks for colour for [key] while [on], or gives it back. Null when done; otherwise why it can't. */
    fun hold(key: String, on: Boolean): String? {
        if (on) unavailableReason()?.let { return it }
        if (on) holds.add(key) else holds.remove(key)
        holdingFlow.value = holds.isNotEmpty()
        settle()
        return null
    }

    /** Colour when the setting is on (or a screen holds it) and Ask is in front, else back to grayscale. */
    fun settle() {
        if ((alwaysFlow.value || holds.isNotEmpty()) && foreground) setColor(true) else restore()
    }

    private fun canWrite() =
        Engine.app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Whether the screen is grayscale through the filter this can lift. */
    private fun grayscale(): Boolean = runCatching {
        val r = Engine.app.contentResolver
        Settings.Secure.getInt(r, ENABLED, 0) == 1 && Settings.Secure.getInt(r, MODE, MONOCHROMACY) == MONOCHROMACY
    }.getOrDefault(false)

    /** Null when colours can be switched; otherwise why not, for the screen. */
    fun unavailableReason(): String? = when {
        !canWrite() -> "Ask needs permission to switch colours. On a computer: adb shell pm grant " +
            "${Engine.app.packageName} android.permission.WRITE_SECURE_SETTINGS"
        !grayscale() && !lifted -> "The screen isn't grayscale through a setting Ask can change."
        else -> null
    }

    private fun setColor(on: Boolean): Boolean {
        if (!canWrite()) return false
        return runCatching {
            val r = Engine.app.contentResolver
            if (on) {
                if (!grayscale()) return lifted
                Settings.Secure.putInt(r, ENABLED, 0)
                lifted = true
            } else if (lifted) {
                Settings.Secure.putInt(r, MODE, MONOCHROMACY)
                Settings.Secure.putInt(r, ENABLED, 1)
                lifted = false
            }
            true
        }.getOrDefault(false)
    }

    /** Back to how LightOS had it, if we changed anything. */
    private fun restore() {
        if (lifted) setColor(false)
    }
}
