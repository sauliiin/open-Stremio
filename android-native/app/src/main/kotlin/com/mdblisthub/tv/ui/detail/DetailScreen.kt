package com.mdblisthub.tv.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.os.Build
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.mdblisthub.tv.R
import coil3.compose.AsyncImage
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.CastMember
import com.mdblisthub.tv.core.model.Episode
import com.mdblisthub.tv.core.model.LibraryBucket
import com.mdblisthub.tv.core.model.LibraryProvider
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.PersonSummary
import com.mdblisthub.tv.core.model.Review
import com.mdblisthub.tv.core.model.ReviewProvider
import com.mdblisthub.tv.core.ui.component.FanartBackdrop
import com.mdblisthub.tv.ui.component.formatAirDate
import com.mdblisthub.tv.core.ui.component.HubSpinner
import com.mdblisthub.tv.core.ui.component.LoadingScreen
import com.mdblisthub.tv.core.ui.component.MediaRow
import com.mdblisthub.tv.core.ui.component.RatingBadges
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens
import com.mdblisthub.tv.core.ui.theme.HubEffects
import com.mdblisthub.tv.core.ui.theme.HubShapes
import com.mdblisthub.tv.core.ui.theme.HubStrokes
import com.mdblisthub.tv.ui.component.HubButton
import com.mdblisthub.tv.ui.component.MediaOptionsDialog
import com.mdblisthub.tv.ui.component.text
import com.mdblisthub.tv.ui.hubViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    graph: DataGraph,
    type: MediaType,
    tmdbId: Int,
    onBack: () -> Unit,
    onPlay: (season: Int?, episode: Int?) -> Unit,
    onSelectSource: (season: Int?, episode: Int?) -> Unit,
    onOpenTitle: (MediaItem) -> Unit,
    onPlayItem: (MediaItem) -> Unit,
    onChooseSourceForItem: (MediaItem) -> Unit,
    /** The backdrop already on screen for this title, carried over from
     * wherever it was opened — see `Routes.detail`. Painted immediately so
     * the page never sits on a bare [LoadingScreen] before its own fetch
     * resolves. */
    heroBackdropUrl: String? = null,
) {
    val viewModel = hubViewModel(key = "detail-$type-$tmdbId") {
        DetailViewModel(graph, type, tmdbId)
    }
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val season by viewModel.season.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val appLanguage by viewModel.language.collectAsStateWithLifecycle()
    val watchedEpisodes by viewModel.watchedEpisodes.collectAsStateWithLifecycle()
    val watchedIds by viewModel.watchedIds.collectAsStateWithLifecycle()
    val dimUnwatchedEpisodes by viewModel.dimUnwatchedEpisodes.collectAsStateWithLifecycle()
    val posterLandscapeTransformation by graph.uiPreferences.posterLandscapeTransformation
        .collectAsStateWithLifecycle(initialValue = true)
    val library by viewModel.library.collectAsStateWithLifecycle()
    val libraryProvider by graph.uiPreferences.libraryProvider.collectAsStateWithLifecycle(initialValue = LibraryProvider.MDBLIST)
    val resumePoint by viewModel.resumePoint.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val libraryError by viewModel.libraryError.collectAsStateWithLifecycle()
    val castBio by viewModel.castBio.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var buttonRowHadFocus by remember { mutableStateOf(false) }
    var buttonRowHasFocus by remember { mutableStateOf(false) }
    var trailerOpen by remember { mutableStateOf(false) }
    var episodeDetails by remember { mutableStateOf<Episode?>(null) }
    var openedReview by remember { mutableStateOf<Review?>(null) }
    var recommendationOptionTarget by remember { mutableStateOf<MediaItem?>(null) }
    // Episode cards update this on focus. The main actions then follow the
    // episode the viewer was actually browsing instead of always targeting E1.
    var focusedEpisodeNumber by remember(tmdbId, season) { mutableIntStateOf(1) }
    val actionEpisodeNumber = episodes
        .firstOrNull { it.episodeNumber == focusedEpisodeNumber }
        ?.episodeNumber
        ?: episodes.firstOrNull()?.episodeNumber
        ?: 1

    LaunchedEffect(buttonRowHasFocus) {
        if (buttonRowHasFocus) {
            if (!buttonRowHadFocus) {
                // Entering the row from outside: snap the list immediately
                // to the top so logo, synopsis and ratings are visible
                // while the user navigates between buttons.
                listState.scrollToItem(0)
                buttonRowHadFocus = true
            }
        } else {
            // Momentary focus loss during sibling transitions is common;
            // only reset the 'had focus' flag if it stays lost.
            delay(100)
            buttonRowHadFocus = false
        }
    }

    /*
     * The micro-scroll fix.
     *
     * Every focusable inside a LazyColumn asks the list to bring it into view
     * the moment it gains focus. While the D-pad walks Assistir → Trailer →
     * Watchlist, that request fires per button — and because the head item is
     * taller than the viewport, "make the button fully visible" means nudging
     * the list down a few pixels, which our scroll-to-top above then undoes.
     * That tug-of-war is the visible jitter.
     *
     * Zeroing the scroll distance while the row holds focus removes the
     * system's half of the fight entirely: the one-shot scrollToItem(0) pins
     * the header, and focus slides between buttons without the list moving.
     * The moment focus leaves the row (down to seasons/cast), the default
     * minimal-scroll behaviour is back and vertical navigation works as ever.
     */
    val pinWhileOnButtons = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (buttonRowHasFocus) return 0f
                return when {
                    offset >= 0f && offset + size <= containerSize -> 0f
                    offset < 0f -> offset
                    else -> offset + size - containerSize
                }
            }
        }
    }

    BackHandler {
        when {
            trailerOpen -> trailerOpen = false
            openedReview != null -> openedReview = null
            castBio.member != null -> viewModel.closeCast()
            else -> onBack()
        }
    }

    val current = detail

    recommendationOptionTarget?.let { item ->
        val isWatched = watchedIds.contains(item.tmdbId)
        MediaOptionsDialog(
            isWatched = isWatched,
            onPlay = {
                onPlayItem(item)
                recommendationOptionTarget = null
            },
            onInfo = {
                onOpenTitle(item)
                recommendationOptionTarget = null
            },
            onToggleWatched = {
                viewModel.setWatched(item, !isWatched)
                recommendationOptionTarget = null
            },
            onChooseSource = {
                onChooseSourceForItem(item)
                recommendationOptionTarget = null
            },
            onDismiss = { recommendationOptionTarget = null },
        )
    }

    Box(Modifier.fillMaxSize()) {
        // Keep the artwork present as part of the page instead of reducing it
        // to a barely visible wallpaper; the shared left/bottom gradients keep
        // metadata and the rows readable. Falls back to the backdrop already
        // on screen wherever this title was opened from — home, search, a
        // recommendations row — so arriving here never shows a blank page
        // while the fetch below is still in flight.
        FanartBackdrop(url = current?.backdropUrl ?: heroBackdropUrl, scrim = 0.56f)

        if (current == null) {
            LoadingScreen(message = stringResource(R.string.loading_fetching))
            return@Box
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides pinWhileOnButtons) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(HubDimens.RowSpacing),
            contentPadding = PaddingValues(
                top = HubDimens.ScreenPaddingVertical * 2,
                bottom = HubDimens.ScreenPaddingVertical * 2,
            ),
        ) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = HubDimens.ScreenPaddingHorizontal)) {
                    // Kodi's skins lead with the clearlogo when there is one;
                    // it reads better over artwork than set type ever does.
                    if (current.logoUrl != null) {
                        AsyncImage(
                            model = current.logoUrl,
                            contentDescription = current.title,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            modifier = Modifier.height(96.dp).widthIn(max = 460.dp).fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = current.title,
                            style = MaterialTheme.typography.displayLarge,
                            color = HubColors.Text,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOfNotNull(
                            current.year?.toString(),
                            current.certification,
                            current.runtimeMinutes?.let { "$it min" },
                            current.seasonCount?.let { "$it temporada${if (it > 1) "s" else ""}" },
                            current.genres.take(3).joinToString(" · ").takeIf { it.isNotBlank() },
                        ).forEach {
                            Text(it, style = MaterialTheme.typography.titleMedium, color = HubColors.TextDim)
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    RatingBadges(current.ratings)

                    current.overview?.let {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = HubColors.TextDim,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(22.dp))
                    // Five buttons is more than a phone's width holds at a
                    // 10-foot touch-target size, so the row scrolls instead of
                    // squeezing — and `height(IntrinsicSize.Min)` with
                    // `fillMaxHeight()` on each button is what keeps "Marcar
                    // como assistido" from reading taller than the others
                    // whenever it does wrap.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .height(IntrinsicSize.Min)
                            // Coming back up from cast/reviews/episodes to this
                            // row is the moment the poster, rating and overview
                            // above it need to be visible again, so focus
                            // landing here snaps the list back to the top.
                            .onFocusChanged { state ->
                                // Only track whether the row has focus; the
                                // scroll action is triggered once from the
                                // LaunchedEffect above when focus enters the row
                                // from outside. This prevents repeated jumps as
                                // focus moves between buttons.
                                buttonRowHasFocus = state.hasFocus
                            },
                    ) {
                        // Both actions follow the episode most recently focused
                        // in the row below. A held OK on that card is the direct
                        // shortcut to the same source picker.
                        HubButton(
                            text = if (type == MediaType.SHOW) {
                                stringResource(R.string.detail_watch_episode, season, actionEpisodeNumber)
                            } else {
                                stringResource(R.string.detail_watch)
                            },
                            primary = true,
                            onClick = {
                                if (type == MediaType.SHOW) {
                                    onPlay(season, actionEpisodeNumber)
                                } else {
                                    onPlay(null, null)
                                }
                            },
                            modifier = Modifier.fillMaxHeight(),
                        )
                        HubButton(
                            text = if (type == MediaType.SHOW) {
                                stringResource(R.string.detail_select_source_episode, season, actionEpisodeNumber)
                            } else {
                                stringResource(R.string.detail_select_source)
                            },
                            onClick = {
                                if (type == MediaType.SHOW) {
                                    onSelectSource(season, actionEpisodeNumber)
                                } else {
                                    onSelectSource(null, null)
                                }
                            },
                            modifier = Modifier.fillMaxHeight(),
                        )
                        if (current.trailerKey != null || current.imdbId != null) {
                            HubButton(
                                text = stringResource(R.string.detail_trailer),
                                onClick = { trailerOpen = true },
                                modifier = Modifier.fillMaxHeight(),
                            )
                        }
                        HubButton(
                            text = if (library.watchlist) stringResource(R.string.detail_in_watchlist) else stringResource(R.string.detail_add_watchlist),
                            enabled = LibraryBucket.WATCHLIST !in pending,
                            onClick = viewModel::toggleWatchlist,
                            modifier = Modifier.fillMaxHeight(),
                        )
                        if (libraryProvider != LibraryProvider.SIMKL) {
                            HubButton(
                                text = if (library.collection) stringResource(R.string.detail_in_collection) else stringResource(R.string.detail_add_collection),
                                enabled = LibraryBucket.COLLECTION !in pending,
                                onClick = viewModel::toggleCollection,
                                modifier = Modifier.fillMaxHeight(),
                            )
                        }
                        HubButton(
                            text = if (library.watched) stringResource(R.string.detail_watched) else stringResource(R.string.detail_mark_watched),
                            enabled = LibraryBucket.WATCHED !in pending,
                            onClick = viewModel::toggleWatched,
                            modifier = Modifier.fillMaxHeight(),
                        )
                        if (resumePoint != null) {
                            HubButton(
                                text = stringResource(R.string.detail_clear_progress),
                                onClick = viewModel::clearProgress,
                                modifier = Modifier.fillMaxHeight(),
                            )
                        }
                    }

                    libraryError?.let { failure ->
                        Spacer(Modifier.height(10.dp))
                        Text(failure.text(), style = MaterialTheme.typography.bodyMedium, color = HubColors.Rotten)
                    }

                    if (current.directors.isNotEmpty() || current.studios.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = listOfNotNull(
                                current.directors.takeIf { it.isNotEmpty() }
                                    ?.joinToString(", ")?.let { stringResource(R.string.detail_director, it) },
                                current.studios.takeIf { it.isNotEmpty() }
                                    ?.joinToString(", ")?.let { stringResource(R.string.detail_studio, it) },
                            ).joinToString("   ·   "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = HubColors.TextFaint,
                        )
                    }
                }
            }

            if (type == MediaType.SHOW && current.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.detail_seasons),
                            style = MaterialTheme.typography.titleLarge,
                            color = HubColors.Text,
                            modifier = Modifier.padding(start = HubDimens.ScreenPaddingHorizontal),
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = HubDimens.ScreenPaddingHorizontal),
                            modifier = Modifier.focusRestorer(),
                        ) {
                            items(current.seasons, key = { it.seasonNumber }) { entry ->
                                HubButton(
                                    text = stringResource(R.string.detail_season_number, entry.seasonNumber),
                                    primary = entry.seasonNumber == season,
                                    onClick = { viewModel.selectSeason(entry.seasonNumber) },
                                )
                            }
                        }
                    }
                }

                item(key = "episodes") {
                    EpisodeRow(
                        episodes = episodes,
                        watchedEpisodes = watchedEpisodes,
                        dimUnwatched = dimUnwatchedEpisodes,
                        showTmdbId = tmdbId,
                        appLanguage = appLanguage,
                        onOpenDetails = { ep -> episodeDetails = ep },
                        onSelectSource = { ep ->
                            onSelectSource(ep.seasonNumber, ep.episodeNumber)
                        },
                        onFocused = { ep -> focusedEpisodeNumber = ep.episodeNumber },
                    )
                }
            }

            if (current.cast.isNotEmpty()) {
                item(key = "cast") {
                    CastRow(current, onCastClick = viewModel::openCast)
                }
            }

            if (current.reviews.isNotEmpty()) {
                item(key = "reviews") {
                    ReviewsRow(current.reviews, onReviewClick = { openedReview = it })
                }
            }

            if (current.recommendations.isNotEmpty()) {
                item(key = "recs") {
                    MediaRow(
                        title = stringResource(R.string.detail_recommendations),
                        items = current.recommendations,
                        onItemClick = onOpenTitle,
                        onItemLongClickIndexed = { _, item ->
                            recommendationOptionTarget = item
                        },
                        expandCardsOnFocus = posterLandscapeTransformation,
                    )
                }
            }
        }
        }

        castBio.member?.let { member ->
            CastBioOverlay(
                member = member,
                state = castBio,
                onDismiss = viewModel::closeCast,
            )
        }

        openedReview?.let { review ->
            ReviewOverlay(review = review, onDismiss = { openedReview = null })
        }

        if (trailerOpen) {
            TrailerOverlay(
                imdbId = current.imdbId,
                youtubeKey = current.trailerKey,
                title = current.title,
                trailers = graph.trailers,
                onDismiss = { trailerOpen = false },
                onOpenExternally = {
                    trailerOpen = false
                    current.trailerKey?.let { key -> openTrailer(context, key) }
                },
            )
        }

        episodeDetails?.let { episode ->
            EpisodeDetailsDialog(
                episode = episode,
                appLanguage = appLanguage,
                onPlay = {
                    episodeDetails = null
                    onPlay(episode.seasonNumber, episode.episodeNumber)
                },
                onSelectSource = {
                    episodeDetails = null
                    onSelectSource(episode.seasonNumber, episode.episodeNumber)
                },
                onDismiss = { episodeDetails = null },
            )
        }
    }
}

