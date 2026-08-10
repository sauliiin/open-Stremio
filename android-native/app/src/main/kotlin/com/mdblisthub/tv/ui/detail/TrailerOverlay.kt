package com.mdblisthub.tv.ui.detail

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn as UnstableOptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.core.data.repository.TrailerRepository
import com.mdblisthub.tv.core.ui.component.HubSpinner
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.ui.component.HubButton
import kotlinx.coroutines.delay

/**
 * The trailer, played over the detail screen instead of handing the viewer to
 * the YouTube app.
 *
 * Three tiers, in order, mirroring the fallback the user already relies on in
 * their Kodi setup:
 *
 * 1. IMDb's own trailer file — a plain MP4, resolved by [TrailerRepository]
 *    and played by Media3 exactly like anything else this app plays. No embed
 *    policy applies to it because it was never an embed.
 * 2. YouTube's IFrame embed, run in a `WebView`, for when IMDb doesn't have a
 *    trailer for this title. Not every video plays this way — an uploader can
 *    disable embedding, and YouTube separately refuses some embedded playback
 *    on television user agents — so this is a fallback, not the primary path.
 * 3. The YouTube app (or a browser) itself, one press away via
 *    [onOpenExternally], for whenever neither of the above panned out.
 */
@Composable
fun TrailerOverlay(
    imdbId: String?,
    youtubeKey: String?,
    title: String,
    trailers: TrailerRepository,
    onDismiss: () -> Unit,
    onOpenExternally: () -> Unit,
) {
    var stage by remember { mutableStateOf<TrailerStage>(TrailerStage.Resolving) }

    LaunchedEffect(imdbId, youtubeKey) {
        val mp4 = imdbId?.let { id -> runCatching { trailers.mp4For(id) }.getOrNull() }
        stage = when {
            mp4 != null -> TrailerStage.Mp4(mp4)
            youtubeKey != null -> TrailerStage.YoutubeEmbed
            else -> TrailerStage.Failed
        }
    }

    // Focus has to leave the "Trailer" button behind the scrim, or the D-pad
    // is talking to a control nobody can see — the same trap the subtitle and
    // cast overlays fell into. Re-requested on every stage change, not just
    // once at the start: the `WebView` and `PlayerView` below are native
    // Android views, and a `WebView` in particular grabs the real Android
    // view focus for itself the moment it's attached — Compose's own focus
    // state still says "Fechar", but the D-pad is no longer reaching it. When
    // that view is torn down on a failed tier, nothing was pulling focus back
    // until this ran again.
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(stage) { closeFocus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(HubColors.Background.copy(alpha = 0.92f)),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.82f)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = HubColors.Text,
                textAlign = TextAlign.Center,
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AndroidBlack)
                    .border(1.dp, HubColors.Border, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when (val current = stage) {
                    is TrailerStage.Resolving -> HubSpinner(size = 40.dp)

                    is TrailerStage.Mp4 -> {
                        var mp4Loading by remember { mutableStateOf(true) }
                        Mp4Trailer(
                            url = current.url,
                            onReady = { mp4Loading = false },
                            onError = {
                                // IMDb handed back a URL that didn't actually
                                // play — a signed link that expired between
                                // lookup and playback, most likely, or one
                                // that just stalled without ever buffering.
                                // Same fallback as no URL at all.
                                stage = if (youtubeKey != null) TrailerStage.YoutubeEmbed else TrailerStage.Failed
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (mp4Loading) HubSpinner(size = 40.dp)
                    }

                    is TrailerStage.YoutubeEmbed -> {
                        var embedLoading by remember { mutableStateOf(true) }
                        YoutubeEmbed(
                            youtubeKey = youtubeKey!!,
                            onReady = { embedLoading = false },
                            onError = { stage = TrailerStage.Failed },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (embedLoading) HubSpinner(size = 40.dp)
                    }

                    is TrailerStage.Failed -> Text(
                        text = "Não consegui carregar o trailer aqui.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = HubColors.TextDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }

            Spacer(Modifier.height(0.dp))
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HubButton(
                    text = "Fechar",
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(closeFocus),
                )
                if (youtubeKey != null) {
                    HubButton(text = "Abrir no YouTube", onClick = onOpenExternally)
                }
            }
        }
    }
}

private sealed interface TrailerStage {
    data object Resolving : TrailerStage
    data class Mp4(val url: String) : TrailerStage
    data object YoutubeEmbed : TrailerStage
    data object Failed : TrailerStage
}

private val AndroidBlack = androidx.compose.ui.graphics.Color.Black

/**
 * Tier 1: IMDb's own file, played the same way as anything else in this app
 * — a bare `ExoPlayer` here rather than the shared [com.mdblisthub.tv.player.PlaybackController],
 * because that controller exists to walk a ranked list of *candidate* stream
 * sources for the main film; a trailer is a single already-known URL, so none
 * of that machinery applies.
 */
@UnstableOptIn(UnstableApi::class)
@Composable
private fun Mp4Trailer(
    url: String,
    onReady: () -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) onReady()
            }
            override fun onPlayerError(error: PlaybackException) = onError()
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // A stalled connection doesn't always surface as `onPlayerError` — it can
    // just sit in STATE_BUFFERING forever. Past this, black-with-a-spinner
    // has stopped being "still loading" and started being "broken", so it's
    // treated the same as an explicit failure.
    LaunchedEffect(player) {
        delay(15_000)
        if (player.playbackState != Player.STATE_READY) onError()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                this.player = player
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                // Nothing here takes D-pad input — the OSD is Fechar/Abrir
                // below, not this view — so it must never be the thing that
                // ends up holding Android's actual view focus.
                isFocusable = false
                isFocusableInTouchMode = false
            }
        },
        onRelease = { view -> view.player = null },
    )
}

