package com.mdblisthub.tv.ui.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.ui.component.HubSpinner
import com.mdblisthub.tv.core.ui.component.PosterCard
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens
import com.mdblisthub.tv.core.ui.theme.HubEffects
import com.mdblisthub.tv.core.ui.theme.HubShapes
import com.mdblisthub.tv.core.ui.theme.HubStrokes
import com.mdblisthub.tv.ui.component.MediaOptionsDialog

@Composable
fun SearchScreen(
    graph: DataGraph,
    onOpenTitle: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onChooseSource: (MediaItem) -> Unit,
) {
    val viewModel = viewModel<SearchViewModel>(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = SearchViewModel(graph) as T
    })

    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val watchedIds by viewModel.watchedIds.collectAsStateWithLifecycle()

    val focusRequester = remember { FocusRequester() }
    var optionTarget by remember { mutableStateOf<MediaItem?>(null) }

    optionTarget?.let { item ->
        val isWatched = watchedIds.contains(item.tmdbId)
        MediaOptionsDialog(
            isWatched = isWatched,
            onPlay = {
                onPlay(item)
                optionTarget = null
            },
            onInfo = {
                onOpenTitle(item)
                optionTarget = null
            },
            onToggleWatched = {
                viewModel.setWatched(item, !isWatched)
                optionTarget = null
            },
            onChooseSource = {
                onChooseSource(item)
                optionTarget = null
            },
            onDismiss = { optionTarget = null },
        )
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = HubDimens.ScreenPaddingHorizontal, vertical = HubDimens.ScreenPaddingVertical),
        verticalArrangement = Arrangement.spacedBy(HubDimens.RowSpacing)
    ) {
        Text(stringResource(R.string.search_title), style = MaterialTheme.typography.headlineMedium, color = HubColors.Text)
        
        SearchBar(
            query = query,
            onQueryChange = viewModel::onQueryChange,
            modifier = Modifier.focusRequester(focusRequester)
        )

        if (isLoading && results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HubSpinner()
                    Text(stringResource(R.string.search_searching), color = HubColors.TextDim)
                }
            }
        } else if (results.isEmpty() && query.isNotBlank() && !isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.search_no_results), color = HubColors.TextDim)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(HubDimens.PosterWidth),
                verticalArrangement = Arrangement.spacedBy(HubDimens.RowSpacing),
                horizontalArrangement = Arrangement.spacedBy(HubDimens.CardSpacing),
                contentPadding = PaddingValues(bottom = 100.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // Keyed so that refining a query re-uses the cards for titles
                // that survive it instead of rebuilding — and, more visibly on
                // a remote, so focus stays on the card it was on rather than
                // jumping to whatever now occupies that index.
                items(results, key = { it.key }) { item ->
                    PosterCard(
                        item = item,
                        onClick = { onOpenTitle(item) },
                        onLongClick = { optionTarget = item },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val borderColor by animateColorAsState(if (isFocused) HubColors.Accent else HubColors.Border)
    val cornerRadius = if (HubColors.isCyberpunk) 2.dp else HubShapes.Field
    val shape = RoundedCornerShape(cornerRadius)

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = HubColors.Text),
        cursorBrush = SolidColor(HubColors.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HubColors.SurfaceStrong.copy(alpha = HubEffects.GlassSurfaceAlpha))
            .border(
                if (isFocused) HubStrokes.Focus else HubStrokes.Hairline,
                borderColor,
                shape,
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        decorationBox = { innerTextField ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Search, contentDescription = null, tint = if (isFocused) HubColors.Accent else HubColors.TextDim)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.search_placeholder),
                            color = HubColors.TextDim,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    innerTextField()
                }
            }
        }
    )
}
