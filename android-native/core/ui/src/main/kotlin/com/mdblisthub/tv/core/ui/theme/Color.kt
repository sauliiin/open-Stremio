package com.mdblisthub.tv.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette is the web app's, arranged the way Kodi's Estuary arranges one:
 * a near-black field, one saturated accent for focus, and everything else in
 * greys so the artwork is the only colour on screen.
 */
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.mdblisthub.tv.core.model.HubThemeVariant

object HubColors {
    var Background by mutableStateOf(Color(0xFF07080C))
    var Surface by mutableStateOf(Color(0xFF121620))
    var SurfaceStrong by mutableStateOf(Color(0xFF1A1F2B))
    var Border by mutableStateOf(Color(0xFF232A38))

    var Accent by mutableStateOf(Color(0xFF7C5CFF))
    var AccentSoft by mutableStateOf(Color(0xFFB6A5FF))
    var Accent2 by mutableStateOf(Color(0xFF12D6C4))

    var Text by mutableStateOf(Color(0xFFF2F4F8))
    var TextDim by mutableStateOf(Color(0xFFA8B0C0))
    var TextFaint by mutableStateOf(Color(0xFF6C7688))

    val Imdb = Color(0xFFF5C518)
    val Fresh = Color(0xFF34D399)
    val Rotten = Color(0xFFF87171)
    val Metacritic = Color(0xFFFFCC33)
    val Trakt = Color(0xFFED1C24)
    /** Netflix brand red, also used by the themed metadata separators. */
    val NetflixRed = Color(0xFFE50914)
    val Tmdb = Color(0xFF01B4E4)
    val Letterboxd = Color(0xFF00E054)

    /**
     * Which palette is currently painted. Single source of truth: the two
     * booleans below used to be set independently at each branch, which made
     * "both true" and "stale after a partial edit" states expressible even
     * though they mean nothing.
     */
    var variant by mutableStateOf(HubThemeVariant.NORMAL)
        private set

    val isCyberpunk: Boolean get() = variant == HubThemeVariant.CYBERPUNK
    val isNetflixy: Boolean get() = variant == HubThemeVariant.NETFLIXY
    val isPrimefly: Boolean get() = variant == HubThemeVariant.PRIMEFLY

    /**
     * Paints a specific palette — what a cold start calls once the persisted
     * choice has been read, so the app comes back in the theme it was left in.
     */
    fun apply(target: HubThemeVariant) {
        when (target) {
            HubThemeVariant.NORMAL -> {
                Background = Color(0xFF07080C)
                Surface = Color(0xFF121620)
                SurfaceStrong = Color(0xFF1A1F2B)
                Border = Color(0xFF232A38)
                Accent = Color(0xFF7C5CFF)
                AccentSoft = Color(0xFFB6A5FF)
                Accent2 = Color(0xFF12D6C4)
                Text = Color(0xFFF2F4F8)
                TextDim = Color(0xFFA8B0C0)
                TextFaint = Color(0xFF6C7688)
            }
            HubThemeVariant.CYBERPUNK -> {
                Background = Color(0xFF050014)
                Surface = Color(0xFF14002e)
                SurfaceStrong = Color(0xFF2b005e)
                Border = Color(0xFFff0055)
                Accent = Color(0xFF00f3ff)
                AccentSoft = Color(0xFF99faff)
                Accent2 = Color(0xFFff00aa)
                Text = Color(0xFFfff000)
                TextDim = Color(0xFF00f3ff)
                TextFaint = Color(0xFFff0055)
            }
            // Dark and clean, carried by the one red accent.
            HubThemeVariant.NETFLIXY -> {
                Background = Color(0xFF000000)
                Surface = Color(0xFF121212)
                SurfaceStrong = Color(0xFF1F1F1F)
                Border = Color(0xFF333333)
                Accent = Color(0xFFE50914)
                AccentSoft = Color(0xFFFF5252)
                Accent2 = Color(0xFFE50914)
                Text = Color(0xFFFFFFFF)
                TextDim = Color(0xFFB3B3B3)
                TextFaint = Color(0xFF808080)
            }
            // Prime Video-inspired navy surfaces with its signature cyan.
            HubThemeVariant.PRIMEFLY -> {
                Background = Color(0xFF0F171E)
                Surface = Color(0xFF1A242F)
                SurfaceStrong = Color(0xFF243244)
                Border = Color(0xFF425265)
                Accent = Color(0xFF00A8E1)
                AccentSoft = Color(0xFF66CFF2)
                Accent2 = Color(0xFF14B8F4)
                Text = Color(0xFFFFFFFF)
                TextDim = Color(0xFFD5DBDB)
                TextFaint = Color(0xFF8197A4)
            }
        }
        variant = target
    }

    /**
     * Advances to the next palette and returns where it landed, so the caller
     * can persist the choice without having to re-derive it.
     */
    fun toggleTheme(): HubThemeVariant {
        val next = when (variant) {
            HubThemeVariant.NORMAL -> HubThemeVariant.CYBERPUNK
            HubThemeVariant.CYBERPUNK -> HubThemeVariant.NETFLIXY
            HubThemeVariant.NETFLIXY -> HubThemeVariant.PRIMEFLY
            HubThemeVariant.PRIMEFLY -> HubThemeVariant.NORMAL
        }
        apply(next)
        return next
    }
}
