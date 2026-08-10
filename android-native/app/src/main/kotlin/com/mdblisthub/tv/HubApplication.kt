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
import coil3.request.crossfade
import coil3.PlatformContext as CoilPlatformContext
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.data.work.HubWorkerFactory
import com.mdblisthub.tv.core.data.work.ImageMemoryTrimmer
import com.mdblisthub.tv.core.data.work.ImageWarmer
import com.mdblisthub.tv.core.model.HubThemeVariant
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.ui.theme.HubColors
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
        graph.imageWarmer = ImageWarmer { urls ->
            val loader = SingletonImageLoader.get(this)
            urls.forEach { url ->
                loader.execute(ImageRequest.Builder(this).data(url).build())
            }
        }

        graph.imageMemoryTrimmer = CoilMemoryTrimmer(this)

        // Keeps TMDB in step with the interface language. Without it the
        // English option produced an English interface wrapped around
        // Portuguese synopses, titles and certifications — the metadata layer
        // never heard about the setting at all.
        graph.scope.launch {
            graph.uiPreferences.language.collect { tag ->
                ApiConfig.LANGUAGE = ApiConfig.metadataLanguageFor(tag)
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
                add(OkHttpNetworkFetcherFactory(callFactory = { graph.network.metadataClient }))
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
            .crossfade(true)
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
