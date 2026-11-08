package com.mdblisthub.tv.ui.home

import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.launch

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
    /**
     * The instant a backdrop starts dissolving in — its image decoded, not
     * merely requested. The home hero changes its text on this, so the two
     * move as one.
     */
    onBackdropRevealed: (String) -> Unit = {},
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
    val backdropVeilAlpha = remember { Animatable(1f) }
    LaunchedEffect(trailerPlaying) {
        if (trailerPlaying) {
            // The backdrop shadow leaves only when the first trailer frame is
            // actually visible. This is intentionally one-way: when playback
            // ends, restoring it with another two-second animation makes the
            // fade appear to belong to the end of the trailer.
            backdropVeilAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = BACKDROP_VEIL_FADE_MS),
            )
        } else {
            backdropVeilAlpha.snapTo(1f)
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        // The still backdrop is intentionally more compact than the trailer.
        // Keeping this transform inside HeroArt changes only its visual
        // footprint; the hero's measured layout and the trailer size remain
        // untouched. Anchoring at the top-right preserves the composition
        // nearest the title copy while leaving the page-side edge to dissolve.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = BACKDROP_SCALE
                    scaleY = BACKDROP_SCALE
                    transformOrigin = TransformOrigin(1f, 0f)
                },
        ) {
            BackdropDissolve(
                url = backdropUrl,
                onRevealed = onBackdropRevealed,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = 1f - trailerAlpha },
            )

            // The backdrop owns its own veil because its footprint is smaller
            // than the trailer's. This keeps the reduced still from retaining
            // a full-size rectangular edge against the page background.
            HeroEdgeVeil(
                Modifier
                    .matchParentSize()
                    .alpha(backdropVeilAlpha.value),
            )
        }

        if (trailerUrl != null) {
            Box(Modifier.matchParentSize()) {
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

                // The trailer keeps the current full-size footprint and has
                // its own Netflix-style feather during the crossfade.
                HeroEdgeVeil(
                    Modifier
                        .matchParentSize()
                        .alpha(trailerAlpha),
                )
            }
        }
    }
}

/**
 * The backdrop's change of title: a true dissolve, the way Prime Video's TV
 * hero does it.
 *
 * Not a `Crossfade` over `AsyncImage`, which is what this used to be: that
 * starts fading the old picture out the instant the URL changes, while the new
 * one is still being fetched, so every move dipped to the bare page background
 * and the new art then popped in. Here each URL is its own layer stacked on
 * top of the last; the new layer stays invisible until its image has actually
 * decoded, only then fades in *over* the old one, and the layers beneath are
 * dropped once it is fully opaque. The old picture is therefore on screen
 * until the new one completely covers it.
 *
 * Layers are `key`ed by URL so a layer that finishes its fade keeps its
 * already-decoded `AsyncImage` instead of being re-requested on promotion.
 */
@Composable
private fun BackdropDissolve(
    url: String?,
    modifier: Modifier = Modifier,
    onRevealed: (String) -> Unit = {},
) {
    val layers = remember { mutableStateListOf<BackdropLayer>() }
    val currentOnRevealed by rememberUpdatedState(onRevealed)
    val scope = rememberCoroutineScope()
    val fade = tween<Float>(durationMillis = BACKDROP_DISSOLVE_MS, easing = HubMotion.StandardEasing)

    LaunchedEffect(url) {
        if (url == null) {
            // Nothing to show: let whatever is up fade away rather than cut.
            layers.toList().forEach { layer ->
                scope.launch {
                    layer.alpha.animateTo(0f, fade)
                    layers.remove(layer)
                }
            }
            return@LaunchedEffect
        }
        val existing = layers.firstOrNull { it.url == url }
        if (existing != null) {
            // Back to a picture still in the stack (A → B → A inside one
            // fade): fade out everything over it instead of reloading it.
            val index = layers.indexOf(existing)
            layers.drop(index + 1).forEach { above ->
                scope.launch {
                    above.alpha.animateTo(0f, fade)
                    layers.remove(above)
                }
            }
            if (existing.loaded) {
                currentOnRevealed(url)
                scope.launch { existing.alpha.animateTo(1f, fade) }
            }
            return@LaunchedEffect
        }
        // Layers that never got as far as showing anything are not worth
        // decoding any more: the viewer has already moved past them.
        layers.removeAll { !it.loaded && it.alpha.value == 0f }
        layers.add(BackdropLayer(url))
    }

    Box(modifier) {
        layers.forEach { layer ->
            key(layer.url) {
                AsyncImage(
                    model = layer.url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = androidx.compose.ui.Alignment.TopStart,
                    // A failed image reveals too: the old title's art must not
                    // linger behind the new title's text.
                    onState = { state ->
                        val settled = state is coil3.compose.AsyncImagePainter.State.Success ||
                            state is coil3.compose.AsyncImagePainter.State.Error
                        if (settled && !layer.loaded) {
                            layer.loaded = true
                            currentOnRevealed(layer.url)
                            scope.launch {
                                // The first picture of all has nothing to
                                // dissolve from and simply fades up.
                                layer.alpha.animateTo(1f, fade)
                                val index = layers.indexOf(layer)
                                if (index > 0 && layers.lastOrNull() == layer) {
                                    layers.removeRange(0, index)
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = layer.alpha.value },
                )
            }
        }
    }
}

private class BackdropLayer(val url: String) {
    val alpha = Animatable(0f)
    var loaded = false
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
            val parent = android.widget.FrameLayout(ctx)
            val view = android.view.LayoutInflater.from(ctx)
                .inflate(R.layout.view_hero_trailer, parent, false) as PlayerView

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

/** One title's backdrop dissolving into the next's. */
private const val BACKDROP_DISSOLVE_MS = 500

/** Backdrop-only reduction; the autoplay trailer remains full-size. */
private const val BACKDROP_SCALE = 0.9f

/** The backdrop shadow lingers softly as the trailer takes over. */
private const val BACKDROP_VEIL_FADE_MS = 2_000

private const val READY_TIMEOUT_MS = 12_000L

@Composable
private fun HeroEdgeVeil(modifier: Modifier = Modifier) {
    val background = HubColors.Background

    Box(
        modifier.drawWithCache {
            val leftFadeEndX = size.width * 0.58f
            // Keep the image clear farther down its right-hand side. Starting
            // this veil halfway up made the feather read as a dark right edge
            // before it had room to dissolve into the rows.
            val bottomFadeStartY = size.height * 0.64f
            val left = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.00f to background,
                    0.20f to background.copy(alpha = 0.94f),
                    0.45f to background.copy(alpha = 0.66f),
                    0.72f to background.copy(alpha = 0.28f),
                    1.00f to Color.Transparent,
                ),
                startX = 0f,
                endX = leftFadeEndX,
            )
            val bottom = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to Color.Transparent,
                    0.28f to background.copy(alpha = 0.24f),
                    0.56f to background.copy(alpha = 0.60f),
                    0.80f to background.copy(alpha = 0.88f),
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
