package com.mdblisthub.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.data.repository.SimklLinkState
import com.mdblisthub.tv.core.model.LibraryProvider
import com.mdblisthub.tv.core.model.HubThemeVariant
import com.mdblisthub.tv.core.model.TraktAccount
import com.mdblisthub.tv.core.model.TraktLinkFailure
import com.mdblisthub.tv.core.model.TraktLinkState
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubEffects
import com.mdblisthub.tv.core.ui.theme.HubShapes
import com.mdblisthub.tv.core.ui.theme.HubStrokes
import com.mdblisthub.tv.ui.component.HubButton
import com.mdblisthub.tv.ui.addons.AddonsScreen
import com.mdblisthub.tv.ui.hubViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    "nl" to "Nederlands",
    "hr" to "Hrvatski",
    "sr" to "Српски",
    "bs" to "Bosanski"
)

data class SettingsUiState(
    val language: String = "en",
    val autotrailer: Boolean = false,
    val posterLandscapeTransformation: Boolean = true,
    val introEnabled: Boolean = true,
    /** Defaults to on, matching `UiPreferencesStore.spotlightHero`. */
    val spotlightHero: Boolean = true,
    val subtitleAutoDownload: Boolean = true,
    val subtitleLanguage: String = "pt",
    val subtitleColor: String = "white",
    val subtitleTextOpacity: Int = 100,
    val subtitleBackgroundEnabled: Boolean = false,
    val subtitleBackgroundOpacity: Int = 40,
    val audioLanguage: String = "en",
    val libraryProvider: LibraryProvider = LibraryProvider.MDBLIST,
    val dimUnwatchedEpisodes: Boolean = false,
    val theme: HubThemeVariant = HubThemeVariant.NORMAL,
    val traktAccount: TraktAccount? = null,
    /** False when the build ships no Trakt client id — see `ApiConfig`. */
    val traktConfigured: Boolean = false,
    val simklLinked: Boolean = false,
)

