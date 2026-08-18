package com.mdblisthub.tv.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens

/**
 * One title in a row.
 *
 * Focus is expressed two ways at once — an accent border and a brightening
 * title, no scale — because on a television the viewer is metres away and a
 * single cue is easy to lose. A per-theme zoom was tried (including one
 * scoped to just Netflixy) and dropped both times: it fought the grid's own
 * spacing (a scaled-up card overlaps its neighbours) and read as restless
 * when moving quickly through a row; the border alone is enough.
 */
@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocusRequester: FocusRequester? = null,
    onFocused: (MediaItem) -> Unit = {},
    progressPercent: Float? = null,
    /**
     * Held OK, the one secondary gesture a remote has. Null leaves the card
     * with a plain click, which is what every row but "continue watching"
     * wants — a card that reacts to being held without doing anything is
     * worse than one that does not react at all.
     */
    onLongClick: (() -> Unit)? = null,
    isWatched: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val targetBorderWidth = if (focused) {
        if (HubColors.isCyberpunk) 4.5.dp else 2.5.dp
    } else 0.dp
    val borderWidth by animateDpAsState(
        targetValue = targetBorderWidth,
        animationSpec = posterFocusTween(),
        label = "poster-border-width",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) HubColors.Accent else HubColors.Border,
        animationSpec = posterFocusTween(),
        label = "poster-border-color",
    )
    val titleColor by animateColorAsState(
        targetValue = if (focused) HubColors.Text else HubColors.TextDim,
        animationSpec = posterFocusTween(),
        label = "poster-title-color",
    )
    val displayTitle = if (HubColors.isPrimefly && item.year != null &&
        !item.title.endsWith("(${item.year})")
    ) {
        "${item.title} (${item.year})"
    } else {
        item.title
    }
    val landscapeArtworkLoader = LocalLandscapeArtworkLoader.current
    val resolvedArtwork = landscapeArtworkLoader.artworkFor(item)

    // LazyRow only composes the visible cards (plus its small layout buffer),
    // so composition is the precise signal to start every image on screen.
    // Focus remains a navigation concern and no longer gates artwork.
    LaunchedEffect(HubColors.isPrimefly, item.key, item.landscapeUrl) {
        if (HubColors.isPrimefly && item.landscapeUrl == null && resolvedArtwork == null) {
            landscapeArtworkLoader.request(item)
        }
    }

    LaunchedEffect(focused) {
        if (focused) onFocused(item)
    }

    Column(
        modifier = modifier.width(HubDimens.PosterWidth),
        verticalArrangement = Arrangement.spacedBy(if (HubColors.isPrimefly) 4.dp else 8.dp),
    ) {
        val cornerRadius = if (HubColors.isCyberpunk) 0.dp else 10.dp
        Box(
            Modifier
                .width(HubDimens.PosterWidth)
                .height(HubDimens.PosterHeight)
                .let {
                    if (HubColors.isCyberpunk && focused) {
                        it.animatedCyberpunkGlow(shape = RoundedCornerShape(cornerRadius))
                    } else it
                }
                .clip(RoundedCornerShape(cornerRadius))
                .background(HubColors.Surface)
                .let {
                    if (!HubColors.isCyberpunk || !focused) {
                        it.border(width = borderWidth, color = borderColor, shape = RoundedCornerShape(cornerRadius))
                    } else it
                }
                .let {
                    if (initialFocusRequester != null) it.focusRequester(initialFocusRequester) else it
                }
                // `clickable` is what makes it focusable *and* what turns the
                // remote's centre key into a click; adding `focusable` beside
                // it would register two focus targets for one card.
                .let { base ->
                    if (onLongClick == null) {
                        base.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        base.combinedClickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    }
                },
        ) {
            val artworkUrl = if (HubColors.isPrimefly) {
                // Never crop a portrait poster into a landscape card.
                resolvedArtwork?.landscapeUrl
                    ?: item.landscapeUrl
                    ?: resolvedArtwork?.backdropUrl
                    ?: item.backdropUrl
            } else {
                item.posterUrl
            }
            if (artworkUrl != null) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // No poster is common on obscure titles; a readable fallback
                // beats an empty rectangle the eye reads as a loading error.
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = HubColors.TextFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(10.dp),
                )
            }

            val watched = progressPercent?.coerceIn(0f, 100f)?.takeIf { it > 0f }
            item.score?.takeIf { it > 0 }?.let { score ->
                ScoreBadge(
                    score = score,
                    modifier = Modifier.align(Alignment.BottomStart).padding(
                        start = if (HubColors.isCyberpunk) 0.dp else 6.dp,
                        end = if (HubColors.isCyberpunk) 0.dp else 6.dp,
                        top = if (HubColors.isCyberpunk) 0.dp else 6.dp,
                        bottom = when {
                            HubColors.isCyberpunk -> 0.dp
                            watched != null -> 18.dp
                            else -> 6.dp
                        },
                    ),
                )
            }


            if (isWatched) {
                WatchedBadge(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = if (HubColors.isCyberpunk) 4.dp else 8.dp,
                            bottom = when {
                                HubColors.isCyberpunk && watched != null -> 20.dp
                                HubColors.isCyberpunk -> 4.dp
                                watched != null -> 20.dp
                                else -> 8.dp
                            }
                        ),
                    size = 16.dp
                )
            }

            watched?.let { progress ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            start = if (HubColors.isCyberpunk && item.score?.let { it > 0 } == true) 36.dp else 8.dp,
                            end = 8.dp,
                            top = 8.dp,
                            bottom = 8.dp,
                        )
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(HubColors.Text.copy(alpha = 0.32f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress / 100f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(HubColors.Accent),
                    )
                }
            }
        }

        Text(
            text = displayTitle,
            style = if (HubColors.isCyberpunk) {
                MaterialTheme.typography.labelSmall
            } else {
                MaterialTheme.typography.labelLarge
            },
            color = titleColor,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(HubDimens.PosterWidth),
        )
    }
}

