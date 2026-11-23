package io.github.sevgjan.ask

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.LightMedia
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.sevgjan.ask.engine.CameraPreview
import io.github.sevgjan.ask.engine.Gallery
import io.github.sevgjan.ask.engine.ScreenColors
import io.github.sevgjan.ask.engine.Shooter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A chat's pictures, full screen: pinch to zoom, swipe for the next one, tap to hide the bars. COLORS ON shows it in
 * colour on the grayscale phone while it's open; SAVE puts it in the phone's gallery.
 */
class ViewerScreen(sealedActivity: SealedLightActivity, private val chatId: Long, private val first: String) : SimpleLightScreen<Unit>(sealedActivity) {
    private val work = MainScope()
    private val colorOverride = MutableStateFlow<Boolean?>(null)
    private val bare = MutableStateFlow(false)

    // Never leave the phone in colour: back to grayscale (or the Colors setting) whenever this isn't on screen.
    override fun willHide() {
        ScreenColors.hold(HOLD, false)
        colorOverride.value = null
    }

    override fun onScreenDestroy() {
        ScreenColors.hold(HOLD, false)
        work.cancel()
    }

    @Composable
    override fun Content() {
        val all by produceState<List<String>?>(null, chatId) {
            value = Ask.db.messagesNow(chatId).flatMap { it.imageList }.ifEmpty { listOf(first) }
        }
        val names = all ?: return Themed { Box(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) }
        val pager = rememberPagerState(initialPage = names.indexOf(first).coerceAtLeast(0)) { names.size }
        val override by colorOverride.collectAsState()
        val always by ScreenColors.always.collectAsState()
        val color = override ?: always
        val grayscale = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
        val hidden by bare.collectAsState()
        var zoomed by remember { mutableStateOf(false) }
        val current = names.getOrElse(pager.currentPage) { first }

        Themed {
            Box(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                HorizontalPager(state = pager, userScrollEnabled = !zoomed, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
                    val name = names[page]
                    val bitmap by produceState<ImageBitmap?>(null, name) { value = Pictures.bitmap(name, 2400) }
                    Zoomable(onTap = { bare.value = !bare.value }, onZoom = { if (page == pager.currentPage) zoomed = it }) {
                        bitmap?.let {
                            Image(
                                bitmap = it,
                                contentDescription = "Picture",
                                contentScale = ContentScale.Fit,
                                colorFilter = grayscale.takeIf { override == false },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                AnimatedVisibility(visible = !hidden, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                    LightTopBar(
                        leftButton = back { goBack() },
                        center = LightTopBarCenter.Text(if (names.size > 1) "${pager.currentPage + 1} of ${names.size}" else "Picture"),
                        modifier = Modifier.background(LightThemeTokens.colors.background),
                    )
                }
                AnimatedVisibility(visible = !hidden, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                    Box(modifier = Modifier.background(LightThemeTokens.colors.background)) {
                        LightBottomBar(
                            listOf(
                                LightBarButton.Text(text = if (color) "COLORS ON" else "COLORS OFF", onClick = {
                                    val want = !color
                                    val reason = ScreenColors.hold(HOLD, want && !always)
                                    if (reason != null) Ask.notice.value = reason else colorOverride.value = want
                                }),
                                LightBarButton.Text(text = "SAVE", onClick = {
                                    work.launch {
                                        val ok = Gallery.save(Pictures.file(current), Pictures.mediaType(current), "Ask-$current")
                                        Ask.notice.value = if (ok) "Saved to Pictures/Ask." else "Couldn't save it."
                                        goBack()
                                    }
                                }),
                            ),
                        )
                    }
                }
                // Over a picture a notice needs a backing of its own to be read.
                val notice by Ask.notice.collectAsState()
                if (notice != null) Box(
                    Modifier.align(Alignment.Center).padding(horizontal = 1f.gridUnitsAsDp())
                        .background(LightThemeTokens.colors.background.copy(alpha = 0.92f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .padding(horizontal = 0.8f.gridUnitsAsDp(), vertical = 0.4f.gridUnitsAsDp()),
                ) { NoticeLine() }
            }
        }
    }

    private companion object {
        const val HOLD = "viewer"
    }
}

/**
 * Pinch to zoom (up to 5x), drag to move around while zoomed, double tap to go back to fit, tap for [onTap].
 * At normal size a one-finger drag is left alone, so the pager can swipe. LightsApp's.
 */
@Composable
internal fun Zoomable(onTap: () -> Unit = {}, onZoom: (Boolean) -> Unit = {}, content: @Composable () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale > 1f) { onZoom(scale > 1f) }
    Box(
        modifier = Modifier.fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { scale = 1f; offset = Offset.Zero },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            val pan = event.calculatePan()
                            // Keep the picture covering the view: don't pan past its edges.
                            val maxX = size.width * (scale - 1f) / 2f
                            val maxY = size.height * (scale - 1f) / 2f
                            offset = if (scale == 1f) Offset.Zero else Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    ) { content() }
}

/** Pick a photo to ask about: the newest on the phone, or a new one with the camera. Returns its name in Ask's folder. */
class AttachScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<String>(sealedActivity) {
    /** Outlives this screen's composition: the camera screen replaces it while you shoot. */
    private val work = MainScope()
    private val busy = MutableStateFlow(false)

    override fun onScreenDestroy() = work.cancel()

    @Composable
    override fun Content() {
        val photos = rememberPermissionRequestLauncher(Manifest.permission.READ_MEDIA_IMAGES)
        val camera = rememberPermissionRequestLauncher(Manifest.permission.CAMERA)
        val preparing by busy.collectAsState()
        var allowed by remember { mutableStateOf<Boolean?>(null) }
        var items by remember { mutableStateOf<List<LightMedia.Item.Image>?>(null) }
        LaunchedEffect(Unit) {
            // Not asked for straight away: some LightOS builds show a permission screen that won't close. Look again
            // every second for a few minutes, in case it's allowed (by ALLOW below, or over adb) meanwhile.
            var tries = 0
            while (!granted(Manifest.permission.READ_MEDIA_IMAGES)) {
                allowed = false
                if (++tries > 300) return@LaunchedEffect
                delay(1000)
            }
            allowed = true
            items = withContext(Dispatchers.IO) { runCatching { lightContext.media.loadImages(90).items }.getOrDefault(emptyList()) }
        }
        Page(
            title = if (preparing) "Preparing…" else "Add a photo",
            left = back { goBack() },
            right = LightBarButton.LightIcon(icon = LightIcons.CAMERA, onClick = {
                work.launch {
                    if (!granted(Manifest.permission.CAMERA)) {
                        Ask.notice.value = "The camera isn't allowed yet. Allow it when LightOS asks, or run on a computer: " +
                            grantCommand(Manifest.permission.CAMERA)
                        camera?.launch()
                        return@launch
                    }
                    navigateTo({ CaptureScreen(it) }) { name -> goBack(name) }
                }
            }),
            bottom = if (allowed == false) listOf(null, LightBarButton.Text(text = "ALLOW", onClick = { photos?.launch() }), null) else null,
            scroll = false,
        ) {
            NoticeLine()
            val list = items
            when {
                allowed == false -> CenteredNote(
                    "To send a photo, Ask needs to see your photos. Tap ALLOW, or run this once on a computer:\n\n" +
                        grantCommand(Manifest.permission.READ_MEDIA_IMAGES).replace(" android.permission.", "\nandroid.permission."),
                )
                list == null -> Unit
                list.isEmpty() -> CenteredNote("No photos on this phone yet. Take one with the camera icon.")
                else -> LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f)) {
                    items(list, key = { it.id }) { item ->
                        val thumb by produceState<ImageBitmap?>(null, item.id) { value = Pictures.libraryThumb(lightContext.media, item) }
                        Box(
                            modifier = Modifier.padding(0.1f.gridUnitsAsDp()).aspectRatio(1f)
                                .background(LightThemeTokens.colors.content.copy(alpha = 0.06f))
                                .lightClickable {
                                    if (busy.value) return@lightClickable
                                    busy.value = true
                                    work.launch {
                                        val name = Pictures.addFromLibrary(lightContext.media, item)
                                        busy.value = false
                                        if (name == null) Ask.notice.value = "Can't read this photo." else goBack(name)
                                    }
                                },
                        ) {
                            thumb?.let { Image(bitmap = it, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                    }
                }
            }
        }
    }
}

/** The camera, to take one photo to ask about. Returns the photo's name in Ask's folder. */
class CaptureScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<String>(sealedActivity) {
    private val work = MainScope()
    private val busy = MutableStateFlow(false)

    override fun onScreenDestroy() = work.cancel()

    @Composable
    override fun Content() {
        val saving by busy.collectAsState()
        Page(
            title = if (saving) "Saving…" else "Photo",
            left = back { goBack() },
            bottom = listOf(null, LightBarButton.Text(text = "TAKE PHOTO", onClick = ::shoot), null),
            scroll = false,
            padded = false,
        ) {
            NoticeLine(Modifier.padding(horizontal = 1f.gridUnitsAsDp()))
            // The camera's frames are 4:3 (3:4 held upright): keep that shape so the preview isn't squashed.
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CameraPreview(Modifier.fillMaxHeight().aspectRatio(3f / 4f, matchHeightConstraintsFirst = true))
            }
        }
    }

    private fun shoot() {
        if (busy.value) return
        busy.value = true
        work.launch {
            val name = Shooter.shoot()?.let { Pictures.addPhoto(it) }
            busy.value = false
            if (name != null) goBack(name) else Ask.notice.value = "The photo wasn't taken."
        }
    }
}