class SettingsViewModel(private val graph: DataGraph) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState(traktConfigured = graph.traktAuth.configured))
    val state = _state.asStateFlow()

    /** Non-null only while the device-link overlay is up. */
    private val _traktLink = MutableStateFlow<TraktLinkState?>(null)
    val traktLink = _traktLink.asStateFlow()
    private val _simklLink = MutableStateFlow<SimklLinkState?>(null)
    val simklLink = _simklLink.asStateFlow()

    private var linkJob: Job? = null

    init {
        viewModelScope.launch {
            // Nested rather than one call: `combine` has typed overloads up to
            // five flows, and this screen observes more than that.
            combine(
                graph.uiPreferences.language,
                graph.uiPreferences.autotrailer,
                graph.uiPreferences.subtitleAutoDownload,
                graph.uiPreferences.subtitleLanguage,
                graph.uiPreferences.subtitleColor,
            ) { lang, autotrailer, subAuto, subLang, subColor ->
                SettingsUiState(
                    language = lang,
                    autotrailer = autotrailer,
                    subtitleAutoDownload = subAuto,
                    subtitleLanguage = subLang,
                    subtitleColor = subColor,
                )
            }
                .combine(graph.uiPreferences.subtitleTextOpacity) { partial, opacity ->
                    partial.copy(subtitleTextOpacity = opacity)
                }
                .combine(graph.uiPreferences.subtitleBackgroundEnabled) { partial, enabled ->
                    partial.copy(subtitleBackgroundEnabled = enabled)
                }
                .combine(graph.uiPreferences.subtitleBackgroundOpacity) { partial, opacity ->
                    partial.copy(subtitleBackgroundOpacity = opacity)
                }
                .combine(graph.uiPreferences.audioLanguage) { partial, audioLang ->
                    partial.copy(audioLanguage = audioLang)
                }
                .combine(graph.uiPreferences.libraryProvider) { partial, provider ->
                    partial.copy(libraryProvider = provider)
                }
                .combine(graph.uiPreferences.dimUnwatchedEpisodes) { partial, dim ->
                    partial.copy(dimUnwatchedEpisodes = dim)
                }
                .combine(graph.uiPreferences.introEnabled) { partial, enabled ->
                    partial.copy(introEnabled = enabled)
                }
                .combine(graph.uiPreferences.spotlightHero) { partial, hero ->
                    partial.copy(spotlightHero = hero)
                }
                .combine(graph.uiPreferences.posterLandscapeTransformation) { partial, enabled ->
                    partial.copy(posterLandscapeTransformation = enabled)
                }
                .combine(graph.uiPreferences.theme) { partial, theme ->
                    partial.copy(theme = theme)
                }
                .combine(graph.traktAuth.account) { partial, account ->
                    partial.copy(
                        traktAccount = account,
                        traktConfigured = graph.traktAuth.configured,
                    )
                }
                .combine(graph.simklTokenStore.linked) { partial, linked -> partial.copy(simklLinked = linked) }
                .collect { _state.value = it }
        }
    }

    /**
     * Picking Trakt with no account linked opens the link flow instead of
     * saving a setting that could not mean anything yet — the switch is only
     * a switch once there is something on the other side of it.
     */
    fun setLibraryProvider(provider: LibraryProvider) {
        viewModelScope.launch {
            if (provider == LibraryProvider.TRAKT && _state.value.traktAccount == null) {
                beginTraktLink()
                return@launch
            }
            if (provider == LibraryProvider.SIMKL && !_state.value.simklLinked) {
                beginSimklLink()
                return@launch
            }
            graph.switchLibraryProvider(provider)
            refreshLibraryRows()
        }
    }

    fun beginTraktLink() {
        if (linkJob?.isActive == true) return
        if (!graph.traktAuth.configured) {
            _traktLink.value = TraktLinkState.Failed(TraktLinkFailure.MISSING_CREDENTIALS)
            return
        }

        _traktLink.value = TraktLinkState.Requesting
        linkJob = viewModelScope.launch {
            graph.traktAuth.startLink().fold(
                onSuccess = { code ->
                    graph.traktAuth.poll(code).collect { linkState ->
                        _traktLink.value = linkState
                        if (linkState is TraktLinkState.Linked) {
                            // Connecting *is* the request to use Trakt; making
                            // the user then pick the option they just went
                            // through a device flow for would be asking twice.
                            graph.switchLibraryProvider(LibraryProvider.TRAKT)
                            refreshLibraryRows()
                            // Deliberately left on screen rather than closed
                            // after a beat. This is the only moment the flow
                            // ever confirms it worked, and a device link is
                            // long enough — open a browser, type a code — that
                            // the viewer is often not watching the television
                            // when it lands. A confirmation nobody is looking
                            // at is the same as no confirmation, so it waits
                            // to be dismissed instead.
                        }
                    }
                },
                onFailure = {
                    _traktLink.value = TraktLinkState.Failed(TraktLinkFailure.UNAVAILABLE)
                },
            )
        }
    }

    fun dismissTraktLink() {
        linkJob?.cancel()
        linkJob = null
        _traktLink.value = null
    }

    fun beginSimklLink() {
        if (linkJob?.isActive == true) return
        _simklLink.value = SimklLinkState.Requesting
        linkJob = viewModelScope.launch {
            runCatching { graph.simklAuth.start() }.onSuccess { pin ->
                graph.simklAuth.poll(pin).collect {
                    _simklLink.value = it
                    if (it is SimklLinkState.Linked) {
                        graph.switchLibraryProvider(LibraryProvider.SIMKL)
                        refreshLibraryRows()
                        delay(LINKED_VISIBLE_MS)
                        _simklLink.value = null
                    }
                }
            }.onFailure { _simklLink.value = SimklLinkState.Failed }
        }
    }

    fun dismissSimklLink() { linkJob?.cancel(); linkJob = null; _simklLink.value = null }
    fun unlinkSimkl() = viewModelScope.launch { graph.unlinkSimkl(); refreshLibraryRows() }

    fun unlinkTrakt() {
        viewModelScope.launch {
            graph.unlinkTrakt()
            refreshLibraryRows()
        }
    }

    /**
     * On the graph's scope, not this ViewModel's: the point of refreshing here
     * is that the change has already landed by the time the user navigates
     * back to Home, and a scope that dies when Settings closes would cancel
     * exactly the work that makes that true.
     */
    private fun refreshLibraryRows() {
        graph.scope.launch {
            graph.homeFeeds.refresh()
            graph.playback.refreshResumePoints()
        }
    }

    fun setLanguage(lang: String) = viewModelScope.launch { graph.uiPreferences.saveLanguage(lang) }
    fun toggleAutotrailer() = viewModelScope.launch { graph.uiPreferences.saveAutotrailer(!_state.value.autotrailer) }

    fun togglePosterLandscapeTransformation() = viewModelScope.launch {
        graph.uiPreferences.savePosterLandscapeTransformation(
            !_state.value.posterLandscapeTransformation,
        )
    }

    fun toggleIntro() = viewModelScope.launch {
        graph.uiPreferences.saveIntroEnabled(!_state.value.introEnabled)
    }

    fun toggleSpotlightHero() = viewModelScope.launch {
        graph.uiPreferences.saveSpotlightHero(!_state.value.spotlightHero)
    }
    fun toggleSubtitleAutoDownload() = viewModelScope.launch { graph.uiPreferences.saveSubtitleAutoDownload(!_state.value.subtitleAutoDownload) }
    fun setSubtitleLanguage(lang: String) = viewModelScope.launch { graph.uiPreferences.saveSubtitleLanguage(lang) }
    fun setSubtitleColor(color: String) = viewModelScope.launch { graph.uiPreferences.saveSubtitleColor(color) }
    fun setSubtitleTextOpacity(opacity: Int) {
        val clamped = opacity.coerceIn(0, 100)
        _state.value = _state.value.copy(subtitleTextOpacity = clamped)
        viewModelScope.launch { graph.uiPreferences.saveSubtitleTextOpacity(clamped) }
    }
    fun toggleSubtitleBackground() {
        val enabled = !_state.value.subtitleBackgroundEnabled
        _state.value = _state.value.copy(subtitleBackgroundEnabled = enabled)
        viewModelScope.launch { graph.uiPreferences.saveSubtitleBackgroundEnabled(enabled) }
    }
    fun setSubtitleBackgroundOpacity(opacity: Int) {
        val clamped = opacity.coerceIn(0, 100)
        _state.value = _state.value.copy(subtitleBackgroundOpacity = clamped)
        viewModelScope.launch { graph.uiPreferences.saveSubtitleBackgroundOpacity(clamped) }
    }
    fun setAudioLanguage(lang: String) = viewModelScope.launch { graph.uiPreferences.saveAudioLanguage(lang) }
    fun setTheme(theme: HubThemeVariant) = viewModelScope.launch { graph.uiPreferences.saveTheme(theme) }
    fun toggleDimUnwatchedEpisodes() = viewModelScope.launch { graph.uiPreferences.saveDimUnwatchedEpisodes(!_state.value.dimUnwatchedEpisodes) }

    private companion object {
        /** Long enough to read "connected as @you" before the overlay closes. */
        const val LINKED_VISIBLE_MS = 1_600L
    }
}

