package com.mdblisthub.tv.ui.home

import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.isActive
import androidx.compose.foundation.verticalScroll

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaList
import com.mdblisthub.tv.core.model.MdblistHomeFeed
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.AddonCatalog
import com.mdblisthub.tv.core.model.ResumePoint
import com.mdblisthub.tv.core.ui.component.FanartBackdrop
import com.mdblisthub.tv.core.ui.component.LoadingScreen
import com.mdblisthub.tv.core.ui.component.MediaRow
import com.mdblisthub.tv.core.ui.component.RailItem
import com.mdblisthub.tv.core.ui.component.SideRail
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens
import com.mdblisthub.tv.core.model.MediaDetail
import coil3.compose.AsyncImage
import com.mdblisthub.tv.ui.component.AnimatedOpenStreamTitle
import com.mdblisthub.tv.ui.component.HubButton
import com.mdblisthub.tv.ui.hubViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where a focused row parks: 30% down the viewport, the same pivot Compose's own (internal) TV spec uses. */
private const val ROW_PIVOT = 0.3f

private sealed interface EditableListTarget {
    val displayName: String

    data class Mdblist(val list: MediaList) : EditableListTarget {
        override val displayName: String get() = list.name
    }

    data class Stremio(val catalog: AddonCatalog) : EditableListTarget {
        override val displayName: String get() = catalog.name
    }

    data class Feed(val feed: MdblistHomeFeed) : EditableListTarget {
        override val displayName: String get() = feed.name
    }
}

/**
 * How the column scrolls when focus moves between rows.
 *
 * Compose's default already animates — it is a `spring` — so the jerkiness
 * was never a missing animation. It is that the default scrolls the *minimum*
 * distance needed to reveal the row, so every row settles at whatever height
 * happens to work out and each press travels a different amount. The eye
 * reads that as stumbling rather than gliding.
 *
 * The fix is a fixed landing point. Compose ships exactly this as
 * `PivotBringIntoViewSpec`, in its Android source set because it exists for
 * televisions — but it is `internal`, so the geometry is restated here.
 *
 * There used to be a `scrollAnimationSpec` override here — a stiff, non-bouncy
 * spring, chosen because the pivot makes *every* press scroll and a held
 * direction key needs an animation that retargets from wherever it is rather
 * than restarting. Compose has since deprecated that member outright
 * ("Animation spec customization is no longer supported") and no longer reads
 * it anywhere, so the override was doing nothing but emitting a warning. The
 * scroll it now uses is the framework's own, which is already a spring with
 * exactly that retargeting behaviour; only the landing point below was ever
 * the part Compose could not supply.
 */