/**
 * Tier 2: YouTube's own embed player.
 *
 * `autoplay=1` needs `mediaPlaybackRequiresUserGesture = false` to be honoured
 * — without it the player loads and then sits there waiting for a tap that a
 * remote cannot give it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YoutubeEmbed(
    youtubeKey: String,
    onReady: () -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(AndroidColor.BLACK)

                // A `WebView` grabs real Android view focus for itself the
                // instant it's attached — there's no touch input to give it
                // here anyway, autoplay does the work, and letting it take
                // focus is what left the D-pad unable to reach Fechar/Abrir.
                isFocusable = false
                isFocusableInTouchMode = false

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true

                // A desktop user agent, deliberately. Left alone, the WebView
                // announces itself as an Android TV, and YouTube answers
                // embedded playback on a TV with "error 152 — video
                // unavailable" for videos that play fine anywhere else:
                // televisions are meant to use their own YouTube app, not an
                // embed. Claiming to be a browser is what gets the embed
                // treated as an embed.
                settings.userAgentString =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) = onReady()

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        // Sub-resources fail all the time on a page this size;
                        // only the main document failing means no trailer.
                        if (request?.isForMainFrame == true) onError()
                    }
                }

                // Local HTML with the embed in an `<iframe>`, *not* the embed
                // URL loaded directly — that route answers "Error 153, video
                // player configuration error", because a WebView pointed
                // straight at the embed sends no `Referer` and YouTube
                // refuses to play for an origin it cannot see. Handing
                // `loadDataWithBaseURL` a youtube.com base gives the page an
                // origin, so the iframe's request carries one.
                loadDataWithBaseURL(
                    "https://www.youtube.com",
                    embedHtml(youtubeKey),
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
        // Guaranteed to run after Compose has detached this view from its
        // parent — unlike a sibling `DisposableEffect`, which the framework
        // may dispose *before* `AndroidView`'s own teardown, since effects
        // unwind in reverse registration order. Destroying a WebView that is
        // still attached is undefined behavior the platform docs warn
        // against; this is what avoided that on the way out of the screen.
        // Left alive and not torn down at all, it also keeps playing audio
        // over whatever comes next.
        onRelease = { view ->
            view.loadUrl("about:blank")
            view.stopLoading()
            view.destroy()
        },
    )
}

/**
 * The page the WebView actually loads.
 *
 * `playsinline=1` keeps playback inside this view rather than handing off to
 * a fullscreen surface the overlay does not manage, and `rel=0` stops the
 * grid of unrelated videos from taking over when the trailer ends.
 */
private fun embedHtml(youtubeKey: String): String = """
    <!DOCTYPE html>
    <html>
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html, body { margin:0; padding:0; height:100%; background:#000; overflow:hidden; }
          iframe { border:0; display:block; width:100%; height:100%; }
        </style>
      </head>
      <body>
        <iframe
          src="https://www.youtube.com/embed/$youtubeKey?autoplay=1&playsinline=1&rel=0&modestbranding=1"
          allow="autoplay; encrypted-media"
          allowfullscreen></iframe>
      </body>
    </html>
""".trimIndent()
