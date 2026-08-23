package com.mdblisthub.tv.ui.welcome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.HubThemeVariant
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubEffects
import com.mdblisthub.tv.core.ui.theme.HubShapes
import com.mdblisthub.tv.core.ui.theme.HubStrokes
import com.mdblisthub.tv.ui.component.HubButton
import kotlinx.coroutines.launch

private data class WelcomeTheme(
    val variant: HubThemeVariant,
    val name: String,
    val preview: Int,
)

@Composable
fun WelcomeScreen(graph: DataGraph, onComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    val firstCardFocus = remember { FocusRequester() }
    var selectedTheme by remember { mutableStateOf(HubThemeVariant.NORMAL) }
    var focusedVariant by remember { mutableStateOf<HubThemeVariant?>(null) }

    val themes = listOf(
        WelcomeTheme(HubThemeVariant.NORMAL, stringResource(R.string.menu_theme_normal), R.drawable.theme_normal_preview),
        WelcomeTheme(HubThemeVariant.CYBERPUNK, stringResource(R.string.menu_theme_cyberpunk), R.drawable.theme_cyberpunk_preview),
        WelcomeTheme(HubThemeVariant.NETFLIXY, stringResource(R.string.menu_theme_netflixy), R.drawable.theme_netflixy_preview),
        WelcomeTheme(HubThemeVariant.PRIMEFLY, stringResource(R.string.menu_theme_primefly), R.drawable.theme_primefly_preview),
    )

    LaunchedEffect(Unit) {
        val current = graph.uiPreferences.currentTheme()
        selectedTheme = when (current) {
            HubThemeVariant.CYBERFLIX -> HubThemeVariant.NETFLIXY
            HubThemeVariant.OPTIMUS_PRIME -> HubThemeVariant.PRIMEFLY
            else -> current
        }
        firstCardFocus.requestFocus()
    }

    BackHandler(enabled = true) { }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(horizontal = 64.dp, vertical = 34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.displayMedium,
                color = HubColors.Text,
            )
            Text(
                text = stringResource(R.string.welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = HubColors.TextDim,
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                items(themes, key = { it.variant.name }) { theme ->
                    WelcomeThemeCard(
                        theme = theme,
                        selected = selectedTheme == theme.variant,
                        focused = focusedVariant == theme.variant,
                        onFocus = {
                            focusedVariant = theme.variant
                            selectedTheme = theme.variant
                        },
                        onClick = { selectedTheme = theme.variant },
                        modifier = if (theme.variant == HubThemeVariant.NORMAL) {
                            Modifier.focusRequester(firstCardFocus)
                        } else {
                            Modifier
                        },
                    )
                }
            }

            HubButton(
                text = stringResource(R.string.welcome_continue),
                primary = true,
                onClick = {
                    scope.launch {
                        graph.uiPreferences.saveTheme(selectedTheme)
                        graph.uiPreferences.saveSetupCompleted(true)
                        onComplete()
                    }
                },
            )
        }
    }
}

@Composable
private fun WelcomeThemeCard(
    theme: WelcomeTheme,
    selected: Boolean,
    focused: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(HubShapes.Panel)
    Column(
        modifier = modifier
            .width(260.dp)
            .height(190.dp)
            .clip(shape)
            .background(HubColors.Surface.copy(alpha = HubEffects.GlassSurfaceAlpha))
            .border(
                if (selected || focused) 2.dp else 1.dp,
                if (focused) HubColors.Text else if (selected) HubColors.Accent else HubColors.Border,
                shape,
            )
            .onFocusChanged { if (it.isFocused) onFocus() }
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(135.dp)
                .clip(RoundedCornerShape(HubShapes.Field)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(theme.preview),
                contentDescription = theme.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.38f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                }
            }
        }
        Text(theme.name, style = MaterialTheme.typography.titleMedium, color = HubColors.Text)
    }
}
