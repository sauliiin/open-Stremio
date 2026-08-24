package com.mdblisthub.tv.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.mdblisthub.tv.core.ui.R

/**
 * Type sized for a couch.
 *
 * Everything here is larger than a phone equivalent, because the reference is
 * a 1080p panel three metres away: what reads as generous on a monitor is
 * barely legible from a sofa, and Kodi's skins are sized the same way.
 */
private val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val BaseHubTypography = Typography(
    displayLarge = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)

/** Applies Inter to every Material text role while preserving the TV-specific scale above. */
private val HubTypography = BaseHubTypography.copy(
    displayLarge = BaseHubTypography.displayLarge.copy(fontFamily = InterFontFamily),
    displayMedium = BaseHubTypography.displayMedium.copy(fontFamily = InterFontFamily),
    displaySmall = BaseHubTypography.displaySmall.copy(fontFamily = InterFontFamily),
    headlineLarge = BaseHubTypography.headlineLarge.copy(fontFamily = InterFontFamily),
    headlineMedium = BaseHubTypography.headlineMedium.copy(fontFamily = InterFontFamily),
    headlineSmall = BaseHubTypography.headlineSmall.copy(fontFamily = InterFontFamily),
    titleLarge = BaseHubTypography.titleLarge.copy(fontFamily = InterFontFamily),
    titleMedium = BaseHubTypography.titleMedium.copy(fontFamily = InterFontFamily),
    titleSmall = BaseHubTypography.titleSmall.copy(fontFamily = InterFontFamily),
    bodyLarge = BaseHubTypography.bodyLarge.copy(fontFamily = InterFontFamily),
    bodyMedium = BaseHubTypography.bodyMedium.copy(fontFamily = InterFontFamily),
    bodySmall = BaseHubTypography.bodySmall.copy(fontFamily = InterFontFamily),
    labelLarge = BaseHubTypography.labelLarge.copy(fontFamily = InterFontFamily),
    labelMedium = BaseHubTypography.labelMedium.copy(fontFamily = InterFontFamily),
    labelSmall = BaseHubTypography.labelSmall.copy(fontFamily = InterFontFamily),
)

/** Overscan inset. TVs still crop the edges, and Kodi budgets for it too. */
object HubDimens {
    val ScreenPaddingHorizontal = 40.dp
    val ScreenPaddingVertical = 24.dp
    val RowSpacing = 24.dp
    val CardSpacing = 12.dp
    /** Primefly follows a streaming-service shelf: wide 16:9 art instead of posters. */
    // Primefly cards are 15% smaller than the previous 166.4 × 93.6 dp,
    // while preserving the authored 16:9 landscape ratio.
    val PosterWidth get() = when {
        HubColors.isPrimefly -> 141.44.dp
        HubColors.isCyberpunk -> 79.03.dp
        else -> 88.8.dp
    }
    val PosterHeight get() = when {
        HubColors.isPrimefly -> 79.56.dp
        HubColors.isCyberpunk -> 118.55.dp
        else -> 133.2.dp
    }
}

@Composable
fun HubTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = HubColors.Accent,
        onPrimary = HubColors.Text,
        secondary = HubColors.Accent2,
        background = HubColors.Background,
        onBackground = HubColors.Text,
        surface = HubColors.Surface,
        onSurface = HubColors.Text,
        surfaceVariant = HubColors.SurfaceStrong,
        border = HubColors.Border,
    )
    MaterialTheme(colorScheme = colorScheme, typography = HubTypography) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to HubColors.Surface.copy(alpha = 0.34f),
                        0.32f to HubColors.Background,
                        1f to HubColors.Background,
                    )
                )
        ) { content() }
    }
}
