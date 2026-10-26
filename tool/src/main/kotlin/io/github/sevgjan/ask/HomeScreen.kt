package io.github.sevgjan.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val store: AskStore) : LightViewModel<Unit>() {
    val hasKey = store.apiKey.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val remaining = store.remainingToday.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val limit = store.limit.stateIn(viewModelScope, SharingStarted.Eagerly, 5)
    val last = store.last.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { Setup.updates.collect { store.applySetupForm() } }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { store.applySetupForm() }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeViewModel>(sealedActivity) {
    private val store = AskStore(lightContext)

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel(store)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val hasKey by viewModel.hasKey.collectAsState()
        val remaining by viewModel.remaining.collectAsState()
        val limit by viewModel.limit.collectAsState()
        val last by viewModel.last.collectAsState()

        LightTheme(colors = themeColors) {
            Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Ask"),
                    rightButton = LightBarButton.LightIcon(icon = LightIcons.SETTINGS, onClick = {
                        navigateTo(screenFactory = { SettingsScreen(it, store) })
                    }),
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )
                when {
                    !hasKey -> Message("Add your Anthropic API key in settings, or from your computer with the Tool Manager.")
                    else -> LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                        LightText(
                            text = if (remaining == 1) "1 question left today" else "$remaining of $limit questions left today",
                            variant = LightTextVariant.Fine,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                        )
                        last?.let { previous ->
                            Column(
                                modifier = Modifier.fillMaxWidth().lightClickable {
                                    navigateTo(screenFactory = { AnswerScreen(it, store, previous.question, previous) })
                                },
                            ) {
                                LightText(text = "Last question", variant = LightTextVariant.Fine, lighten = true)
                                LightText(text = previous.question, variant = LightTextVariant.Copy, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                LightText(
                                    text = previous.answer,
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                                )
                            }
                        }
                    }
                }
                LightBottomBar(
                    listOf(
                        if (hasKey) {
                            LightBarButton.Text(text = if (remaining > 0) "ASK" else "COME BACK TOMORROW", onClick = ::ask)
                        } else {
                            LightBarButton.Text(text = "SET UP", onClick = { navigateTo(screenFactory = { SettingsScreen(it, store) }) })
                        },
                    ),
                )
            }
        }
    }

    private fun ask() {
        if (viewModel.remaining.value <= 0) return
        navigateTo(screenFactory = { TextEditorScreen(it, title = "Your question", submitLabel = "ASK") }) { question ->
            if (question.isNotBlank()) navigateTo(screenFactory = { AnswerScreen(it, store, question.trim(), previous = null) })
        }
    }

    @Composable
    private fun ColumnScope.Message(text: String) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            LightText(
                text = text,
                variant = LightTextVariant.Copy,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
            )
        }
    }
}