enum class SettingsSection {
    INTERFACE,
    THEMES,
    LIBRARY,
    SUBTITLES,
    PLAYER,
    ADDONS,
}

private const val SETTINGS_DETAIL_IN_MS = 200
private const val SETTINGS_DETAIL_OUT_MS = 180
private val SETTINGS_FRAME_WIDTH = 32.dp
private const val SETTINGS_CONTENT_SCALE = 0.95f
private val SETTINGS_TITLE_SIZE = 38.sp
private val SETTINGS_TITLE_LINE_HEIGHT = 42.sp
private val SETTINGS_ACCENT_FRAME_WIDTH = 1.dp
private val SETTINGS_ACCENT_FRAME_SHAPE = RoundedCornerShape(28.dp)

private data class SettingsCategory(
    val section: SettingsSection,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun SettingsScreen(
    graph: DataGraph,
    onBack: () -> Unit,
    initialSection: SettingsSection = SettingsSection.INTERFACE,
) {
    val viewModel = hubViewModel { SettingsViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val traktLink by viewModel.traktLink.collectAsStateWithLifecycle()
    val simklLink by viewModel.simklLink.collectAsStateWithLifecycle()
    var selectedSection by rememberSaveable { mutableStateOf(initialSection) }
    var subtitlePickerOpen by remember { mutableStateOf(false) }
    var audioPickerOpen by remember { mutableStateOf(false) }
    val railFocusRequesters = remember {
        SettingsSection.entries.associateWith { FocusRequester() }
    }

    val categories = listOf(
        SettingsCategory(
            SettingsSection.INTERFACE,
            stringResource(R.string.settings_section_interface),
            stringResource(R.string.settings_category_interface_desc),
            Icons.Default.Settings,
        ),
        SettingsCategory(
            SettingsSection.THEMES,
            stringResource(R.string.settings_section_themes),
            stringResource(R.string.settings_category_themes_desc),
            Icons.Default.Palette,
        ),
        SettingsCategory(
            SettingsSection.LIBRARY,
            stringResource(R.string.settings_section_library),
            stringResource(R.string.settings_category_library_desc),
            Icons.AutoMirrored.Filled.ViewList,
        ),
        SettingsCategory(
            SettingsSection.SUBTITLES,
            stringResource(R.string.settings_section_subtitles),
            stringResource(R.string.settings_category_subtitles_desc),
            Icons.Default.Subtitles,
        ),
        SettingsCategory(
            SettingsSection.PLAYER,
            stringResource(R.string.settings_section_player),
            stringResource(R.string.settings_category_player_desc),
            Icons.Default.PlayArrow,
        ),
        SettingsCategory(
            SettingsSection.ADDONS,
            stringResource(R.string.addons_title),
            stringResource(R.string.settings_category_addons_desc),
            Icons.Default.Extension,
        ),
    )
    BackHandler {
        when {
            traktLink != null -> viewModel.dismissTraktLink()
            simklLink != null -> viewModel.dismissSimklLink()
            subtitlePickerOpen -> subtitlePickerOpen = false
            audioPickerOpen -> audioPickerOpen = false
            else -> onBack()
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        railFocusRequesters.getValue(initialSection).requestFocus()
    }

    val screenDensity = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HubColors.Background)
            // A real inset frame keeps every settings control inside the TV's
            // comfortable viewing area and makes the layout read slightly
            // smaller without shrinking text or focus targets.
            .border(SETTINGS_FRAME_WIDTH, HubColors.Background)
            .padding(SETTINGS_FRAME_WIDTH)
            .border(
                SETTINGS_ACCENT_FRAME_WIDTH,
                HubColors.Accent.copy(alpha = 0.78f),
                SETTINGS_ACCENT_FRAME_SHAPE,
            )
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        // The frame and its usable viewport stay untouched. Only the density
        // seen by the settings controls is reduced, which scales type, cards,
        // icons and internal spacing together without introducing a second
        // inset or leaving an unused strip around the content.
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = screenDensity.density * SETTINGS_CONTENT_SCALE,
                fontScale = screenDensity.fontScale,
            ),
        ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(286.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = SETTINGS_TITLE_SIZE,
                        lineHeight = SETTINGS_TITLE_LINE_HEIGHT,
                    ),
                    color = HubColors.Text,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(categories, key = { it.section.name }) { category ->
                        SettingsRailButton(
                            category = category,
                            selected = category.section == selectedSection,
                            onSelect = { selectedSection = category.section },
                            modifier = Modifier.focusRequester(
                                railFocusRequesters.getValue(category.section),
                            ),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusGroup()
                    .focusProperties {
                        onExit = {
                            if (requestedFocusDirection == FocusDirection.Left) {
                                railFocusRequesters.getValue(selectedSection).requestFocus()
                            }
                        }
                    },
            ) {
                AnimatedContent(
                    targetState = selectedSection,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        val order = categories.map { it.section }
                        val forward = order.indexOf(targetState) >= order.indexOf(initialState)
                        val direction = if (forward) 1 else -1
                        (slideInHorizontally(
                            animationSpec = tween(
                                SETTINGS_DETAIL_IN_MS,
                                easing = FastOutSlowInEasing,
                            ),
                        ) { fullWidth -> direction * fullWidth / 4 } +
                            fadeIn(tween(SETTINGS_DETAIL_IN_MS)))
                            .togetherWith(
                                slideOutHorizontally(
                                    animationSpec = tween(
                                        SETTINGS_DETAIL_OUT_MS,
                                        easing = FastOutSlowInEasing,
                                    ),
                                ) { fullWidth -> -direction * fullWidth / 4 } +
                                    fadeOut(tween(SETTINGS_DETAIL_OUT_MS)),
                            )
                    },
                    label = "settings-detail-transition",
                ) { animatedSection ->
                    val animatedCategory = categories.first { it.section == animatedSection }
                    Column(Modifier.fillMaxSize()) {
                        SettingsDetailHeader(animatedCategory.title, animatedCategory.subtitle)
                        Spacer(Modifier.height(20.dp))

                        when (animatedSection) {
                            SettingsSection.INTERFACE -> InterfaceSettingsContent(state, viewModel)
                            SettingsSection.THEMES -> ThemeSettingsContent(
                                theme = state.theme,
                                autotrailer = state.autotrailer,
                                onSelect = viewModel::setTheme,
                                onToggleAutotrailer = viewModel::toggleAutotrailer,
                            )
                            SettingsSection.LIBRARY -> LibrarySettingsContent(state, viewModel)
                            SettingsSection.SUBTITLES -> SubtitleSettingsContent(
                                state = state,
                                viewModel = viewModel,
                                onOpenLanguage = { subtitlePickerOpen = true },
                            )
                            SettingsSection.PLAYER -> PlayerSettingsContent(
                                state = state,
                                onOpenLanguage = { audioPickerOpen = true },
                            )
                            SettingsSection.ADDONS -> AddonsScreen(
                                graph = graph,
                                onBack = {},
                                embedded = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
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

        traktLink?.let { link ->
            TraktLinkOverlay(
                state = link,
                onRetry = viewModel::beginTraktLink,
                onDismiss = viewModel::dismissTraktLink,
            )
        }
        simklLink?.let { link -> SimklLinkOverlay(link, viewModel::beginSimklLink, viewModel::dismissSimklLink) }
        }
    }
}

@Composable
private fun SettingsRailButton(
    category: SettingsCategory,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(28.dp)
    val background by animateColorAsState(
        if (selected || focused) HubColors.SurfaceStrong else HubColors.Surface.copy(alpha = 0.55f),
        label = "settings-rail-background",
    )
    val border by animateColorAsState(
        if (focused) HubColors.Text else if (selected) HubColors.Accent else Color.Transparent,
        label = "settings-rail-border",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(shape)
            .background(background)
            .border(if (focused || selected) 1.5.dp else 0.dp, border, shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onSelect()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = category.icon,
            contentDescription = null,
            tint = if (selected || focused) HubColors.Text else HubColors.TextDim,
        )
        Text(
            text = category.title,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected || focused) HubColors.Text else HubColors.TextDim,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = HubColors.TextFaint,
        )
    }
}

@Composable
private fun SettingsDetailHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.displayMedium.copy(
                fontSize = SETTINGS_TITLE_SIZE,
                lineHeight = SETTINGS_TITLE_LINE_HEIGHT,
            ),
            color = HubColors.Text,
        )
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = HubColors.TextDim)
    }
}

