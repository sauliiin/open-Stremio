package com.mdblisthub.tv.core.ui.component

import androidx.compose.runtime.staticCompositionLocalOf
import com.mdblisthub.tv.core.model.CardArtwork
import com.mdblisthub.tv.core.model.MediaItem

/** App-provided bridge from a composed card to the lightweight artwork cache. */
class CardArtworkLoader(
    val artworkFor: (MediaItem) -> CardArtwork?,
    val request: (MediaItem) -> Unit,
)

val LocalCardArtworkLoader = staticCompositionLocalOf {
    CardArtworkLoader(
        artworkFor = { null },
        request = {},
    )
}
