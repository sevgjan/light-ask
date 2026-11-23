package io.github.sevgjan.ask.engine

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Takes one photo with the main camera, to ask about. Same approach as LightsApp's. */
object Shooter {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var sensor = 90

    @SuppressLint("MissingPermission") // asked through LightOS first
    internal fun open(preview: Surface) {
        close()
        val t = HandlerThread("ask-camera").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post {
            runCatching {
                val cm = Engine.app.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: cm.cameraIdList.first()
                val chars = cm.getCameraCharacteristics(id)
                sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.JPEG)
                val size = sizes.filter { maxOf(it.width, it.height) <= 2048 }.maxByOrNull { it.width * it.height } ?: sizes.first()
                val r = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
                reader = r
                cm.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        device = d
                        @Suppress("DEPRECATION")
                        d.createCaptureSession(listOf(preview, r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                runCatching {
                                    s.setRepeatingRequest(d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(preview) }.build(), null, h)
                                }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) = Unit
                        }, h)
                    }
                    override fun onDisconnected(d: CameraDevice) { d.close() }
                    override fun onError(d: CameraDevice, error: Int) { d.close() }
                }, h)
            }
        }
    }

    /** The JPEG bytes of one photo, or null if the camera wasn't ready. */
    suspend fun shoot(): ByteArray? = suspendCancellableCoroutine { cont ->
        val d = device
        val s = session
        val r = reader
        val h = handler
        if (d == null || s == null || r == null || h == null) { cont.resume(null); return@suspendCancellableCoroutine }
        r.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage()
            val bytes = runCatching {
                val buffer = image.planes[0].buffer
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            }.getOrNull()
            image.close()
            if (cont.isActive) cont.resume(bytes)
        }, h)
        runCatching {
            val request = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface)
                set(CaptureRequest.JPEG_ORIENTATION, sensor)
            }.build()
            s.capture(request, null, h)
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    fun close() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null; device = null; reader = null
        thread?.quitSafely()
        thread = null; handler = null
    }
}

/** The camera's live picture while taking a photo. */
@Composable
fun CameraPreview(modifier: Modifier = Modifier) {
    DisposableEffect(Unit) { onDispose { Shooter.close() } }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        st.setDefaultBufferSize(1280, 960)
                        Shooter.open(Surface(st))
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { Shooter.close(); return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
    )
}