@Composable
private fun SettingsContentList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(end = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
private fun InterfaceSettingsContent(state: SettingsUiState, viewModel: SettingsViewModel) {
    SettingsContentList {
        item {
            SettingsCard(
                title = stringResource(R.string.settings_interface_group),
                subtitle = stringResource(R.string.settings_category_interface_desc),
            ) {
                SettingsRow(label = stringResource(R.string.settings_language)) {
                    listOf(
                        "pt" to stringResource(R.string.lang_pt),
                        "en" to stringResource(R.string.lang_en),
                        "es" to stringResource(R.string.lang_es),
                    ).forEach { (code, name) ->
                        HubButton(text = name, primary = state.language == code, onClick = { viewModel.setLanguage(code) })
                    }
                }
                ToggleSettingsRow(
                    stringResource(R.string.settings_poster_landscape_transformation),
                    state.posterLandscapeTransformation,
                    viewModel::togglePosterLandscapeTransformation,
                )
                ToggleSettingsRow(stringResource(R.string.settings_intro), state.introEnabled, viewModel::toggleIntro)
                ToggleSettingsRow(stringResource(R.string.settings_spotlight_hero), state.spotlightHero, viewModel::toggleSpotlightHero)
            }
        }
    }
}

@Composable
private fun ThemeSettingsContent(
    theme: HubThemeVariant,
    autotrailer: Boolean,
    onSelect: (HubThemeVariant) -> Unit,
    onToggleAutotrailer: () -> Unit,
) {
    // CyberFlix and Optimus Prime are playback modes, not additional visual
    // themes: they are Netflixy and Primefly with the compatible autotrailer
    // enabled. Normalize them here so the selector has one card per actual
    // appearance instead of presenting two pairs of duplicates.
    val visualTheme = when (theme) {
        HubThemeVariant.CYBERFLIX -> HubThemeVariant.NETFLIXY
        HubThemeVariant.OPTIMUS_PRIME -> HubThemeVariant.PRIMEFLY
        else -> theme
    }
    val themes = listOf(
        Triple(HubThemeVariant.NORMAL, stringResource(R.string.menu_theme_normal), R.drawable.theme_normal_preview),
        Triple(HubThemeVariant.CYBERPUNK, stringResource(R.string.menu_theme_cyberpunk), R.drawable.theme_cyberpunk_preview),
        Triple(HubThemeVariant.NETFLIXY, stringResource(R.string.menu_theme_netflixy), R.drawable.theme_netflixy_preview),
        Triple(HubThemeVariant.PRIMEFLY, stringResource(R.string.menu_theme_primefly), R.drawable.theme_primefly_preview),
    )
    SettingsContentList {
        item {
            SettingsCard(
                title = stringResource(R.string.settings_theme_group),
                subtitle = stringResource(R.string.settings_theme_group_desc),
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(themes, key = { it.first.name }) { (variant, name, preview) ->
                        ThemeOptionCard(
                            name = name,
                            preview = preview,
                            selected = visualTheme == variant,
                            onClick = { onSelect(variant) },
                        )
                    }
                }
                ToggleSettingsRow(
                    label = stringResource(R.string.settings_theme_autotrailer),
                    enabled = autotrailer,
                    onToggle = onToggleAutotrailer,
                )
            }
        }
    }
}

