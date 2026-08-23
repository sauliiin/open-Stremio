package com.mdblisthub.tv.player

import android.graphics.Color
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * The video output.
 *
 * `PlayerView` rather than a bare `SurfaceView`, because ExoPlayer delivers
 * subtitles as cue *data* — a raw surface shows the film with no subtitles at
 * all. PlayerView owns the surface, the subtitle renderer and the resize
 * modes together, so the "esticar" button and the yellow captions both come
 * from here rather than from the engine (which is where mpv kept them).
 *
 * Its own controls are switched off: this app's OSD is the one in
 * `PlayerScreen`, and two control layers competing for the same D-pad is
 * exactly the sort of thing that makes a remote feel broken.
 */
@OptIn(UnstableApi::class)
@Composable
fun ExoVideoSurface(
    controller: PlaybackController,
    scaleType: VideoScaleType,
    modifier: Modifier = Modifier,
    subtitleColor: ComposeColor = ComposeColor.Yellow,
    subtitleBackgroundOpacity: Float = 0f,
    cropToFill: Boolean = false,
) {
    val fixedZoom = scaleType == VideoScaleType.ZOOM && !cropToFill
    AndroidView(
        modifier = modifier.graphicsLayer {
            val zoom = if (fixedZoom) FIXED_ZOOM_SCALE else 1f
            scaleX = zoom
            scaleY = zoom
        },
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                // Letterbox/pillarbox bars must stay black regardless of the
                // selected theme. Under `RESIZE_MODE_FIT` the content frame
                // shrinks to the video's own aspect ratio and centers itself;
                // the view paints nothing behind that shrunk frame on its
                // own, so without this the gap falls through to whatever is
                // drawn beneath in the Compose z-order — `PlayerScreen`'s
                // root Box, tinted with `HubColors.Background`.
                setBackgroundColor(Color.BLACK)
                // SurfaceView, not TextureView: a set-top box gets a hardware
                // overlay out of it, which is both cheaper and the only path
                // to HDR passthrough on most of them.
                player = controller.player
                // Nothing about playback sends the system a key event, so
                // without this the box's own screensaver/sleep timer runs out
                // from under a film that is still very much playing — the
                // "app closes after a few minutes" this exists to prevent.
                keepScreenOn = true

                subtitleView?.apply {
                    // Bigger than default — legible from a couch over any
                    // backdrop. `setApplyEmbeddedStyles(false)` is what makes
                    // the app's own styling stick even on a subtitle that
                    // ships its own ASS styling, not just a plain SRT.
                    setApplyEmbeddedStyles(false)
                    setApplyEmbeddedFontSizes(false)
                    setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26f)
                }
            }
        },
        update = { view ->
            view.resizeMode = if (cropToFill) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                when (scaleType) {
                    VideoScaleType.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    VideoScaleType.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    VideoScaleType.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
            }
            // Applied here rather than in `factory`, which runs exactly once:
            // set there, the caption colour was frozen at whatever it was the
            // first time this composed, so changing the setting moved the
            // app-drawn overlay and left container subtitles on the old
            // colour — the same feature rendering two different ways.
            view.subtitleView?.setStyle(
                CaptionStyleCompat(
                    subtitleColor.toArgb(),
                    ComposeColor.Black.copy(
                        alpha = subtitleBackgroundOpacity.coerceIn(0f, 1f),
                    ).toArgb(),
                    Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                    Color.BLACK,
                    Typeface.DEFAULT,
                ),
            )
        },
        onRelease = { view ->
            // Leaving a destroyed view attached to a live player is how a back
            // press turns into a leaked surface.
            view.player = null
        },
    )
}

private const val FIXED_ZOOM_SCALE = 1.34f
