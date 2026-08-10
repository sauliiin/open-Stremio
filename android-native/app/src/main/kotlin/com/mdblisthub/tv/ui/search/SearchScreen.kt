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
import androidx.tv.material3.Text
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.ui.component.PosterCard
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens

@Composable
fun SearchScreen(
    graph: DataGraph,
    onOpenTitle: (MediaItem) -> Unit,
) {
    val viewModel = viewModel<SearchViewModel>(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = SearchViewModel(graph) as T
    })

    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = HubDimens.ScreenPaddingHorizontal, vertical = HubDimens.ScreenPaddingVertical),
        verticalArrangement = Arrangement.spacedBy(HubDimens.RowSpacing)
    ) {
        Text("Buscar no TMDB", style = MaterialTheme.typography.headlineMedium, color = HubColors.Text)
        
        SearchBar(
            query = query,
            onQueryChange = viewModel::onQueryChange,
            modifier = Modifier.focusRequester(focusRequester)
        )

        if (isLoading && results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Buscando...", color = HubColors.TextDim)
            }
        } else if (results.isEmpty() && query.isNotBlank() && !isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhum resultado encontrado.", color = HubColors.TextDim)
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
                        onClick = { onOpenTitle(item) }
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
    val cornerRadius = if (HubColors.isCyberpunk) 0.dp else 8.dp

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = HubColors.Text),
        cursorBrush = SolidColor(HubColors.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(HubColors.Surface)
            .border(2.dp, borderColor, RoundedCornerShape(cornerRadius))
            .padding(16.dp),
        decorationBox = { innerTextField ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Search, contentDescription = null, tint = if (isFocused) HubColors.Accent else HubColors.TextDim)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Buscar filmes ou séries...", color = HubColors.TextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                    innerTextField()
                }
            }
        }
    )
}