@Composable
private fun ThemeOptionCard(name: String, preview: Int, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .width(204.dp)
            .height(164.dp)
            .clip(shape)
            .background(HubColors.SurfaceStrong)
            .border(
                if (selected || focused) 2.dp else 1.dp,
                if (focused) HubColors.Text else if (selected) HubColors.Accent else HubColors.Border.copy(alpha = 0.5f),
                shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(preview),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.38f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                }
            }
        }
        Text(name, style = MaterialTheme.typography.bodyLarge, color = HubColors.Text)
    }
}

@Composable
private fun LibrarySettingsContent(state: SettingsUiState, viewModel: SettingsViewModel) {
    SettingsContentList {
        item {
            SettingsCard(
                title = stringResource(R.string.settings_section_library),
                subtitle = stringResource(R.string.settings_category_library_desc),
            ) {
                SettingsRow(label = stringResource(if (state.libraryProvider == LibraryProvider.SIMKL) R.string.settings_library_provider_simkl else R.string.settings_library_provider)) {
                    listOf(
                        LibraryProvider.MDBLIST to stringResource(R.string.settings_library_mdblist),
                        LibraryProvider.TRAKT to stringResource(R.string.settings_library_trakt),
                        LibraryProvider.SIMKL to stringResource(R.string.settings_library_simkl),
                    ).forEach { (provider, name) ->
                        HubButton(text = name, primary = state.libraryProvider == provider, onClick = { viewModel.setLibraryProvider(provider) })
                    }
                }
                ToggleSettingsRow(
                    stringResource(R.string.settings_dim_unwatched_episodes),
                    state.dimUnwatchedEpisodes,
                    viewModel::toggleDimUnwatchedEpisodes,
                )
                SettingsRow(label = state.traktAccount?.handle ?: stringResource(R.string.settings_trakt_not_connected)) {
                    if (state.traktAccount == null) {
                        HubButton(text = stringResource(R.string.settings_trakt_connect), primary = true, onClick = viewModel::beginTraktLink)
                    } else {
                        HubButton(text = stringResource(R.string.settings_trakt_disconnect), onClick = viewModel::unlinkTrakt)
                    }
                }
                SettingsRow(label = stringResource(if (state.simklLinked) R.string.settings_simkl_connected else R.string.settings_simkl_not_connected)) {
                    if (state.simklLinked) HubButton(text = stringResource(R.string.settings_simkl_disconnect), onClick = viewModel::unlinkSimkl)
                    else HubButton(text = stringResource(R.string.settings_simkl_connect), primary = true, onClick = viewModel::beginSimklLink)
                }
            }
        }
    }
}