/** Shared by every focus-driven property on the card, so they move as one. */
private fun <T> posterFocusTween() = tween<T>(durationMillis = 200, easing = FastOutSlowInEasing)

/**
 * Hoisted out of [ScoreBadge]: `forLanguageTag` parses the tag every call, and
 * the badge is drawn once per visible card in every row on the home screen.
 */
private val ScoreLocale: java.util.Locale = java.util.Locale.forLanguageTag("pt-BR")

@Composable
private fun ScoreBadge(score: Int, modifier: Modifier = Modifier) {
    val cornerRadius = if (HubColors.isCyberpunk) 0.dp else 6.dp
    Box(
        modifier
            .size(
                width = if (HubColors.isCyberpunk) 32.dp else 40.dp,
                height = if (HubColors.isCyberpunk) 18.dp else 22.dp,
            )
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.verticalGradient(
                    listOf(HubColors.Background.copy(alpha = 0.85f), HubColors.Background)
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = remember(score) { String.format(ScoreLocale, "%.1f", score / 10.0) },
            style = if (HubColors.isCyberpunk) {
                MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
            } else {
                MaterialTheme.typography.labelSmall
            },
            color = HubColors.Imdb,
        )
    }
}

/**
 * The sweep's colours, as packed ARGB ints.
 *
 * Hoisted because these used to be three `Color.parseColor("#…")` calls
 * *inside* the draw — three string parses per frame, sixty times a second,
 * for a value that has never changed.
 */
private val CyberpunkGlowColors = intArrayOf(
    0xFF9D00FF.toInt(),
    0xFF00F3FF.toInt(),
    0xFF9D00FF.toInt(),
)

/**
 * The rotating neon halo the cyberpunk theme puts behind a focused card.
 *
 * Everything expensive — the shader, the two blur filters, the three paints
 * and the shape's outline — is built once per size in [drawWithCache] and
 * then reused; only the rotation changes per frame, and it is applied by
 * mutating the shader's local matrix in place. A focused card therefore costs
 * no allocations per frame, where it used to cost ten: a `SweepGradient`, a
 * `Matrix`, three `Paint`s, two `BlurMaskFilter`s and three parsed colour
 * strings, all discarded sixty times a second on a box whose GC has very
 * little headroom to spare.
 *
 * `@Composable` rather than `composed { }` for a related reason: `composed`
 * is opaque to Compose's modifier comparison, so it re-materialises on every
 * recomposition of every card using it.
 *
 * The paints are `android.graphics.Paint` directly rather than Compose paints
 * unwrapped with `asFrameworkPaint()`, which is deprecated — the draw was
 * already going through `nativeCanvas` for the rounded case, so this only
 * makes the other two branches agree with it.
 */
@Composable
fun Modifier.animatedCyberpunkGlow(shape: Shape = RectangleShape): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "glow_rot")
    // Deliberately not delegated with `by`: a delegated read would happen
    // inside the cache block below and invalidate it every frame, rebuilding
    // precisely the objects the cache exists to keep. It is read in the draw
    // instead, which is the only place it changes anything.
    val rotation = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "glow_rot_anim",
    )

    return this.drawWithCache {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val shader = android.graphics.SweepGradient(centerX, centerY, CyberpunkGlowColors, null)
        val matrix = android.graphics.Matrix()

        // One shader behind all three paints, as before: rotating it moves the
        // outer halo, the inner bloom and the border as a single piece.
        // Anti-aliasing is set explicitly because a bare framework `Paint`
        // defaults to off, where the Compose `Paint` these replaced had it on.
        val paints = arrayOf(
            android.graphics.Paint().apply {
                isAntiAlias = true
                this.shader = shader
                maskFilter = android.graphics.BlurMaskFilter(
                    16.dp.toPx() * 1.5f,
                    android.graphics.BlurMaskFilter.Blur.NORMAL,
                )
            },
            android.graphics.Paint().apply {
                isAntiAlias = true
                this.shader = shader
                maskFilter = android.graphics.BlurMaskFilter(
                    16.dp.toPx() * 0.5f,
                    android.graphics.BlurMaskFilter.Blur.NORMAL,
                )
            },
            android.graphics.Paint().apply {
                isAntiAlias = true
                this.shader = shader
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 4.5.dp.toPx()
            },
        )
        val outline = shape.createOutline(size, layoutDirection, this)

        onDrawBehind {
            matrix.setRotate(rotation.value, centerX, centerY)
            shader.setLocalMatrix(matrix)

            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                when (outline) {
                    is Outline.Rectangle -> {
                        val r = outline.rect
                        for (i in paints.indices) {
                            native.drawRect(r.left, r.top, r.right, r.bottom, paints[i])
                        }
                    }
                    is Outline.Rounded -> {
                        val r = outline.roundRect
                        for (i in paints.indices) {
                            native.drawRoundRect(
                                r.left, r.top, r.right, r.bottom,
                                r.bottomLeftCornerRadius.x, r.bottomLeftCornerRadius.y,
                                paints[i],
                            )
                        }
                    }
                    is Outline.Generic -> {
                        val p = outline.path.asAndroidPath()
                        for (i in paints.indices) native.drawPath(p, paints[i])
                    }
                }
            }
        }
    }
}
