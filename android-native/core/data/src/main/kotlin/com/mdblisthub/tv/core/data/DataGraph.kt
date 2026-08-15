package com.mdblisthub.tv.core.data

import android.content.Context
import com.mdblisthub.tv.core.data.repository.AddonsRepository
import com.mdblisthub.tv.core.data.repository.AuthRepository
import com.mdblisthub.tv.core.data.repository.FirebaseSyncRepository
import com.mdblisthub.tv.core.data.repository.LibraryRepository
import com.mdblisthub.tv.core.data.repository.ListPreferencesSyncRepository
import com.mdblisthub.tv.core.data.repository.ListsRepository
import com.mdblisthub.tv.core.data.repository.MediaRepository
import com.mdblisthub.tv.core.data.repository.HomeFeedsRepository
import com.mdblisthub.tv.core.data.repository.PlaybackRepository
import com.mdblisthub.tv.core.data.repository.RecommendationsRepository
import com.mdblisthub.tv.core.data.repository.StreamsRepository
import com.mdblisthub.tv.core.data.repository.StremioAccountRepository
import com.mdblisthub.tv.core.data.repository.TrailerRepository
import com.mdblisthub.tv.core.data.repository.TraktAuthRepository
import com.mdblisthub.tv.core.data.repository.WikipediaRepository
import com.mdblisthub.tv.core.data.repository.source.MdblistHomeFeedSource
import com.mdblisthub.tv.core.data.repository.source.MdblistLibrarySource
import com.mdblisthub.tv.core.data.repository.source.MdblistPlaybackSource
import com.mdblisthub.tv.core.data.repository.source.TraktHomeFeedSource
import com.mdblisthub.tv.core.data.repository.source.TraktLibrarySource
import com.mdblisthub.tv.core.data.repository.source.TraktPlaybackSource
import com.mdblisthub.tv.core.model.LibraryProvider
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
    val traktTokenStore = TraktTokenStore(appContext)

    val traktAuth = TraktAuthRepository(network.traktAuth, network.trakt, traktTokenStore)

    init {
        // The HTTP layer reads the Trakt credential through this — see
        // `NetworkModule.traktTokens`. Installed here rather than passed to the
        // constructor because the store that holds the token is built on top
        // of the network module, not underneath it. Same late binding as
        // [imageWarmer] below.
        network.traktTokens = traktAuth
    }

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

    /**
     * The two answers to "where does this account's library live". Both are
     * built regardless of the setting: they hold no state worth deferring, and
     * a switch has to take effect on the next call rather than on the next
     * launch.
     */
    val homeFeeds = HomeFeedsRepository(
        mdblist = MdblistHomeFeedSource(network.mdblist, session),
        trakt = TraktHomeFeedSource(network.trakt, traktTokenStore),
        preferences = uiPreferences,
        session = session,
        media = media,
    )
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
    val library = LibraryRepository(
        mdblist = MdblistLibrarySource(network.mdblist, session),
        trakt = TraktLibrarySource(network.trakt, traktTokenStore),
        preferences = uiPreferences,
        database = database,
    )
    val playback = PlaybackRepository(
        mdblist = MdblistPlaybackSource(network.mdblist, session),
        trakt = TraktPlaybackSource(network.trakt, traktTokenStore),
        preferences = uiPreferences,
        database = database,
        media = media,
    )
    val firebaseSync = FirebaseSyncRepository(network.sync, syncStore, auth, addons, scope)
    val wikipedia = WikipediaRepository(network.wikipedia, uiPreferences.language)
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

    /**
     * Moves the library to another provider, and clears what the old one left.
     *
     * Behaviour rather than wiring, which is unusual for this file — but the
     * switch has to touch three repositories at once, and this is the only
     * object that holds all three. A fourth class existing solely to hold the
     * same references would be ceremony, and leaving the sequence to each
     * app's settings screen would mean writing it twice and getting it subtly
     * different once.
     *
     * The clears are not optional. Bucket membership and paused sessions are
     * cached per title with no record of who reported them, so without this a
     * film on the mdblist watchlist would keep showing as watchlisted under a
     * Trakt account that has never heard of it.
     */
    suspend fun switchLibraryProvider(provider: LibraryProvider) {
        if (uiPreferences.currentLibraryProvider() == provider) return
        uiPreferences.saveLibraryProvider(provider)
        homeFeeds.onProviderChanged()
        library.onProviderChanged()
        playback.onProviderChanged()
    }

    /**
     * Disconnects Trakt, putting the library back on mdblist first.
     *
     * The order matters: unlinking while the setting still says Trakt would
     * leave every row pointing at an account with no token, which reads as a
     * broken app rather than a disconnected one.
     */
    suspend fun unlinkTrakt() {
        switchLibraryProvider(LibraryProvider.MDBLIST)
        traktAuth.unlink()
    }
}
