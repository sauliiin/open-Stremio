package com.mdblisthub.tv.navigation
import androidx.compose.ui.res.stringResource
import com.mdblisthub.tv.R

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.LandscapeArtwork
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.ui.component.LandscapeArtworkLoader
import com.mdblisthub.tv.core.ui.component.LoadingScreen
import com.mdblisthub.tv.core.ui.component.LocalLandscapeArtworkLoader
import com.mdblisthub.tv.ui.addons.AddonsScreen
import com.mdblisthub.tv.ui.detail.DetailScreen
import com.mdblisthub.tv.ui.home.HomeScreen
import com.mdblisthub.tv.ui.login.LoginScreen
import com.mdblisthub.tv.ui.player.PlayerScreen
import com.mdblisthub.tv.ui.search.SearchScreen
import com.mdblisthub.tv.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val SEARCH = "search"
    const val ADDONS = "addons"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{type}/{tmdbId}"
    const val PLAYER = "player/{type}/{tmdbId}?season={season}&episode={episode}&select={select}"

    fun detail(type: MediaType, tmdbId: Int) = "detail/${type.mdblist}/$tmdbId"

    fun player(type: MediaType, tmdbId: Int, season: Int? = null, episode: Int? = null, select: Boolean = false) =
        "player/${type.mdblist}/$tmdbId?season=${season ?: -1}&episode=${episode ?: -1}&select=$select"
}

@Composable
fun HubNavHost(graph: DataGraph) {
    val navController = rememberNavController()

    /**
     * Where the graph starts — decided **once**, from a snapshot, and never
     * recomputed.
     *
     * This used to read `graph.auth.signedIn` as live Compose state and derive
     * the start destination from it, which looks reasonable and is in fact the
     * bug that made a first sign-in end on a black screen with no menu.
     *
     * `NavHost` keys its `NavGraph` on `startDestination` via `remember`, and
     * hands each rebuilt graph to `NavController.setGraph`, which drops the
     * entire back stack and re-seeds it from the new start. So the instant a
     * login landed, one state change fired two things at once: `LoginScreen`'s
     * `onSignedIn` running `navigate(HOME) { popUpTo(LOGIN) }`, and a graph
     * swap caused by that same `signedIn` flipping. The swap tore down the
     * entry the navigate had just pushed, and nothing was left to draw. Sign-out
     * raced identically in reverse.
     *
     * Read once here, after `restore()` has settled, the value the graph is
     * built from can no longer change underneath it. Every later transition is
     * an explicit `navigate` — which is the only thing that should ever move
     * the back stack.
     */
    var startDestination by remember { mutableStateOf<String?>(null) }

    // A stored key is re-checked before anything routes, so every screen
    // downstream can assume the session is real.
    LaunchedEffect(Unit) {
        graph.auth.restore()
        graph.listPreferencesSync.restore()
        graph.firebaseSync.restore()
        graph.scheduler.syncNow()
        startDestination = if (graph.auth.signedIn.first()) Routes.HOME else Routes.LOGIN
    }

    val start = startDestination
    if (start == null) {
        LoadingScreen(message = stringResource(R.string.loading_opening))
        return
    }

    val artworkCache = remember { mutableStateMapOf<String, LandscapeArtwork>() }
    val artworkRequests = remember { mutableSetOf<String>() }
    val artworkPermits = remember { Semaphore(16) }
    val artworkScope = rememberCoroutineScope()
    val artworkLoader = remember(graph, artworkScope) {
        LandscapeArtworkLoader(
            artworkFor = { item -> artworkCache[item.key] },
            request = { item ->
                // All calls happen from a card effect on the main thread. The
                // set makes recomposition and repeated shelves share one call.
                if (artworkRequests.add(item.key)) {
                    artworkScope.launch {
                        val artwork = artworkPermits.withPermit {
                            graph.media.resolveLandscapeArtwork(item)
                        }
                        artworkCache[item.key] = artwork
                    }
                }
            },
        )
    }

    CompositionLocalProvider(LocalLandscapeArtworkLoader provides artworkLoader) {
        NavHost(
            navController = navController,
            startDestination = start,
        ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                graph = graph,
                onSignedIn = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                graph = graph,
                onOpenTitle = { item -> navController.navigate(Routes.detail(item.type, item.tmdbId)) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenAddons = { navController.navigate(Routes.ADDONS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onResume = { point ->
                    navController.navigate(
                        Routes.player(point.type, point.tmdbId ?: 0, point.season, point.episode),
                    )
                },
                onSignOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.ADDONS) {
            AddonsScreen(graph = graph, onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(graph = graph, onBack = { navController.popBackStack() })
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                graph = graph,
                onOpenTitle = { item -> navController.navigate(Routes.detail(item.type, item.tmdbId)) }
            )
        }

        composable(
            route = Routes.DETAIL,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("tmdbId") { type = NavType.IntType },
            ),
        ) { entry ->
            val type = MediaType.parse(entry.arguments?.getString("type"))
            val tmdbId = entry.arguments?.getInt("tmdbId") ?: 0

            DetailScreen(
                graph = graph,
                type = type,
                tmdbId = tmdbId,
                onBack = { navController.popBackStack() },
                onPlay = { season, episode ->
                    navController.navigate(Routes.player(type, tmdbId, season, episode))
                },
                onSelectSource = { season, episode ->
                    navController.navigate(Routes.player(type, tmdbId, season, episode, select = true))
                },
                onOpenTitle = { item -> navController.navigate(Routes.detail(item.type, item.tmdbId)) },
            )
        }

        composable(
            route = Routes.PLAYER,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("tmdbId") { type = NavType.IntType },
                navArgument("season") { type = NavType.IntType; defaultValue = -1 },
                navArgument("episode") { type = NavType.IntType; defaultValue = -1 },
                navArgument("select") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val args = entry.arguments
            PlayerScreen(
                graph = graph,
                type = MediaType.parse(args?.getString("type")),
                tmdbId = args?.getInt("tmdbId") ?: 0,
                season = args?.getInt("season")?.takeIf { it > 0 },
                episode = args?.getInt("episode")?.takeIf { it > 0 },
                manualSelect = args?.getBoolean("select") ?: false,
                onBack = { navController.popBackStack() },
                onOpenAddons = { navController.navigate(Routes.ADDONS) },
            )
        }
        }
    }
}