@OptIn(ExperimentalFoundationApi::class)
private val RowPivotScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val pivot = if (HubColors.isNetflixy) 0.18f * containerSize else ROW_PIVOT * containerSize

        // A row tall enough that parking it at the pivot would hang its
        // bottom off-screen is aligned to the bottom edge instead — parking
        // it would otherwise hide the very cards being brought into view.
        val target = if (size <= containerSize && containerSize - pivot < size) {
            containerSize - size
        } else {
            pivot
        }

        // The container clamps this at both ends, which is what keeps the
        // hero panel visible at the top of the list: the first row wants to
        // move *down* to reach the pivot, and there is nowhere to scroll.
        return offset - target
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    graph: DataGraph,
    onOpenTitle: (MediaItem) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAddons: () -> Unit,
    onOpenSettings: () -> Unit,
    onResume: (ResumePoint) -> Unit,
    onSignOut: () -> Unit,
) {
    val viewModel = hubViewModel { HomeViewModel(graph) }
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val allLists by viewModel.allLists.collectAsStateWithLifecycle()
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val allFeeds by viewModel.allFeeds.collectAsStateWithLifecycle()
    val addonCatalogs by viewModel.addonCatalogs.collectAsStateWithLifecycle()
    val allAddonCatalogs by viewModel.allAddonCatalogs.collectAsStateWithLifecycle()
    val resumePoints by viewModel.resumePoints.collectAsStateWithLifecycle()
    // `focused`, `focusedBackdropUrl` and `focusedDetail` are deliberately NOT
    // collected here. Reading them at this level made this ~500-line composable
    // the recomposition scope for every single card the D-pad passes over.
    // `HeroPanel` and the backdrop collect them themselves, so the scope that
    // invalidates is the one that actually displays the value.
    val becauseYouWatched by viewModel.becauseYouWatched.collectAsStateWithLifecycle()
    val isEditMode by viewModel.isEditMode.collectAsStateWithLifecycle()
    val initialSyncComplete by viewModel.initialSyncComplete.collectAsStateWithLifecycle()
    val mdblistLinked by graph.auth.mdblistLinked.collectAsStateWithLifecycle(initialValue = false)
    val deletedListIds by graph.session.deletedListIds.collectAsStateWithLifecycle(initialValue = emptySet())
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshDynamicRows()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val extraCatalogs = remember(allLists, addonCatalogs, deletedListIds) {
        val representedListIds = allLists.mapTo(deletedListIds.toMutableSet()) { it.id }
        addonCatalogs.filterNot { catalog ->
            catalog.mdblistMirrorListId()?.let { it in representedListIds } == true
        }
    }
    val homeRows = remember(lists, allLists, feeds, allFeeds, extraCatalogs) {
        val feedsStillUseDefaultPlacement = allFeeds.none { it.position != null }
        val existingRowOffset = if (feedsStillUseDefaultPlacement) feeds.size else 0
        val lastStoredPosition = maxOf(
            allLists.maxOfOrNull { it.position } ?: -1,
            allFeeds.mapNotNull { it.position }.maxOrNull() ?: -1,
            extraCatalogs.mapNotNull { it.position }.maxOrNull() ?: -1,
        )
        var nextCatalogPosition = lastStoredPosition + 1
        val feedRows = feeds.mapIndexed { index, feed ->
            HomeMediaRow.Feed(
                feed,
                if (feedsStillUseDefaultPlacement) index else feed.position ?: index,
            )
        }
        val directRows = lists.map {
            HomeMediaRow.Mdblist(it, it.position + existingRowOffset)
        }
        val catalogRows = extraCatalogs.map { catalog ->
            HomeMediaRow.Stremio(
                catalog,
                (catalog.position ?: nextCatalogPosition++) + existingRowOffset,
            )
        }
        (feedRows + directRows + catalogRows)
            .sortedBy { it.position }
            .mapIndexed { index, row ->
                when (row) {
                    is HomeMediaRow.Mdblist -> row.copy(position = index)
                    is HomeMediaRow.Feed -> row.copy(position = index)
                    is HomeMediaRow.Stremio -> row.copy(position = index)
                }
            }
    }
    val openCatalogItem: (MediaItem) -> Unit = { item ->
        if (item.tmdbId > 0) {
            onOpenTitle(item)
        } else {
            item.imdbId?.let { imdbId ->
                scope.launch {
                    graph.media.resolveImdb(item.type, imdbId).onSuccess { tmdbId ->
                        onOpenTitle(item.copy(tmdbId = tmdbId))
                    }
                }
            }
        }
    }

    var showExitDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<EditableListTarget?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<EditableListTarget?>(null) }
    var resumeRemovalTarget by remember { mutableStateOf<ResumePoint?>(null) }
    val emptyStateFocusRequester = remember { FocusRequester() }

    resumeRemovalTarget?.let { point ->
        Dialog(onDismissRequest = { resumeRemovalTarget = null }) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .background(HubColors.Surface, RoundedCornerShape(16.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.home_resume_remove_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = HubColors.Text,
                )
                Text(
                    stringResource(R.string.home_resume_remove_body, point.title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = HubColors.TextDim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            viewModel.removeResumePoint(point)
                            resumeRemovalTarget = null
                        },
                    ) { Text(stringResource(R.string.home_delete)) }
                    Button(onClick = { resumeRemovalTarget = null }) {
                        Text(stringResource(R.string.home_cancel))
                    }
                }
            }
        }
    }

    if (showExitDialog) {
        Dialog(onDismissRequest = { showExitDialog = false }) {
            Box(
                modifier = Modifier
                    .background(
                        HubColors.Surface, 
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(32.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally, 
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        stringResource(R.string.home_exit_question),
                        style = MaterialTheme.typography.titleLarge, 
                        color = HubColors.Text
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = { 
                                showExitDialog = false
                                viewModel.signOut(onSignOut) 
                            }
                        ) {
                            Text(stringResource(R.string.home_yes))
                        }
                        Button(onClick = { showExitDialog = false }) {
                            Text(stringResource(R.string.home_no))
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        Dialog(onDismissRequest = { renameTarget = null }) {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .background(HubColors.Surface, RoundedCornerShape(16.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.home_rename_list), style = MaterialTheme.typography.titleLarge, color = HubColors.Text)
                BasicTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = HubColors.Text),
                    cursorBrush = SolidColor(HubColors.Accent2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(HubColors.Background, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = renameValue.isNotBlank(),
                        onClick = {
                            when (target) {
                                is EditableListTarget.Mdblist ->
                                    viewModel.renameList(target.list, renameValue)
                                is EditableListTarget.Stremio ->
                                    viewModel.renameCatalog(target.catalog, renameValue)
                                is EditableListTarget.Feed ->
                                    viewModel.renameFeed(target.feed, renameValue)
                            }
                            renameTarget = null
                        },
                    ) { Text(stringResource(R.string.home_save)) }
                    Button(onClick = { renameTarget = null }) { Text(stringResource(R.string.home_cancel)) }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        Dialog(onDismissRequest = { deleteTarget = null }) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .background(HubColors.Surface, RoundedCornerShape(16.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.home_delete_list_question), style = MaterialTheme.typography.titleLarge, color = HubColors.Text)
                Text(
                    when (target) {
                        is EditableListTarget.Mdblist ->
                            stringResource(R.string.home_delete_mdblist_body, target.displayName)
                        is EditableListTarget.Stremio ->
                            stringResource(R.string.home_delete_addon_body, target.displayName)
                        is EditableListTarget.Feed ->
                            stringResource(R.string.home_delete_feed_body, target.displayName)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = HubColors.TextDim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            when (target) {
                                is EditableListTarget.Mdblist -> viewModel.deleteList(target.list)
                                is EditableListTarget.Stremio -> viewModel.deleteCatalog(target.catalog)
                                is EditableListTarget.Feed -> viewModel.deleteFeed(target.feed)
                            }
                            deleteTarget = null
                        },
                    ) { Text(stringResource(R.string.home_delete)) }
                    Button(onClick = { deleteTarget = null }) { Text(stringResource(R.string.home_cancel)) }
                }
            }
        }
    }

    // Remembered because this composable recomposes on every card focus (it
    // reads `focused` for the hero panel), and neither of these depends on
    // that — rebuilding them per focus is pure allocation.
    val menuHome = stringResource(R.string.menu_home)
    val menuSearch = stringResource(R.string.menu_search)
    val menuAddons = stringResource(R.string.menu_addons)
    val menuLists = stringResource(R.string.menu_lists)
    val menuListsDone = stringResource(R.string.menu_lists_done)
    val menuThemeNormal = stringResource(R.string.menu_theme_normal)
    val menuThemeCyberpunk = stringResource(R.string.menu_theme_cyberpunk)
    val menuThemeNetflixy = stringResource(R.string.menu_theme_netflixy)
    val menuSettings = stringResource(R.string.menu_settings)
    val menuExit = stringResource(R.string.menu_exit)

    val currentThemeName = if (HubColors.isCyberpunk) menuThemeCyberpunk else if (HubColors.isNetflixy) menuThemeNetflixy else menuThemeNormal

    val rail = remember(isEditMode, HubColors.isCyberpunk, HubColors.isNetflixy, menuHome, menuSearch, menuAddons, menuLists, menuListsDone, currentThemeName, menuSettings, menuExit) {
        listOf(
            RailItem("home", menuHome, Icons.Default.Home),
            RailItem("search", menuSearch, Icons.Default.Search),
            RailItem("addons", menuAddons, Icons.Default.Extension),
            RailItem("lists", if (isEditMode) menuListsDone else menuLists, if (isEditMode) Icons.Default.Check else Icons.AutoMirrored.Filled.ViewList),
            RailItem("theme", currentThemeName, Icons.Default.Palette),
            RailItem("settings", menuSettings, Icons.Default.Settings),
            RailItem("exit", menuExit, Icons.AutoMirrored.Filled.Logout),
        )
    }
    val resumeCards = remember(resumePoints) { resumePoints.map { it.toCardItem() } }

    Box(Modifier.fillMaxSize()) {
        // The fanart follows focus, the way Estuary does it: whatever the
        // remote is pointing at fills the screen behind the rows.
        FocusedBackdrop(viewModel)

        Row(Modifier.fillMaxSize()) {
            SideRail(
                items = rail,
                selectedKey = "home",
                onSelect = { item ->
                    when (item.key) {
                        "search" -> onOpenSearch()
                        "addons" -> onOpenAddons()
                        "lists" -> viewModel.toggleEditMode()
                        // Through the ViewModel, not HubColors directly: the
                        // choice has to be persisted as well as painted.
                        "theme" -> viewModel.cycleTheme()
                        "settings" -> onOpenSettings()
                        "exit" -> showExitDialog = true
                    }
                },
            )

            if (!mdblistLinked && lists.isEmpty() && feeds.isEmpty() && resumePoints.isEmpty() && extraCatalogs.isEmpty()) {
                LaunchedEffect(Unit) {
                    emptyStateFocusRequester.requestFocus()
                }
                Column(
                    modifier = Modifier.fillMaxSize().padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        stringResource(R.string.home_empty_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = HubColors.Text,
                    )
                    Text(
                        stringResource(R.string.home_empty_desc),
                        style = MaterialTheme.typography.bodyLarge,
                        color = HubColors.TextDim,
                    )
                    HubButton(
                        text = stringResource(R.string.home_empty_button),
                        primary = true,
                        onClick = onOpenAddons,
                        modifier = Modifier.focusRequester(emptyStateFocusRequester),
                    )
                }
                return@Row
            }

            // Gated on the sync flag, not on the row lists. The old condition
            // was `lists.isNotEmpty() && allLists.isEmpty()`, and `lists` is
            // `allLists` with the hidden ones filtered out — a subset can
            // never be non-empty while its superset is empty, so this branch
            // was unreachable and the message never appeared once.
            if (!initialSyncComplete && allLists.isEmpty()) {
                LoadingScreen(message = stringResource(R.string.home_syncing))
            }

            if (lists.isEmpty() && feeds.isEmpty() && resumePoints.isEmpty() && extraCatalogs.isEmpty()) {
                val hasHiddenRows = allLists.isNotEmpty() || allFeeds.isNotEmpty() || allAddonCatalogs.isNotEmpty()
                LaunchedEffect(hasHiddenRows) { emptyStateFocusRequester.requestFocus() }
                Column(
                    modifier = Modifier.fillMaxSize().padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        stringResource(
                            if (hasHiddenRows) R.string.home_no_visible_rows else R.string.home_no_lists,
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        color = HubColors.Text,
                    )
                    Text(
                        stringResource(
                            if (hasHiddenRows) {
                                R.string.home_no_visible_rows_desc
                            } else {
                                R.string.home_no_lists_desc
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = HubColors.TextDim,
                    )
                    HubButton(
                        text = stringResource(
                            if (hasHiddenRows) R.string.home_edit_lists else R.string.home_empty_button,
                        ),
                        primary = true,
                        onClick = if (hasHiddenRows) viewModel::toggleEditMode else onOpenAddons,
                        modifier = Modifier.focusRequester(emptyStateFocusRequester),
                    )
                }
                return@Row
            }

            Column(Modifier.fillMaxSize()) {
                if (HubColors.isNetflixy) {
                    Box(Modifier.weight(1f)) {
                        HeroPanel(viewModel)
                    }
                }

                CompositionLocalProvider(LocalBringIntoViewSpec provides RowPivotScroll) {
                    LazyColumn(
                        modifier = if (HubColors.isNetflixy) 
                            Modifier.fillMaxWidth().height(264.dp).clipToBounds() 
                        else Modifier.fillMaxSize(),
                        // Tighter than HubDimens.RowSpacing on purpose — with the
                        // smaller posters, this is what keeps two rows of a list on
                        // screen together instead of one full row plus a sliver of
                        // the next.
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            top = 12.dp,
                            // Room to park the last row at the pivot instead of
                            // stopping short with it pinned to the bottom edge.
                            bottom = HubDimens.ScreenPaddingVertical * 8,
                        ),
                    ) {
                        if (!HubColors.isNetflixy) {
                            item(key = "hero") {
                                HeroPanel(viewModel)
                            }
                        }

                if (resumePoints.isNotEmpty() && !isEditMode) {
                    item(key = "resume") {
                        MediaRow(
                            title = stringResource(R.string.home_resume_row),
                            items = resumeCards,
                            // `card` alone cannot say which episode this is —
                            // `toCardItem()` drops season/episode, so two
                            // in-progress episodes of the same show produce
                            // equal `MediaItem`s, and `indexOf` on those would
                            // always resolve to the *first* one regardless of
                            // which card was actually pressed. `resumeCards`
                            // and `resumePoints` are the same list at the same
                            // indices (see their construction above), so only
                            // the position — not the card's own equality —
                            // can say correctly which point this was.
                            onItemFocused = viewModel::onFocused,
                            key = { index, item -> resumePoints.getOrNull(index)?.key ?: item.key },
                            onItemClickIndexed = { index, _ ->
                                resumePoints.getOrNull(index)?.let(onResume)
                            },
                            onItemLongClickIndexed = { index, _ ->
                                resumeRemovalTarget = resumePoints.getOrNull(index)
                            },
                            progressPercent = { index, _ -> resumePoints.getOrNull(index)?.progress },
                        )
                    }
                }

                itemsIndexed(
                    homeRows,
                    key = { _, row ->
                        when (row) {
                            is HomeMediaRow.Mdblist -> "mdblist-${row.list.id}"
                            is HomeMediaRow.Feed -> row.feed.key
                            is HomeMediaRow.Stremio -> "addon-catalog-${catalogRowIdentity(row.catalog)}"
                        }
                    },
                ) { index, row ->
                    when (row) {
                        is HomeMediaRow.Mdblist -> {
                            val list = row.list
                            val itemFlow = remember(list.id) { viewModel.itemsFor(list.id) }
                            ListRow(
                                list = list,
                                itemFlow = itemFlow,
                                isEditMode = isEditMode,
                                onToggleVisibility = { viewModel.toggleListVisibility(list) },
                                canMoveUp = index > 0,
                                canMoveDown = index < homeRows.lastIndex,
                                onMoveUp = { viewModel.moveRow(homeRows, index, -1) },
                                onMoveDown = { viewModel.moveRow(homeRows, index, 1) },
                                onRename = {
                                    renameTarget = EditableListTarget.Mdblist(list)
                                    renameValue = list.name
                                },
                                onDelete = {
                                    deleteTarget = EditableListTarget.Mdblist(list)
                                },
                                onEnsure = { viewModel.ensureItems(list.id) },
                                onItemClick = onOpenTitle,
                                onItemFocused = viewModel::onFocused,
                                onReachedEnd = { viewModel.loadMore(list.id) },
                            )
                        }
                        is HomeMediaRow.Stremio -> {
                            val catalog = row.catalog
                            val itemFlow = remember(catalog.addonBase, catalog.key) {
                                viewModel.itemsForCatalog(catalog)
                            }
                            AddonCatalogRow(
                                catalog = catalog,
                                itemFlow = itemFlow,
                                isEditMode = isEditMode,
                                onToggleVisibility = {
                                    viewModel.toggleCatalogVisibility(catalog)
                                },
                                canMoveUp = index > 0,
                                canMoveDown = index < homeRows.lastIndex,
                                onMoveUp = { viewModel.moveRow(homeRows, index, -1) },
                                onMoveDown = { viewModel.moveRow(homeRows, index, 1) },
                                onRename = {
                                    renameTarget = EditableListTarget.Stremio(catalog)
                                    renameValue = catalog.name
                                },
                                onDelete = {
                                    deleteTarget = EditableListTarget.Stremio(catalog)
                                },
                                onEnsure = { viewModel.ensureCatalog(catalog) },
                                onItemClick = openCatalogItem,
                                onItemFocused = viewModel::onFocused,
                            )
                        }
                        is HomeMediaRow.Feed -> {
                            val feed = row.feed
                            val cards = feed.items.map { it.media }
                            MediaRow(
                                title = feed.name,
                                items = cards,
                                isEditMode = isEditMode,
                                hidden = feed.hidden,
                                onToggleVisibility = {
                                    viewModel.toggleFeedVisibility(feed)
                                },
                                canMoveUp = index > 0,
                                canMoveDown = index < homeRows.lastIndex,
                                onMoveUp = { viewModel.moveRow(homeRows, index, -1) },
                                onMoveDown = { viewModel.moveRow(homeRows, index, 1) },
                                onRename = {
                                    renameTarget = EditableListTarget.Feed(feed)
                                    renameValue = feed.name
                                },
                                onDelete = {
                                    deleteTarget = EditableListTarget.Feed(feed)
                                },
                                key = { itemIndex, item ->
                                    val feedItem = feed.items.getOrNull(itemIndex)
                                    "${item.key}:${feedItem?.season ?: 0}:${feedItem?.episode ?: 0}"
                                },
                                onItemClickIndexed = { itemIndex, item ->
                                    val feedItem = feed.items.getOrNull(itemIndex)
                                    if (feedItem?.season != null && feedItem.episode != null) {
                                        onResume(feedItem.toResumePoint())
                                    } else {
                                        openCatalogItem(item)
                                    }
                                },
                                onItemFocused = viewModel::onFocused,
                            )
                        }
                    }
                }

                // "Porque você assistiu" — always last, since it is built from
                // the five most recent watches rather than an MDBList row.
                if (!isEditMode) {
                    items(becauseYouWatched, key = { "byw-${it.seedTitle}" }) { row ->
                        MediaRow(
                            title = stringResource(R.string.home_because_you_watched, row.seedTitle),
                            items = row.items,
                            onItemClick = onOpenTitle,
                            onItemFocused = viewModel::onFocused,
                        )
                    }
                }
                }
            }
            }
        }
    }
}

@Composable
private fun AddonCatalogRow(
    catalog: AddonCatalog,
    itemFlow: StateFlow<List<MediaItem>>,
    isEditMode: Boolean,
    onToggleVisibility: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onEnsure: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
) {
    val items by itemFlow.collectAsStateWithLifecycle()
    LaunchedEffect(catalog.addonBase, catalog.key) { onEnsure() }
    MediaRow(
        title = catalog.name,
        items = items,
        isEditMode = isEditMode,
        hidden = catalog.hidden,
        onToggleVisibility = onToggleVisibility,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown,
        onRename = onRename,
        onDelete = onDelete,
        onItemClick = onItemClick,
        onItemFocused = onItemFocused,
    )
}

private const val MDBLIST_CATALOG_HOST = "stremio-mdblist.baby-beamup.club"

private fun catalogRowIdentity(catalog: AddonCatalog): String =
    "${catalog.addonBase.trimEnd('/')}|${catalog.key}"

/** Returns the source MDBList id only for manifests generated by its catalog bridge. */
private fun AddonCatalog.mdblistMirrorListId(): Long? {
    val uri = runCatching { java.net.URI(addonBase) }.getOrNull() ?: return null
    if (!uri.host.orEmpty().contains(MDBLIST_CATALOG_HOST, ignoreCase = true)) return null

    val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
    val marker = segments.indexOfLast { it.equals("mdblist", ignoreCase = true) }
    if (marker < 2) return null
    return segments.getOrNull(marker - 2)?.toLongOrNull()
}

/**
 * One row, collecting its own items.
 *
 * `LazyColumn` only collects rows near the viewport. The ViewModel retains
 * each visited row's last Room emission, so returning upward restores its
 * geometry immediately instead of flashing through an empty 0dp item.
 */
@Composable
private fun ListRow(
    list: MediaList,
    itemFlow: StateFlow<List<MediaItem>>,
    isEditMode: Boolean = false,
    onToggleVisibility: () -> Unit = {},
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {},
    onEnsure: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
    onReachedEnd: () -> Unit,
) {
    val items by itemFlow.collectAsStateWithLifecycle()

    LaunchedEffect(list.id) { onEnsure() }

    MediaRow(
        title = list.name,
        items = items,
        isEditMode = isEditMode,
        hidden = list.hidden,
        onToggleVisibility = onToggleVisibility,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown,
        onRename = onRename,
        onDelete = onDelete,
        onItemClick = onItemClick,
        onItemFocused = onItemFocused,
        onReachedEnd = onReachedEnd,
    )
}

@Composable
private fun AutoScrollText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val scrollState = androidx.compose.foundation.rememberScrollState()
    LaunchedEffect(text) {
        scrollState.scrollTo(0)
        kotlinx.coroutines.delay(4000)
        while (isActive) {
            val max = scrollState.maxValue
            if (max > 0) {
                // 80ms/px rather than 40 — at the old speed a long synopsis
                // read as a blur scrolling past, not text anyone could
                // actually read while glancing at the hero panel.
                scrollState.animateScrollTo(max, animationSpec = androidx.compose.animation.core.tween(durationMillis = max * 80, easing = androidx.compose.animation.core.LinearEasing))
                kotlinx.coroutines.delay(4000)
                scrollState.animateScrollTo(0, animationSpec = androidx.compose.animation.core.tween(durationMillis = 800))
                kotlinx.coroutines.delay(3000)
            } else {
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier.verticalScroll(scrollState)
    )
}

/**
 * The full-bleed artwork, collecting the focused item's backdrop itself.
 *
 * Split out for the recomposition scope, not for tidiness: the URL changes
 * every time focus settles on a different card, and read from `HomeScreen` it
 * invalidated the whole screen for what is one `AsyncImage`.
 */
@Composable
private fun FocusedBackdrop(viewModel: HomeViewModel) {
    val url by viewModel.focusedBackdropUrl.collectAsStateWithLifecycle()
    FanartBackdrop(url = url)
}

/** Same reasoning as [FocusedBackdrop] — see the note in `HomeScreen`. */
@Composable
private fun HeroPanel(viewModel: HomeViewModel) {
    val item by viewModel.focused.collectAsStateWithLifecycle()
    val itemDetail by viewModel.focusedDetail.collectAsStateWithLifecycle()
    HeroPanelContent(item, itemDetail)
}

@Composable
private fun HeroPanelContent(item: MediaItem?, itemDetail: MediaDetail?) {
    if (HubColors.isNetflixy) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = HubDimens.ScreenPaddingHorizontal, end = HubDimens.ScreenPaddingHorizontal, bottom = 4.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (item == null) {
                AnimatedOpenStreamTitle(
                    style = MaterialTheme.typography.headlineLarge,
                )
                return@Column
            }

            val logoUrl = itemDetail?.logoUrl
            if (logoUrl != null) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = item.title,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.height(100.dp).padding(bottom = 20.dp),
                    alignment = Alignment.BottomStart,
                )
            } else {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = HubColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 20.dp)
                )
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                listOfNotNull(
                    item.year?.toString(),
                    stringResource(
                        if (item.type == MediaType.SHOW) R.string.home_type_show else R.string.home_type_movie,
                    ),
                    item.runtimeMinutes?.let { stringResource(R.string.home_minutes, it) },
                    item.genres.take(2).joinToString(" · ").takeIf { it.isNotBlank() },
                ).forEach {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = HubColors.TextDim)
                }
            }

            val overview = itemDetail?.overview
            if (overview != null) {
                AutoScrollText(
                    text = overview,
                    style = MaterialTheme.typography.bodyLarge,
                    color = HubColors.Text,
                    modifier = Modifier.fillMaxWidth(0.55f).weight(1f, fill = false)
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HubDimens.ScreenPaddingHorizontal)
                .height(76.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (item == null) {
                AnimatedOpenStreamTitle(
                    style = MaterialTheme.typography.headlineLarge,
                )
                return@Column
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineLarge,
                color = HubColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOfNotNull(
                    item.year?.toString(),
                    stringResource(
                        if (item.type == MediaType.SHOW) R.string.home_type_show else R.string.home_type_movie,
                    ),
                    item.runtimeMinutes?.let { stringResource(R.string.home_minutes, it) },
                    item.genres.take(2).joinToString(" · ").takeIf { it.isNotBlank() },
                ).forEach {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = HubColors.TextDim)
                }
            }
        }
    }
}

private fun ResumePoint.toCardItem() = MediaItem(
    tmdbId = tmdbId ?: 0,
    type = type,
    title = title,
    imdbId = imdbId,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    score = score,
)
