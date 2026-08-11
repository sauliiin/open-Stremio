package com.mdblisthub.tv.core.data

import android.content.Context
import com.mdblisthub.tv.core.data.repository.AddonsRepository
import com.mdblisthub.tv.core.data.repository.AuthRepository
import com.mdblisthub.tv.core.data.repository.FirebaseSyncRepository
import com.mdblisthub.tv.core.data.repository.LibraryRepository
import com.mdblisthub.tv.core.data.repository.ListPreferencesSyncRepository
import com.mdblisthub.tv.core.data.repository.ListsRepository
import com.mdblisthub.tv.core.data.repository.MediaRepository
import com.mdblisthub.tv.core.data.repository.MdblistHomeFeedsRepository
import com.mdblisthub.tv.core.data.repository.PlaybackRepository
import com.mdblisthub.tv.core.data.repository.RecommendationsRepository
import com.mdblisthub.tv.core.data.repository.StreamsRepository
import com.mdblisthub.tv.core.data.repository.StremioAccountRepository
import com.mdblisthub.tv.core.data.repository.TrailerRepository
import com.mdblisthub.tv.core.data.repository.WikipediaRepository
import com.mdblisthub.tv.core.data.work.ImageMemoryTrimmer
import com.mdblisthub.tv.core.data.work.ImageWarmer
import com.mdblisthub.tv.core.data.work.MetadataScheduler
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.network.NetworkModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * The object graph, built once by the Application.
 *
 * Everything here is a plain constructor call. The graph is shallow enough
 * that a DI framework would add an annotation processor, a build-time code
 * generation step and an indirection to read through — for no property this
 * file does not already have.
 */
class DataGraph(context: Context) {

    val appContext = context.applicationContext

    /** Survives every screen; the prefetcher and one-off writes live on it. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val network = NetworkModule(appContext)
    val database = HubDatabase.create(appContext)
    val session = SessionStore(appContext)
    val syncStore = SyncStore(appContext)
    val uiPreferences = UiPreferencesStore(appContext)
    val stremioAccountStore = StremioAccountStore(appContext)

    val auth = AuthRepository(
        network.mdblist,
        network.sync,
        session,
        syncStore,
        stremioAccountStore,
        database,
    )
    val lists = ListsRepository(network.mdblist, session, database)
    val addons = AddonsRepository(network.stremio, network.stremioInstall, database, session, lists)
    val listPreferencesSync = ListPreferencesSyncRepository(
        network.sync,
        auth,
        session,
        lists,
        scope,
    )
    val media = MediaRepository(network.tmdb, network.mdblist, network.omdb, network.fanartTv, session, database)
    val homeFeeds = MdblistHomeFeedsRepository(network.mdblist, session, media)
    val stremioAccount = StremioAccountRepository(
        network.stremioAccount,
        stremioAccountStore,
        addons,
        network.stremioInstall,
        lists,
        session,
    )
    val streams = StreamsRepository(
        network.stremio, addons, network.addonClient, network.openSubtitles, network.wyzie,
    )
    val library = LibraryRepository(network.mdblist, session, database)
    val playback = PlaybackRepository(network.mdblist, session, database, media)
    val firebaseSync = FirebaseSyncRepository(network.sync, syncStore, auth, addons, scope)
    val wikipedia = WikipediaRepository(network.wikipedia)
    val trailers = TrailerRepository(network.imdb)
    val recommendations = RecommendationsRepository(network.mdblist, network.tmdb, media, session)

    val scheduler = MetadataScheduler(appContext)
    val prefetcher = MetadataPrefetcher(media, scope)

    /**
     * Assigned by the app once the Coil loader exists, which cannot happen
     * before this graph because the loader shares its OkHttp client. The
     * no-op default means a worker that runs in between simply warms nothing.
     */
    var imageWarmer: ImageWarmer = ImageWarmer { }

    /**
     * Attached by the app alongside [imageWarmer], and for the same reason:
     * the image cache belongs to the Coil loader, which cannot exist before
     * this graph. See [ImageMemoryTrimmer] for why the player wants it.
     */
    var imageMemoryTrimmer: ImageMemoryTrimmer = ImageMemoryTrimmer.NoOp
}
