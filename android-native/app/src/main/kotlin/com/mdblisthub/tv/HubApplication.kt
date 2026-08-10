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
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.data.work.HubWorkerFactory
import com.mdblisthub.tv.core.data.work.ImageWarmer
import com.mdblisthub.tv.core.model.HubThemeVariant
import com.mdblisthub.tv.core.ui.theme.HubColors
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath

class HubApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    lateinit var graph: DataGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = DataGraph(this)

        // Read before the first frame rather than collected into composition.
        // The palette is global state that composition reads on its very first
        // pass, so letting DataStore deliver it asynchronously means every
        // cold start paints one frame in the default theme and then flips —
        // a visible flash on every launch, for someone who is not on Normal.
        // One small preferences file is the cheaper side of that trade, and
        // failing to read it is never worth blocking a start over.
        HubColors.apply(
            runCatching { runBlocking { graph.uiPreferences.currentTheme() } }
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
