package com.mdblisthub.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens
import com.mdblisthub.tv.ui.component.HubButton
import com.mdblisthub.tv.ui.hubViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

val ALL_LANGUAGES = listOf(
    "pt" to "Português (Brasil)",
    "pt-pt" to "Português (Portugal)",
    "en" to "English",
    "es" to "Español",
    "fr" to "Français",
    "it" to "Italiano",
    "de" to "Deutsch",
    "ru" to "Русский",
    "ja" to "日本語",
    "ko" to "한국어",
    "zh" to "中文",
    "ar" to "العربية",
    "hi" to "हिन्दी",
    "tr" to "Türkçe",
    "pl" to "Polski",
    "nl" to "Nederlands"
)

data class SettingsUiState(
    val language: String = "pt",
    val subtitleAutoDownload: Boolean = true,
    val subtitleLanguage: String = "pt",
    val subtitleColor: String = "white",
    val audioLanguage: String = "en",
)

class SettingsViewModel(private val graph: DataGraph) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                graph.uiPreferences.language,
                graph.uiPreferences.subtitleAutoDownload,
                graph.uiPreferences.subtitleLanguage,
                graph.uiPreferences.subtitleColor,
                graph.uiPreferences.audioLanguage,
            ) { lang, subAuto, subLang, subColor, audioLang ->
                SettingsUiState(lang, subAuto, subLang, subColor, audioLang)
            }.collect {
                _state.value = it
            }
        }
    }

    fun setLanguage(lang: String) = viewModelScope.launch { graph.uiPreferences.saveLanguage(lang) }
    fun toggleSubtitleAutoDownload() = viewModelScope.launch { graph.uiPreferences.saveSubtitleAutoDownload(!_state.value.subtitleAutoDownload) }
    fun setSubtitleLanguage(lang: String) = viewModelScope.launch { graph.uiPreferences.saveSubtitleLanguage(lang) }
    fun setSubtitleColor(color: String) = viewModelScope.launch { graph.uiPreferences.saveSubtitleColor(color) }
    fun setAudioLanguage(lang: String) = viewModelScope.launch { graph.uiPreferences.saveAudioLanguage(lang) }
}

@Composable
fun SettingsScreen(graph: DataGraph, onBack: () -> Unit) {
    val viewModel = hubViewModel { SettingsViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    var subtitlePickerOpen by remember { mutableStateOf(false) }
    var audioPickerOpen by remember { mutableStateOf(false) }

    BackHandler {
        when {
            subtitlePickerOpen -> subtitlePickerOpen = false
            audioPickerOpen -> audioPickerOpen = false
            else -> onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        // Pinned outside the LazyColumn on purpose: the list's initial focus
        // lands on the first focusable card below, and Compose's focus-driven
        // scroll-into-view then shifts the whole list up to seat that card
        // near the top — taking this non-focusable title with it, off the
        // top of the screen, before the user ever presses a key. A fixed
        // header can't be scrolled away by a focus change it isn't part of.
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.displayLarge,
            color = HubColors.Text,
            modifier = Modifier
                .padding(horizontal = HubDimens.ScreenPaddingHorizontal)
                .padding(top = HubDimens.ScreenPaddingVertical * 2, bottom = 18.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = HubDimens.ScreenPaddingHorizontal,
                end = HubDimens.ScreenPaddingHorizontal,
                bottom = HubDimens.ScreenPaddingVertical,
            ),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
        item(key = "interface") {
            SettingsCard(title = stringResource(R.string.settings_section_interface)) {
                SettingsRow(label = stringResource(R.string.settings_language)) {
                    val langs = listOf("pt" to stringResource(R.string.lang_pt), "en" to stringResource(R.string.lang_en))
                    langs.forEach { (code, name) ->
                        HubButton(
                            text = name,
                            primary = state.language == code,
                            onClick = { viewModel.setLanguage(code) }
                        )
                    }
                }
            }
        }

        item(key = "subtitles") {
            SettingsCard(title = stringResource(R.string.settings_section_subtitles)) {
                SettingsRow(label = stringResource(R.string.settings_subtitle_auto_download)) {
                    HubButton(
                        text = stringResource(
                            if (state.subtitleAutoDownload) R.string.settings_on else R.string.settings_off,
                        ),
                        primary = state.subtitleAutoDownload,
                        onClick = viewModel::toggleSubtitleAutoDownload
                    )
                }
                
                SettingsRow(label = stringResource(R.string.settings_subtitle_default_lang)) {
                    val currentName = ALL_LANGUAGES.find { it.first == state.subtitleLanguage }?.second ?: state.subtitleLanguage
                    HubButton(
                        text = currentName,
                        primary = true,
                        onClick = { subtitlePickerOpen = true }
                    )
                }

                SettingsRow(label = stringResource(R.string.settings_subtitle_color)) {
                    val colors = listOf(
                        "yellow" to stringResource(R.string.color_yellow),
                        "white" to stringResource(R.string.color_white),
                        "red" to stringResource(R.string.color_red),
                        "blue" to stringResource(R.string.color_blue)
                    )
                    colors.forEach { (code, name) ->
                        HubButton(
                            text = name,
                            primary = state.subtitleColor == code,
                            onClick = { viewModel.setSubtitleColor(code) }
                        )
                    }
                }
            }
        }

        item(key = "player") {
            SettingsCard(title = stringResource(R.string.settings_section_player)) {
                SettingsRow(label = stringResource(R.string.settings_audio_preferred_lang)) {
                    val currentName = ALL_LANGUAGES.find { it.first == state.audioLanguage }?.second ?: state.audioLanguage
                    HubButton(
                        text = currentName,
                        primary = true,
                        onClick = { audioPickerOpen = true }
                    )
                }
            }
        }

        item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (subtitlePickerOpen) {
        LanguagePickerOverlay(
            title = stringResource(R.string.settings_subtitle_default_lang),
            languages = ALL_LANGUAGES,
            selectedCode = state.subtitleLanguage,
            onSelect = { viewModel.setSubtitleLanguage(it); subtitlePickerOpen = false },
        )
    }

    if (audioPickerOpen) {
        LanguagePickerOverlay(
            title = stringResource(R.string.settings_audio_preferred_lang),
            languages = ALL_LANGUAGES,
            selectedCode = state.audioLanguage,
            onSelect = { viewModel.setAudioLanguage(it); audioPickerOpen = false },
        )
    }
    }
}


@Composable
private fun SettingsCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(HubColors.Surface.copy(alpha = 0.65f))
            .border(1.dp, HubColors.Border, RoundedCornerShape(14.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = HubColors.Text)
        content()
    }
}

@Composable
private fun SettingsRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = HubColors.TextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            content()
        }
    }
}

@Composable
private fun LanguagePickerOverlay(
    title: String,
    languages: List<Pair<String, String>>,
    selectedCode: String,
    onSelect: (String) -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { onSelect(selectedCode) },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.5f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.displayMedium, color = HubColors.Text)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(languages) { (code, name) ->
                    HubButton(
                        text = name,
                        primary = code == selectedCode,
                        onClick = { onSelect(code) }
                    )
                }
            }
        }
    }
    }
}
