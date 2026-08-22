package com.mdblisthub.tv

import android.app.Application
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.transitionFactory
import coil3.PlatformContext as CoilPlatformContext
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.ui.coil.AlwaysCrossfadeTransitionFactory
import com.mdblisthub.tv.core.data.work.HubWorkerFactory
import com.mdblisthub.tv.core.data.work.ImageMemoryTrimmer
import com.mdblisthub.tv.core.data.work.ImageWarmer
import com.mdblisthub.tv.core.model.HubThemeVariant
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.player.MediaCache
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

class HubApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    lateinit var graph: DataGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = DataGraph(this)

        // Read before the first frame rather than collected into composition.
        // The palette is global state that composition reads on its very first
        // pass, so letting it arrive asynchronously means every cold start
        // paints one frame in the default theme and then flips — a visible
        // flash on every launch, for someone who is not on Normal.
        //
        // Synchronous, but no longer blocking: this reads a one-key
        // SharedPreferences mirror instead of awaiting DataStore, so the main
        // thread is not parked on file I/O and protobuf parsing at the most
        // latency-sensitive point in the lifecycle. See `UiPreferencesStore`.
        HubColors.apply(
            runCatching { graph.uiPreferences.startupTheme() }
                .getOrDefault(HubThemeVariant.NORMAL),
        )

        // Assigned after the graph exists, because the loader shares its
        // OkHttp client — one connection pool for artwork and metadata alike.
        //
        // The urls are warmed concurrently, which is the difference between
        // this keeping up with a browsing session and trailing it: `execute`
        // suspends until that one image has been fetched *and* decoded, so the
        // `forEach` it replaces spent eight round trips end to end on a row of
        // eight posters — and `ArtworkWorker` calls this once per row, for
        // every list on the account. Nothing widens as a result; the image
        // client's own per-host limit still bounds how many are in flight, so
        // this queues the work rather than opening more sockets for it.
        graph.imageWarmer = ImageWarmer { urls ->
            val loader = SingletonImageLoader.get(this)
            coroutineScope {
                urls.map { url ->
                    async { loader.execute(ImageRequest.Builder(this@HubApplication).data(url).build()) }
                }.awaitAll()
            }
        }

        graph.imageMemoryTrimmer = CoilMemoryTrimmer(this)

        // Opened here so the player does not have to open it.
        //
        // `MediaCache.get` reads a live storage quota over binder, stats the
        // volume and opens the SQLite span index — and it was being called
        // from `PlaybackController`'s constructor, which runs on the main
        // thread inside `PlayerViewModel`'s creation. That is disk I/O and a
        // binder round trip sitting between the OK keypress and the first
        // addon request, on the one screen where latency is the whole
        // experience. It is memoized and synchronized, so warming it on the
        // graph's IO scope costs the player nothing but the lookup.
        graph.scope.launch { MediaCache.warm(this@HubApplication) }

        // Keeps TMDB in step with the interface language. Without it the
        // English option produced an English interface wrapped around
        // Portuguese synopses, titles and certifications — the metadata layer
        // never heard about the setting at all.
        graph.scope.launch {
            graph.uiPreferences.language.collect { tag ->
                ApiConfig.LANGUAGE = ApiConfig.metadataLanguageFor(tag)
            }
        }

        graph.scope.launch {
            graph.uiPreferences.theme.collect { variant ->
                HubColors.apply(variant)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            // `{ graph }` and not `graph`: WorkManager reads this while it
            // initialises, which can happen before onCreate has finished.
            .setWorkerFactory(HubWorkerFactory { graph })
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    /**
     * A generous disk cache is the point of the whole design: posters are
     * immutable once published, so a title browsed last week should never be
     * downloaded again. Half a gigabyte is nothing on a set-top box and is
     * roughly a year of casual browsing.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                // The image client, not the metadata one: same connection
                // pool, no second disk cache underneath Coil's own. See
                // `HttpClients.images`.
                add(OkHttpNetworkFetcherFactory(callFactory = { graph.network.imageClient }))
            }
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, 0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("artwork").toOkioPath())
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            // Plain `.crossfade(true)` skips the animation whenever Coil
            // serves the image from memory cache — which, revisiting rows
            // browsed moments ago, is most posters most of the time. This
            // factory crossfades any genuinely new image regardless of
            // where it was served from. See `AlwaysCrossfadeTransitionFactory`.
            .transitionFactory(AlwaysCrossfadeTransitionFactory())
            .build()
}

/**
 * Lends the video buffer the heap the poster cache is holding.
 *
 * The image cache is budgeted at 25% of the heap and the player's allocator at
 * a share of what is free — but a film playing and a wall of posters on screen
 * never happen at the same time, so during playback that quarter of the heap
 * is doing nothing except making the buffer smaller. Coil keeps
 * `initialMaxSize` precisely so a caller can hand the budget back afterwards.
 *
 * Nothing is lost by trimming: every entry is still on Coil's 512MB disk
 * cache, so returning to the home screen re-decodes rather than re-downloads.
 */
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
private class CoilMemoryTrimmer(
    private val context: CoilPlatformContext,
) : ImageMemoryTrimmer {

    override fun trimForPlayback() {
        val cache = SingletonImageLoader.get(context).memoryCache ?: return
        cache.maxSize = (cache.initialMaxSize / PLAYBACK_DIVISOR).coerceAtLeast(MIN_BYTES)
    }

    override fun restore() {
        val cache = SingletonImageLoader.get(context).memoryCache ?: return
        cache.maxSize = cache.initialMaxSize
    }

    private companion object {
        const val PLAYBACK_DIVISOR = 8L

        /** Enough for the artwork the player's own veil draws, and no more. */
        const val MIN_BYTES = 8L * 1024 * 1024
    }
}
