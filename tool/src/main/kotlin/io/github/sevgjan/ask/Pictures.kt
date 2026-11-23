package io.github.sevgjan.ask

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.thelightphone.sdk.LightMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Ask's pictures folder: photos the user attached (turned upright and made small enough to send) and pictures the
 * models made (kept as they came, with a smaller copy to send back when the chat goes on).
 */
object Pictures {
    /** Claude's advice: more than this on the long side only costs time; it's scaled down anyway. */
    private const val SEND_SIDE = 1568
    private const val CONTEXT_SIDE = 1024

    private val dir: File get() = File(Ask.files, "pictures").apply { mkdirs() }
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun file(name: String) = File(dir, name)

    fun mediaType(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    /** What goes to the model for [name]: a made picture's smaller copy if there is one, else the file itself. */
    fun forModel(name: String): Picture {
        val context = file(contextName(name))
        val use = if (context.exists()) contextName(name) else name
        return Picture(mediaType(use)) { runCatching { file(use).readBytes() }.getOrNull() }
    }

    private fun contextName(name: String) = name.substringBeforeLast('.') + ".ctx.jpg"

    /** A photo to send, from its original bytes. Returns its file name, or null if it isn't a picture. */
    suspend fun addPhoto(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val bitmap = upright(bytes, SEND_SIDE) ?: return@withContext null
        val name = "photo-${UUID.randomUUID()}.jpg"
        file(name).writeBytes(jpeg(bitmap, 88))
        bitmap.recycle()
        name
    }

    /** A picture a model made, stored as it came. Returns its file name. */
    suspend fun addMade(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val ext = when {
            bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
            bytes.size > 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "webp"
            bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            else -> return@withContext null
        }
        val name = "made-${UUID.randomUUID()}.$ext"
        file(name).writeBytes(bytes)
        upright(bytes, CONTEXT_SIDE)?.let {
            file(contextName(name)).writeBytes(jpeg(it, 85))
            it.recycle()
        }
        name
    }

    /** A picture from the phone's photo library, ready to send. */
    suspend fun addFromLibrary(media: LightMedia, item: LightMedia.Item.Image): String? {
        val bytes = media.readItem(item) { it.readBytes() } ?: return null
        return addPhoto(bytes)
    }

    /** A small square-ish preview of a library photo, for the picker. */
    suspend fun libraryThumb(media: LightMedia, item: LightMedia.Item.Image, side: Int = 240): ImageBitmap? {
        val key = "lib:${item.id}@$side"
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        media.readItem(item) { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, side) }
        val bitmap = media.readItem(item) { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return bitmap.asImageBitmap().also { cache.put(key, it) }
    }

    /** One of Ask's pictures, at most [maxSide] on its long side, for the screen. */
    suspend fun bitmap(name: String, maxSide: Int): ImageBitmap? {
        val key = "$name@$maxSide"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = file(name)
            if (!f.exists()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            val options = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, maxSide) }
            BitmapFactory.decodeFile(f.path, options)?.asImageBitmap()?.also { cache.put(key, it) }
        }
    }

    /** A picture still being drawn, from its bytes. */
    fun preview(bytes: ByteArray, maxSide: Int = 720): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, maxSide) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }.getOrNull()

    /** Removes picture files no message uses any more. */
    suspend fun tidy(used: Set<String>) = withContext(Dispatchers.IO) {
        val keep = used + used.map(::contextName)
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    private fun upright(bytes: ByteArray, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, maxSide) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val degrees = runCatching {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val scale = (maxSide.toFloat() / maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
        if (degrees == 0f && scale == 1f) return decoded
        val matrix = Matrix().apply { postScale(scale, scale); postRotate(degrees) }
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }.getOrNull()

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** The largest power-of-two step that keeps the picture at least [maxSide] on its long side. */
    private fun sample(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }
}
