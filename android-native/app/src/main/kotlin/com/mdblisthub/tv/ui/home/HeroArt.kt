package com.mdblisthub.tv.ui.home

import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubMotion
import kotlinx.coroutines.delay

/**
 * The hero artwork block: a backdrop that gives way to an autoplaying trailer.
 *
 * **Why the edges are feathered rather than rounded.** This block sits over the
 * app background with rows below it and a title card to its left, and a hard
 * rectangle there reads as a video window pasted onto the page — the one thing
 * a "big screen" home layout must not look like. Rounding the corners does not
 * fix that; it just makes a smaller rectangle. What actually dissolves the
 * boundary is a pair of long, multi-stop ramps: one runs into the title card
 * on the left and one into the rows below. Both are painted once over the
 * complete media stack, so the edge cannot change shape while the still and
 * trailer crossfade. The right edge is the edge of the screen, not a meeting
 * point, and stays hard on purpose.
 *
 * **The crossfade.** [backdropUrl] and [trailerUrl] deliberately occupy the
 * same rectangle, stacked. The still is what the viewer sees while the trailer
 * is being resolved and buffered; it fades only once the player reports its
 * first frame is actually up ([Player.STATE_READY]), never on the URL merely
 * arriving. Fading on the URL is what produces the black gap that makes an
 * auto-preview feel broken — the still leaves before the video can replace it.
 */
@OptIn(UnstableApi::class)
@Composable
fun HeroArt(
    backdropUrl: String?,
    trailerUrl: String?,
    modifier: Modifier = Modifier,
    /** Muting is the caller's call; see the home screen for why it plays aloud. */
    muted: Boolean = false,
    /** Identity of the focused card that owns [trailerUrl]. */
    trailerItemKey: String? = null,
    /** Reports the first real video frame, rather than URL resolution or buffering. */
    onTrailerPlaybackChanged: (String?, Boolean) -> Unit = { _, _ -> },
) {
    var trailerPlaying by remember(trailerUrl) { mutableStateOf(false) }
    DisposableEffect(trailerUrl, trailerItemKey) {
        onDispose { onTrailerPlaybackChanged(trailerItemKey, false) }
    }

    // Only the *video* fades in. The still fades out against it, so for the
    // length of the transition both are painted and neither edge shows.
    val trailerAlpha by animateFloatAsState(
        targetValue = if (trailerPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = CROSSFADE_MS),
        label = "hero-trailer-alpha",
    )

    Box(modifier = modifier.clipToBounds()) {
        Crossfade(
            targetState = backdropUrl,
            animationSpec = tween(
                durationMillis = HubMotion.Scene,
                easing = HubMotion.StandardEasing,
            ),
            label = "hero-backdrop-crossfade",
            modifier = Modifier.matchParentSize(),
        ) { currentBackdrop ->
            if (currentBackdrop != null) {
                AsyncImage(
                    model = currentBackdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // `Crop`'s own default is `Center`, which crops the same
                    // number of pixels off the top as off the bottom. TMDB
                    // backdrops are near-16:9 already and this block is
                    // considerably narrower than that, so the vertical crop is
                    // the heavy one — and backdrop compositions routinely put a
                    // face in the upper half of the frame, which a centered crop
                    // then cuts straight through. `TopStart` spends that same
                    // crop budget on the bottom margin instead, where these
                    // images are overwhelmingly closer to empty.
                    alignment = androidx.compose.ui.Alignment.TopStart,
                    modifier = Modifier
                        .fillMaxSize()
                        // Held at full opacity until the trailer is genuinely
                        // on screen, then taken out underneath it.
                        .alpha(1f - trailerAlpha),
                )
            }
        }

        if (trailerUrl != null) {
            TrailerSurface(
                url = trailerUrl,
                muted = muted,
                onFirstFrame = {
                    trailerPlaying = true
                    onTrailerPlaybackChanged(trailerItemKey, true)
                },
                onFailed = {
                    trailerPlaying = false
                    onTrailerPlaybackChanged(trailerItemKey, false)
                },
                modifier = Modifier
                    .matchParentSize()
                    .alpha(trailerAlpha),
            )
        }

        // Nuvio-style edge treatment: several deliberately spaced stops
        // create a feather that reads like optical softness, without paying
        // for a live blur over a video surface. Drawing it once above both
        // media layers also keeps the silhouette perfectly still during the
        // trailer transition.
        HeroEdgeVeil(Modifier.matchParentSize())
    }
}

