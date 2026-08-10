package com.mdblisthub.tv.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.ui.component.LoadingScreen
import com.mdblisthub.tv.ui.addons.AddonsScreen
import com.mdblisthub.tv.ui.detail.DetailScreen
import com.mdblisthub.tv.ui.home.HomeScreen
import com.mdblisthub.tv.ui.login.LoginScreen
import com.mdblisthub.tv.ui.player.PlayerScreen
import com.mdblisthub.tv.ui.search.SearchScreen

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val SEARCH = "search"
    const val ADDONS = "addons"
    const val DETAIL = "detail/{type}/{tmdbId}"
    const val PLAYER = "player/{type}/{tmdbId}?season={season}&episode={episode}"

    fun detail(type: MediaType, tmdbId: Int) = "detail/${type.mdblist}/$tmdbId"

    fun player(type: MediaType, tmdbId: Int, season: Int? = null, episode: Int? = null) =
        "player/${type.mdblist}/$tmdbId?season=${season ?: -1}&episode=${episode ?: -1}"
}

@Composable
fun HubNavHost(graph: DataGraph) {
    val navController = rememberNavController()
    val signedIn by graph.auth.signedIn.collectAsStateWithLifecycle(initialValue = null)

    // A stored key is re-checked before anything routes, so every screen
    // downstream can assume the session is real.
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        graph.auth.restore()
        graph.listPreferencesSync.restore()
        graph.scheduler.syncNow()
        restored = true
    }

    if (!restored || signedIn == null) {
        LoadingScreen(message = "Abrindo…")
        return
    }

    NavHost(
        navController = navController,
        startDestination = if (signedIn == true) Routes.HOME else Routes.LOGIN,
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
            ),
        ) { entry ->
            val args = entry.arguments
            PlayerScreen(
                graph = graph,
                type = MediaType.parse(args?.getString("type")),
                tmdbId = args?.getInt("tmdbId") ?: 0,
                season = args?.getInt("season")?.takeIf { it > 0 },
                episode = args?.getInt("episode")?.takeIf { it > 0 },
                onBack = { navController.popBackStack() },
                onOpenAddons = { navController.navigate(Routes.ADDONS) },
            )
        }
    }
}
