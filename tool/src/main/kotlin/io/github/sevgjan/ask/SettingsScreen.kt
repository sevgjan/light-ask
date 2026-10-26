package io.github.sevgjan.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
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

private val LIMITS = listOf(1, 3, 5, 10, 20)

class SettingsViewModel(private val store: AskStore) : LightViewModel<Unit>() {
    val hasKey = store.apiKey.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val model = store.model.stateIn(viewModelScope, SharingStarted.Eagerly, ClaudeModel.OPUS)
    val limit = store.limit.stateIn(viewModelScope, SharingStarted.Eagerly, 5)
    val baseUrl = store.baseUrl.stateIn(viewModelScope, SharingStarted.Eagerly, ClaudeApi.DEFAULT_BASE_URL)

    init {
        viewModelScope.launch { Setup.updates.collect { store.applySetupForm() } }
    }

    fun setKey(value: String) = viewModelScope.launch { store.setApiKey(value) }
    fun setBaseUrl(value: String) = viewModelScope.launch { store.setBaseUrl(value) }
    fun nextModel() = viewModelScope.launch {
        store.setModel(ClaudeModel.entries[(model.value.ordinal + 1) % ClaudeModel.entries.size])
    }
    fun nextLimit() = viewModelScope.launch {
        store.setLimit(LIMITS[(LIMITS.indexOf(limit.value) + 1).mod(LIMITS.size)])
    }
}

class SettingsScreen(sealedActivity: SealedLightActivity, private val store: AskStore) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel() = SettingsViewModel(store)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val hasKey by viewModel.hasKey.collectAsState()
        val model by viewModel.model.collectAsState()
        val limit by viewModel.limit.collectAsState()
        val baseUrl by viewModel.baseUrl.collectAsState()

        LightTheme(colors = themeColors) {
            Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Settings"),
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )
                LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                    Field("Anthropic API key", if (hasKey) "saved, encrypted" else "not set") {
                        navigateTo(screenFactory = { TextEditorScreen(it, "API key") }) { viewModel.setKey(it) }
                    }
                    Field("Model", model.label, viewModel::nextModel)
                    Field("Questions per day", limit.toString(), viewModel::nextLimit)
                    Field("API address", baseUrl) {
                        navigateTo(screenFactory = { TextEditorScreen(it, "API address (blank for default)", baseUrl) }) {
                            viewModel.setBaseUrl(it)
                        }
                    }
                    LightText(
                        text = "Questions go to Anthropic with your key and are billed to your account. " +
                            "Only the last question and answer are kept on the phone.",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }

    @Composable
    private fun Field(label: String, value: String, onClick: () -> Unit) {
        Column(modifier = Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(vertical = 0.4f.gridUnitsAsDp())) {
            LightText(text = label, variant = LightTextVariant.Fine, lighten = true)
            LightText(text = value, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
