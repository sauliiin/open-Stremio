package com.mdblisthub.tv.core.ui.component

import androidx.compose.runtime.staticCompositionLocalOf
import com.mdblisthub.tv.core.model.LandscapeArtwork
import com.mdblisthub.tv.core.model.MediaItem

/** App-provided bridge from a composed card to the lightweight artwork cache. */
class LandscapeArtworkLoader(
    val artworkFor: (MediaItem) -> LandscapeArtwork?,
    val request: (MediaItem) -> Unit,
)

val LocalLandscapeArtworkLoader = staticCompositionLocalOf {
    LandscapeArtworkLoader(
        artworkFor = { null },
        request = {},
    )
}
