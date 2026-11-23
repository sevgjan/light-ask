package io.github.sevgjan.ask.engine

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The application context, handed over by [EngineInit] when the process starts. */
internal object Engine {
    @Volatile private var context: Context? = null
    val app: Context get() = context ?: error("Ask's engine isn't attached yet")

    fun attach(c: Context) {
        if (context != null) return
        val application = c.applicationContext as Application
        context = application
        ScreenColors.attach(application)
    }
}

/** Starts the engine when the process starts, before any screen. */
class EngineInit : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(Engine::attach)
        return true
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/**
 * Android's own record of a permission. LightOS keeps its own as well (what the SDK's checkPermission asks), and
 * a grant over adb (`pm grant`) only reaches Android's, so Ask asks both.
 */
object Permissions {
    fun granted(permission: String): Boolean =
        runCatching { Engine.app.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
}

/** The phone's photo library. */
object Gallery {
    /**
     * Adds a picture to the phone's gallery under Pictures/Ask, like a camera app would. [name] is the file name
     * shown there. Returns whether it worked.
     */
    suspend fun save(file: File, mime: String, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Ask")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = Engine.app.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return@runCatching false
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        }.getOrDefault(false)
    }
}

/** Web pages, for the sources under an answer. */
object Links {
    /** LightOS hands https links to its own "scan this QR code" screen, so ask Chromium first. */
    private const val CHROMIUM = "org.chromium.chrome"

    fun open(url: String): Boolean {
        val fixed = if (url.contains("://")) url else "https://$url"
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(fixed)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { Engine.app.startActivity(Intent(view).setPackage(CHROMIUM)); true }.getOrElse {
            runCatching { Engine.app.startActivity(view); true }.getOrDefault(false)
        }
    }
}