@Composable
private fun SubtitleSettingsContent(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenLanguage: () -> Unit,
) {
    SettingsContentList {
        item {
            SettingsCard(
                title = stringResource(R.string.settings_section_subtitles),
                subtitle = stringResource(R.string.settings_category_subtitles_desc),
            ) {
                ToggleSettingsRow(
                    stringResource(R.string.settings_subtitle_auto_download),
                    state.subtitleAutoDownload,
                    viewModel::toggleSubtitleAutoDownload,
                )
                SettingsRow(label = stringResource(R.string.settings_subtitle_default_lang)) {
                    val currentName = ALL_LANGUAGES.find { it.first == state.subtitleLanguage }?.second ?: state.subtitleLanguage
                    HubButton(text = currentName, primary = true, onClick = onOpenLanguage)
                }
                SettingsRow(label = stringResource(R.string.settings_subtitle_color)) {
                    listOf(
                        Triple("yellow", stringResource(R.string.color_yellow), Color(0xFFFFD600)),
                        Triple("white", stringResource(R.string.color_white), Color.White),
                        Triple("red", stringResource(R.string.color_red), Color(0xFFE53935)),
                        Triple("blue", stringResource(R.string.color_blue), Color(0xFF2196F3)),
                        Triple("black", stringResource(R.string.color_black), Color.Black),
                    ).forEach { (code, name, color) ->
                        SubtitleColorOption(
                            color = color,
                            contentDescription = name,
                            selected = state.subtitleColor == code,
                            onClick = { viewModel.setSubtitleColor(code) },
                        )
                    }
                }
                SettingsRow(label = stringResource(R.string.settings_subtitle_text_opacity)) {
                    OpacitySlider(value = state.subtitleTextOpacity, onValueChange = viewModel::setSubtitleTextOpacity)
                }
                ToggleSettingsRow(
                    stringResource(R.string.settings_subtitle_black_background),
                    state.subtitleBackgroundEnabled,
                    viewModel::toggleSubtitleBackground,
                )
                if (state.subtitleBackgroundEnabled) {
                    SettingsRow(label = stringResource(R.string.settings_subtitle_background_opacity)) {
                        OpacitySlider(value = state.subtitleBackgroundOpacity, onValueChange = viewModel::setSubtitleBackgroundOpacity)
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleColorOption(
    color: Color,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        focused -> HubColors.Text
        selected -> HubColors.Accent
        else -> HubColors.Border
    }

    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(HubColors.SurfaceStrong)
            .border(if (focused || selected) 3.dp else 1.dp, borderColor, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = contentDescription,
                    tint = if (color == Color.White || color == Color(0xFFFFD600)) Color.Black else Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayerSettingsContent(state: SettingsUiState, onOpenLanguage: () -> Unit) {
    SettingsContentList {
        item {
            SettingsCard(
                title = stringResource(R.string.settings_section_player),
                subtitle = stringResource(R.string.settings_category_player_desc),
            ) {
                SettingsRow(label = stringResource(R.string.settings_audio_preferred_lang)) {
                    val currentName = ALL_LANGUAGES.find { it.first == state.audioLanguage }?.second ?: state.audioLanguage
                    HubButton(text = currentName, primary = true, onClick = onOpenLanguage)
                }
            }
        }
    }
}

@Composable
private fun ToggleSettingsRow(label: String, enabled: Boolean, onToggle: () -> Unit) {
    SettingsRow(label = label) {
        HubButton(
            text = stringResource(if (enabled) R.string.settings_on else R.string.settings_off),
            primary = enabled,
            onClick = onToggle,
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(HubShapes.Panel)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HubColors.Surface.copy(alpha = HubEffects.GlassSurfaceAlpha))
            .border(
                HubStrokes.Hairline,
                HubColors.Border.copy(alpha = HubEffects.SoftBorderAlpha),
                shape,
            )
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = HubColors.Text)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = HubColors.TextDim)
            }
        }
        content()
    }
}

@Composable
private fun SettingsRow(label: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(HubShapes.Field)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HubColors.SurfaceStrong.copy(alpha = HubEffects.MutedSurfaceAlpha))
            .border(HubStrokes.Hairline, HubColors.Border.copy(alpha = 0.45f), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = HubColors.TextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            content()
        }
    }
}

/** TV-friendly 0–100 slider: Left/Right moves one percentage point. */
@Composable
private fun OpacitySlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val clamped = value.coerceIn(0, 100)

    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            modifier = modifier
                .width(360.dp)
                .height(32.dp)
                .focusable(interactionSource = interaction)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            onValueChange((clamped - 1).coerceAtLeast(0)); true
                        }
                        Key.DirectionRight -> {
                            onValueChange((clamped + 1).coerceAtMost(100)); true
                        }
                        else -> false
                    }
                },
        ) {
            val trackHeight = 8.dp.toPx()
            val thumbRadius = if (focused) 10.dp.toPx() else 8.dp.toPx()
            val trackStart = thumbRadius
            val trackWidth = size.width - thumbRadius * 2
            val trackTop = (size.height - trackHeight) / 2f
            val progressWidth = trackWidth * clamped / 100f

            drawRoundRect(
                color = HubColors.Border,
                topLeft = androidx.compose.ui.geometry.Offset(trackStart, trackTop),
                size = androidx.compose.ui.geometry.Size(trackWidth, trackHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f),
            )
            drawRoundRect(
                color = HubColors.Accent,
                topLeft = androidx.compose.ui.geometry.Offset(trackStart, trackTop),
                size = androidx.compose.ui.geometry.Size(progressWidth, trackHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f),
            )
            drawCircle(
                color = if (focused) Color.White else HubColors.Accent,
                radius = thumbRadius,
                center = androidx.compose.ui.geometry.Offset(
                    trackStart + progressWidth,
                    size.height / 2f,
                ),
            )
        }
        Text(
            text = "$clamped%",
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused) HubColors.Text else HubColors.TextDim,
        )
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
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(HubShapes.Dialog))
                .background(HubColors.Surface.copy(alpha = 0.96f))
                .border(
                    HubStrokes.Hairline,
                    HubColors.Border.copy(alpha = HubEffects.SoftBorderAlpha),
                    RoundedCornerShape(HubShapes.Dialog),
                )
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = SETTINGS_TITLE_SIZE,
                    lineHeight = SETTINGS_TITLE_LINE_HEIGHT,
                ),
                color = HubColors.Text,
            )
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