/**
 * A bare [ExoPlayer] on a [PlayerView], built per URL and released with it.
 *
 * Deliberately not the shared `PlaybackController`: that exists to walk a
 * ranked queue of candidate sources for the feature being watched, with
 * failover, resume points and a stall watchdog. A trailer is one already-known
 * MP4 that nobody is committed to — if it fails, the right answer is to say
 * nothing and leave the backdrop up, which is what [onFailed] does.
 */
@OptIn(UnstableApi::class)
@Composable
private fun TrailerSurface(
    url: String,
    muted: Boolean,
    onFirstFrame: () -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            // A trailer that outlives its own length would loop into a second
            // viewing nobody asked for; one pass is the whole intent.
            repeatMode = Player.REPEAT_MODE_OFF
            volume = if (muted) 0f else TRAILER_VOLUME
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) onFirstFrame()
                if (state == Player.STATE_ENDED) onFailed()
            }

            override fun onPlayerError(error: PlaybackException) = onFailed()
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // A dead link does not always surface as `onPlayerError` — it can sit in
    // STATE_BUFFERING indefinitely. Past this the backdrop simply stays, which
    // is a silent, correct outcome rather than a black rectangle.
    LaunchedEffect(player) {
        delay(READY_TIMEOUT_MS)
        if (player.playbackState != Player.STATE_READY) onFailed()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Inflated, not constructed: the layout sets `surface_type` to a
            // texture view, which has no runtime setter and is the whole
            // reason the edge veil can sit above the video at all. See the XML.
            val view = android.view.LayoutInflater
                .from(ctx)
                .inflate(R.layout.view_hero_trailer, null) as PlayerView

            view.apply {
                this.player = player
                // Crop, not fit: letterbox bars inside a feathered block would
                // put back the hard horizontal edges the veil exists to remove.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                // Nothing here answers the D-pad — the cards below do — so it
                // must never take Android's view focus away from them.
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        onRelease = { view -> view.player = null },
    )
}

/** Slightly under unity: a preview should never be louder than the app itself. */
private const val TRAILER_VOLUME = 0.65f

private const val CROSSFADE_MS = 900

private const val READY_TIMEOUT_MS = 12_000L

@Composable
private fun HeroEdgeVeil(modifier: Modifier = Modifier) {
    val background = HubColors.Background

    Box(
        modifier.drawWithCache {
            val leftFadeEndX = size.width * 0.52f
            val bottomFadeStartY = size.height * 0.60f
            val left = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.00f to background.copy(alpha = 0.99f),
                    0.22f to background.copy(alpha = 0.91f),
                    0.48f to background.copy(alpha = 0.62f),
                    0.76f to background.copy(alpha = 0.24f),
                    1.00f to Color.Transparent,
                ),
                startX = 0f,
                endX = leftFadeEndX,
            )
            val bottom = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to Color.Transparent,
                    0.30f to background.copy(alpha = 0.22f),
                    0.62f to background.copy(alpha = 0.58f),
                    0.84f to background.copy(alpha = 0.86f),
                    1.00f to background,
                ),
                startY = bottomFadeStartY,
                endY = size.height,
            )
            onDrawBehind {
                drawRect(
                    brush = left,
                    size = Size(leftFadeEndX, size.height),
                )
                drawRect(
                    brush = bottom,
                    topLeft = Offset(0f, bottomFadeStartY),
                    size = Size(size.width, size.height - bottomFadeStartY),
                )
            }
        },
    )
}