@Composable
private fun EpisodeDetailsDialog(
    episode: Episode,
    appLanguage: String,
    onPlay: () -> Unit,
    onSelectSource: () -> Unit,
    onDismiss: () -> Unit,
) {
    val watchFocus = remember { FocusRequester() }
    LaunchedEffect(episode.id) { watchFocus.requestFocus() }
    val metadata = listOfNotNull(
        episode.airDate?.let { formatAirDate(it, appLanguage) },
        episode.runtimeMinutes?.takeIf { it > 0 }
            ?.let { stringResource(R.string.detail_episode_runtime, it) },
        episode.voteAverage?.takeIf { it > 0.0 }
            ?.let { stringResource(R.string.detail_episode_rating, it) },
    ).joinToString("  •  ")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogShape = RoundedCornerShape(HubShapes.Dialog)
        Column(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .widthIn(max = 900.dp)
                .clip(dialogShape)
                .background(HubColors.Surface.copy(alpha = 0.96f))
                .border(HubStrokes.Hairline, HubColors.Border, dialogShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .width(300.dp)
                        .height(169.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(HubColors.SurfaceStrong),
                ) {
                    episode.stillUrl?.let { still ->
                        AsyncImage(
                            model = still,
                            contentDescription = episode.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.detail_episode_number,
                            episode.seasonNumber,
                            episode.episodeNumber,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = HubColors.AccentSoft,
                    )
                    Text(
                        text = episode.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = HubColors.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (metadata.isNotBlank()) {
                        Text(
                            text = metadata,
                            style = MaterialTheme.typography.bodyMedium,
                            color = HubColors.TextDim,
                        )
                    }
                    Text(
                        text = episode.overview?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.detail_episode_no_overview),
                        style = MaterialTheme.typography.bodyLarge,
                        color = HubColors.TextDim,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HubButton(
                    text = stringResource(
                        R.string.detail_watch_episode,
                        episode.seasonNumber,
                        episode.episodeNumber,
                    ),
                    primary = true,
                    onClick = onPlay,
                    modifier = Modifier.focusRequester(watchFocus),
                )
                HubButton(
                    text = stringResource(R.string.detail_select_source),
                    onClick = onSelectSource,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    episodes: List<Episode>,
    watchedEpisodes: Set<String>,
    dimUnwatched: Boolean,
    showTmdbId: Int,
    appLanguage: String,
    onOpenDetails: (Episode) -> Unit,
    onSelectSource: (Episode) -> Unit,
    onFocused: (Episode) -> Unit,
) {
    if (episodes.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.detail_episodes),
            style = MaterialTheme.typography.titleLarge,
            color = HubColors.Text,
            modifier = Modifier.padding(start = HubDimens.ScreenPaddingHorizontal),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = HubDimens.ScreenPaddingHorizontal),
            modifier = Modifier.focusRestorer(),
        ) {
            items(episodes, key = { it.id }) { episode ->
                // These cards carried no focus treatment at all: `clickable`
                // made them focusable, but nothing on screen changed, so
                // walking the row with a remote gave no clue where you were.
                // Every other focusable in the app tracks focus explicitly —
                // the cast row directly below this one included.
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()

                LaunchedEffect(focused) {
                    if (focused) onFocused(episode)
                }

                val borderWidth by animateDpAsState(
                    if (focused) 3.dp else 0.dp,
                    episodeFocusTween(),
                    label = "episode-border-width",
                )
                val borderColor by animateColorAsState(
                    if (focused) HubColors.Accent else HubColors.Border,
                    episodeFocusTween(),
                    label = "episode-border-color",
                )
                // The still image occupies most of the card, so a border alone
                // reads weakly against bright artwork from three metres away.
                // Lifting the surface behind it too is what makes the focused
                // card obvious at a glance rather than on inspection.
                val background by animateColorAsState(
                    if (focused) {
                        HubColors.Accent.copy(alpha = 0.32f)
                    } else {
                        HubColors.Surface.copy(alpha = 0.7f)
                    },
                    episodeFocusTween(),
                    label = "episode-background",
                )

                val watched = watchedEpisodes.contains(
                    "${showTmdbId}:${episode.seasonNumber}:${episode.episodeNumber}",
                )
                // Focus lifts the blur back to sharp — same as the mobile app —
                // so checking an unwatched episode never costs an extra click.
                //
                // Modifier.blur() is only available on API 31+; on older devices
                // we fall back to reduced alpha which achieves a similar visual
                // signal at minimal cost.
                val blurActive = dimUnwatched && !watched && !focused

                val episodeShape = RoundedCornerShape(HubShapes.Card)
                Column(
                    modifier = Modifier
                        .width(260.dp)
                        .clip(episodeShape)
                        .background(background)
                        .border(borderWidth, borderColor, episodeShape)
                        .combinedClickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = { onOpenDetails(episode) },
                            onLongClick = { onSelectSource(episode) },
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box {
                        episode.stillUrl?.let {
                        AsyncImage(
                            model = it,
                            contentDescription = episode.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(132.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .then(
                                    when {
                                        !blurActive -> Modifier
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                                            Modifier.blur(UNWATCHED_BLUR_RADIUS)
                                        else -> Modifier.alpha(UNWATCHED_ALPHA_FALLBACK)
                                    }
                                ),
                        )
                    }
                        if (watched) {
                            com.mdblisthub.tv.core.ui.component.WatchedBadge(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 10.dp, bottom = 10.dp),
                                size = 22.dp
                            )
                        }
                        episode.airDate
                            ?.let { formatAirDate(it, appLanguage) }
                            ?.let { formatted ->
                                Text(
                                    text = formatted,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = HubColors.Text,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp)
                                        .background(
                                            HubColors.Background.copy(alpha = 0.72f),
                                            RoundedCornerShape(6.dp),
                                        )
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                )
                            }
                    }
                    Text(
                        text = "${episode.episodeNumber}. ${episode.name}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (focused) HubColors.AccentSoft else HubColors.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.detail_episode_source_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (focused) HubColors.AccentSoft else HubColors.TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Shared by every focus-driven property on an episode card, so they move as one. */
private fun <T> episodeFocusTween(): FiniteAnimationSpec<T> =
    tween(durationMillis = 200, easing = FastOutSlowInEasing)

/** Gaussian blur radius applied to unwatched stills on API 31+ — see [EpisodeRow]. */
private val UNWATCHED_BLUR_RADIUS = 6.dp

/**
 * Alpha fallback for devices below API 31 that cannot run [Modifier.blur].
 * Achieves the same "this is still to watch" signal without blurring.
 */
private const val UNWATCHED_ALPHA_FALLBACK = 0.45f


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CastRow(current: com.mdblisthub.tv.core.model.MediaDetail, onCastClick: (CastMember) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.detail_cast),
            style = MaterialTheme.typography.titleLarge,
            color = HubColors.Text,
            modifier = Modifier.padding(start = HubDimens.ScreenPaddingHorizontal),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(horizontal = HubDimens.ScreenPaddingHorizontal),
            modifier = Modifier.focusRestorer(),
        ) {
            items(current.cast, key = { it.id }) { member ->
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .width(112.dp)
                        .clickable(interactionSource = interaction, indication = null) { onCastClick(member) }
                        .padding(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(92.dp)
                            .clip(CircleShape)
                            .background(HubColors.Surface)
                            .border(
                                width = if (focused) 2.5.dp else 0.dp,
                                color = if (focused) HubColors.Accent else HubColors.Border,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (member.profileUrl != null) {
                            AsyncImage(
                                model = member.profileUrl,
                                contentDescription = member.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        text = member.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (focused) HubColors.Text else HubColors.TextDim,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    member.character?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = (MaterialTheme.typography.labelSmall.fontSize.value - 2f).sp
                            ),
                            color = HubColors.TextFaint,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The cast popup — a biography borrowed from Wikipedia, since a TMDB credit
 * carries nothing past a name, a character and a photo.
 *
 * Keyed off `member` rather than reading `state.member` directly: the state
 * clears to null the instant [onDismiss] fires, and this composable would
 * otherwise have nothing left to render for the one frame before the `if`
 * guard around its call site catches up.
 */
@Composable
private fun CastBioOverlay(
    member: CastMember,
    state: CastBioState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Opening the popup does not move focus on its own — it was left sitting
    // on the (now-hidden) cast card behind the scrim, so OK just reopened the
    // same popup instead of ever reaching "Fechar". Same fix as the player's
    // subtitle/audio pickers.
    val closeButtonFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeButtonFocus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(HubColors.Background.copy(alpha = 0.75f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss),
    ) {
        val dialogShape = RoundedCornerShape(HubShapes.Dialog)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 560.dp)
                .heightIn(max = 520.dp)
                .clip(dialogShape)
                .background(HubColors.Surface.copy(alpha = 0.96f))
                .border(
                    HubStrokes.Hairline,
                    HubColors.Border.copy(alpha = HubEffects.SoftBorderAlpha),
                    dialogShape,
                )
                // Consumes taps inside the card so they do not also reach the
                // scrim's dismiss handler behind it.
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(HubColors.SurfaceStrong),
                    contentAlignment = Alignment.Center,
                ) {
                    val photoUrl = member.profileUrl ?: state.summary?.thumbnailUrl
                    if (photoUrl != null) {
                        AsyncImage(
                            model = photoUrl,
                            contentDescription = member.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(member.name, style = MaterialTheme.typography.headlineMedium, color = HubColors.Text)
                    member.character?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, color = HubColors.TextDim)
                    }
                }
            }

            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                    HubSpinner(size = 32.dp, modifier = Modifier.align(Alignment.Center))
                }
                state.error != null -> Text(
                    text = if (state.error == "WIKIPEDIA_NOT_FOUND") stringResource(R.string.detail_wikipedia_error, state.member?.name ?: "") else state.error,
                    style = MaterialTheme.typography.bodyLarge,
                    color = HubColors.TextDim,
                )
                state.summary != null -> Text(
                    text = state.summary.extract,
                    style = MaterialTheme.typography.bodyLarge,
                    color = HubColors.TextDim,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                state.summary?.pageUrl?.let { url ->
                    HubButton(text = stringResource(R.string.detail_wikipedia), onClick = { openUrl(context, url) })
                }
                HubButton(text = stringResource(R.string.detail_close), onClick = onDismiss, modifier = Modifier.focusRequester(closeButtonFocus))
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser to hand it to — nothing left to do.
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReviewsRow(reviews: List<Review>, onReviewClick: (Review) -> Unit) {
    if (reviews.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.detail_reviews),
            style = MaterialTheme.typography.titleLarge,
            color = HubColors.Text,
            modifier = Modifier.padding(start = HubDimens.ScreenPaddingHorizontal),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = HubDimens.ScreenPaddingHorizontal),
            modifier = Modifier.focusRestorer(),
        ) {
            items(reviews, key = { "${it.provider}-${it.author}-${it.updatedAt}" }) { review ->
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()

                val reviewShape = RoundedCornerShape(HubShapes.Card)
                Column(
                    modifier = Modifier
                        .width(360.dp)
                        .clip(reviewShape)
                        .background(if (focused) HubColors.SurfaceStrong else HubColors.Surface.copy(alpha = 0.7f))
                        .border(
                            width = if (focused) 2.5.dp else 0.dp,
                            color = if (focused) HubColors.Accent else HubColors.Border,
                            shape = reviewShape,
                        )
                        .clickable(interactionSource = interaction, indication = null) { onReviewClick(review) }
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = review.author,
                            style = MaterialTheme.typography.titleMedium,
                            color = HubColors.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        review.rating?.let {
                            Text(
                                // Locale-explicit: the no-arg overload formats
                                // with whatever the box's default is, so the
                                // same rating rendered "8.4" or "8,4" purely
                                // by device, out of step with every other
                                // number in this app.
                                text = "★ ${String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f", it)}",
                                style = MaterialTheme.typography.labelLarge,
                                color = HubColors.Imdb,
                            )
                        }
                        Text(
                            text = review.provider.label(),
                            style = MaterialTheme.typography.labelSmall,
                            color = review.provider.color(),
                        )
                    }
                    Text(
                        text = review.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HubColors.TextDim,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A full review is readable without leaving the detail screen. */
@Composable
private fun ReviewOverlay(review: Review, onDismiss: () -> Unit) {
    val closeButtonFocus = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    LaunchedEffect(review) {
        closeButtonFocus.requestFocus()
        scrollState.scrollTo(0)
        delay(REVIEW_READ_HOLD_MS)
        while (isActive) {
            val overflow = scrollState.maxValue
            if (overflow > 0) {
                // Match the synopsis reader: 190 ms per pixel, then a short
                // pause at the end before returning to the beginning.
                scrollState.animateScrollTo(
                    overflow,
                    animationSpec = tween(overflow * REVIEW_MS_PER_PX, easing = LinearEasing),
                )
                delay(REVIEW_BOTTOM_HOLD_MS)
                scrollState.animateScrollTo(0, animationSpec = tween(REVIEW_RETURN_MS))
                delay(REVIEW_RESTART_HOLD_MS)
            } else {
                delay(REVIEW_LAYOUT_RECHECK_MS)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(HubColors.Background.copy(alpha = 0.76f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
    ) {
        val dialogShape = RoundedCornerShape(HubShapes.Dialog)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.72f)
                .widthIn(max = 900.dp)
                .height(540.dp)
                .clip(dialogShape)
                .background(HubColors.Surface.copy(alpha = 0.98f))
                .border(HubStrokes.Hairline, HubColors.Border, dialogShape)
                // The card owns its clicks; only the surrounding scrim closes it.
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(review.author, style = MaterialTheme.typography.headlineMedium, color = HubColors.Text)
                    Text(review.provider.label(), style = MaterialTheme.typography.labelLarge, color = review.provider.color())
                }
                review.rating?.let {
                    Text(
                        text = "★ ${String.format(Locale.forLanguageTag("pt-BR"), "%.1f", it)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = HubColors.Imdb,
                    )
                }
            }

            Text(
                text = review.content,
                style = MaterialTheme.typography.bodyLarge,
                color = HubColors.TextDim,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState),
            )

            HubButton(
                text = stringResource(R.string.detail_close),
                onClick = onDismiss,
                modifier = Modifier.focusRequester(closeButtonFocus),
            )
        }
    }
}

private const val REVIEW_READ_HOLD_MS = 7_000L
private const val REVIEW_MS_PER_PX = 190
private const val REVIEW_BOTTOM_HOLD_MS = 4_000L
private const val REVIEW_RETURN_MS = 800
private const val REVIEW_RESTART_HOLD_MS = 3_000L
private const val REVIEW_LAYOUT_RECHECK_MS = 1_000L

private fun ReviewProvider.label(): String = when (this) {
    ReviewProvider.TRAKT -> "Trakt"
    ReviewProvider.TMDB -> "TMDB"
}

private fun ReviewProvider.color(): Color = when (this) {
    ReviewProvider.TRAKT -> HubColors.Trakt
    ReviewProvider.TMDB -> HubColors.Tmdb
}

/**
 * Opens the trailer in the YouTube app, or a browser if there is no YouTube
 * app to hand it to.
 *
 * The player embedded in this app cannot do it: ExoPlayer plays media files,
 * not YouTube's streaming protocol, and this build carries no resolver for
 * it. Handing off to an app built for exactly this is the native equivalent
 * of the `<iframe>` the web build uses — both delegate instead of
 * reimplementing a video platform.
 */
private fun openTrailer(context: Context, youtubeKey: String) {
    val appIntent = Intent(Intent.ACTION_VIEW, "vnd.youtube:$youtubeKey".toUri())
    val webIntent = Intent(
        Intent.ACTION_VIEW,
        "https://www.youtube.com/watch?v=$youtubeKey".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    try {
        context.startActivity(appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(webIntent)
        } catch (_: ActivityNotFoundException) {
            // A box with neither the YouTube app nor a browser: nothing left
            // to hand the trailer to.
        }
    }
}
