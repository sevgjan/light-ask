package io.github.sevgjan.ask

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A LightOS screen: top bar, content, optional bottom bar. The same layout as LightsApp's. */
@Composable
fun Page(
    title: String,
    subtitle: String? = null,
    left: LightBarButton? = null,
    right: LightBarButton? = null,
    onTitleClick: (() -> Unit)? = null,
    bottom: List<LightBarButton?>? = null,
    scroll: Boolean = true,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Themed {
        Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
            LightTopBar(
                leftButton = left,
                center = if (subtitle != null) LightTopBarCenter.TwoLineDetail(title, subtitle, onTitleClick) else LightTopBarCenter.Text(title, onTitleClick),
                rightButton = right,
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            val body = Modifier.weight(1f).fillMaxWidth().let { if (padded) it.padding(horizontal = 1f.gridUnitsAsDp()) else it }
            if (scroll) LightScrollView(modifier = body, content = content, scrollBarPosition = LightScrollBarPosition.Outside)
            else Column(modifier = body, content = content)
            if (bottom != null) LightBottomBar(items = bottom)
        }
    }
}

@Composable
fun Themed(content: @Composable () -> Unit) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors) { content() }
}

fun back(onClick: () -> Unit) = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onClick)

/** The notice everyone shares ([Ask.notice]): tap to dismiss, or it goes by itself after a while. */
@Composable
fun NoticeLine(modifier: Modifier = Modifier) {
    val notice by Ask.notice.collectAsState()
    androidx.compose.runtime.LaunchedEffect(notice) {
        val shown = notice ?: return@LaunchedEffect
        // Long enough to read (adb commands stay longer, to copy them down).
        kotlinx.coroutines.delay(if ("adb " in shown) 30_000 else 8_000)
        if (Ask.notice.value == shown) Ask.notice.value = null
    }
    notice?.let { Note(it, modifier.lightClickable { Ask.notice.value = null }) }
}

@Composable
fun CenteredNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LightText(text = text, variant = LightTextVariant.Copy, lighten = true, align = TextAlign.Center, modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()))
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    LightText(text = text, variant = LightTextVariant.Fine, lighten = true, modifier = modifier.padding(vertical = 0.3f.gridUnitsAsDp()))
}

@Composable
fun Section(title: String) {
    LightText(text = title, variant = LightTextVariant.Fine, lighten = true, modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()))
}

/** A tappable line. */
@Composable
fun ActionRow(text: String, detail: String? = null, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(vertical = 0.45f.gridUnitsAsDp())) {
        LightText(text = text, variant = LightTextVariant.Copy)
        detail?.let { LightText(text = it, variant = LightTextVariant.Fine, lighten = true) }
    }
}

/** A settings section to open: its name, an optional short status, and a chevron. */
@Composable
fun MenuRow(label: String, status: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(vertical = 0.6f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        status?.let { LightText(text = it, variant = LightTextVariant.Fine, lighten = true, maxLines = 1, modifier = Modifier.padding(horizontal = 0.4f.gridUnitsAsDp())) }
        LightText(text = "›", variant = LightTextVariant.Copy, lighten = true)
    }
}

/** One setting on one line: its name, and its value on the right. Tap to change it. */
@Composable
fun OptionRow(label: String, value: String = "", onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.lightClickable(onClick = onClick) else it }.padding(vertical = 0.6f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (value.isNotEmpty()) LightText(text = value, variant = LightTextVariant.Copy, lighten = true, maxLines = 1, modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()))
    }
}

/** An on/off setting, as LightOS draws one (Airplane mode, Bluetooth): its toggle, then its name. */
@Composable
fun ToggleRow(label: String, on: Boolean, detail: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(vertical = 0.45f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(if (on) com.thelightphone.sdk.ui.R.drawable.ic_toggle_state_on_white else com.thelightphone.sdk.ui.R.drawable.ic_toggle_state_off_white),
            contentDescription = label + if (on) " on" else " off",
            colorFilter = ColorFilter.tint(LightThemeTokens.colors.content),
        )
        Column(modifier = Modifier.padding(start = 1f.gridUnitsAsDp()).weight(1f)) {
            LightText(text = label, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { LightText(text = it, variant = LightTextVariant.Fine, lighten = true) }
        }
    }
}

/** One of several choices: a tick on the chosen one. */
@Composable
fun ChoiceRow(label: String, chosen: Boolean, detail: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(vertical = 0.5f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(text = label, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { LightText(text = it, variant = LightTextVariant.Fine, lighten = true) }
        }
        if (chosen) LightText(text = "✓", variant = LightTextVariant.Copy, modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()))
    }
}

/** An icon that does something, at the size of the composer's icons. */
@Composable
fun IconButton(icon: LightIconConfiguration, size: Float = 1.8f, modifier: Modifier = Modifier, onClick: () -> Unit) {
    LightIcon(icon = icon, size = size, modifier = modifier.lightClickable(onClick = onClick))
}

@Composable
fun Gap() = Box(modifier = Modifier.height(0.8f.gridUnitsAsDp()))

/** A thin rule between sections. */
@Composable
fun Rule(modifier: Modifier = Modifier) =
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(LightThemeTokens.colors.content.copy(alpha = 0.15f)))

/** "14:05" today, "Tue" this week, "3 Nov" this year, "3 Nov 2025" before. */
fun shortTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return ""
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = then.get(Calendar.YEAR) == today.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val pattern = when {
        sameDay -> "HH:mm"
        now - ms < 6L * 86_400_000 && ms < now -> "EEE"
        then.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "d MMM"
        else -> "d MMM yyyy"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}
