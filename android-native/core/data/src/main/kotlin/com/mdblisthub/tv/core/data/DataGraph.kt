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
import com.mdblisthub.tv.core.data.repository.SimklAuthRepository
import com.mdblisthub.tv.core.data.repository.WikipediaRepository
import com.mdblisthub.tv.core.data.repository.source.MdblistHomeFeedSource
import com.mdblisthub.tv.core.data.repository.source.MdblistLibrarySource
import com.mdblisthub.tv.core.data.repository.source.MdblistPlaybackSource
import com.mdblisthub.tv.core.data.repository.source.TraktHomeFeedSource
import com.mdblisthub.tv.core.data.repository.source.TraktLibrarySource
import com.mdblisthub.tv.core.data.repository.source.TraktPlaybackSource
import com.mdblisthub.tv.core.data.repository.source.SimklHomeFeedSource
import com.mdblisthub.tv.core.data.repository.source.SimklLibrarySource
import com.mdblisthub.tv.core.data.repository.source.SimklPlaybackSource
import com.mdblisthub.tv.core.model.LibraryProvider
import com.mdblisthub.tv.core.data.work.ImageMemoryTrimmer
import com.mdblisthub.tv.core.data.work.ImageWarmer
import com.mdblisthub.tv.core.data.work.MetadataScheduler
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.network.NetworkModule
import com.mdblisthub.tv.core.network.TraktTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    val network: NetworkModule by lazy {
        NetworkModule(appContext).also { module ->
            // Bridges the circular auth dependency without constructing the
            // Trakt repositories at process start. The bridge resolves them
            // only if an actual Trakt request needs a token or refresh.
            module.traktTokens = object : TraktTokens {
                override fun accessToken(): String = traktAuth.accessToken()
                override fun refreshed(expired: String): String? = traktAuth.refreshed(expired)
            }
            module.simklToken = { kotlinx.coroutines.runBlocking { simklTokenStore.accessToken() } }
        }
    }
    val database by lazy { HubDatabase.create(appContext) }
    val session = SessionStore(appContext)
    val syncStore = SyncStore(appContext)
    val uiPreferences = UiPreferencesStore(appContext)
    val stremioAccountStore = StremioAccountStore(appContext)
    val traktTokenStore = TraktTokenStore(appContext)
    val simklTokenStore = SimklTokenStore(appContext)

    val traktAuth by lazy { TraktAuthRepository(network.traktAuth, network.trakt, traktTokenStore) }
    val simklAuth by lazy { SimklAuthRepository(network.simkl, simklTokenStore) }

    val auth by lazy { AuthRepository(
        apiProvider = { network.mdblist },
        syncApiProvider = { network.sync },
        session = session,
        syncStore = syncStore,
        stremioAccountStore = stremioAccountStore,
        databaseProvider = { database },
    ) }
    val lists by lazy { ListsRepository(network.mdblist, session, database) }
    val addons: AddonsRepository by lazy {
        AddonsRepository(network.stremio, network.stremioInstall, database, session, lists).also {
            it.onLocalChange = { firebaseSync.pushIfEnabled() }
        }
    }
    val listPreferencesSync by lazy { ListPreferencesSyncRepository(
        network.sync,
        auth,
        session,
        lists,
        scope,
    ) }
    val media by lazy {
        MediaRepository(network.tmdb, network.mdblist, network.omdb, network.fanartTv, session, database)
    }

    /**
     * The two answers to "where does this account's library live". Both are
     * built regardless of the setting: they hold no state worth deferring, and
     * a switch has to take effect on the next call rather than on the next
     * launch.
     */
    val homeFeeds by lazy { HomeFeedsRepository(
        mdblist = MdblistHomeFeedSource(network.mdblist, session),
        trakt = TraktHomeFeedSource(network.trakt, traktTokenStore),
        simkl = SimklHomeFeedSource(network.simkl, simklTokenStore),
        preferences = uiPreferences,
        session = session,
        media = media,
    ) }
    val stremioAccount by lazy { StremioAccountRepository(
        network.stremioAccount,
        stremioAccountStore,
        addons,
        network.stremioInstall,
        lists,
        session,
    ) }
    val streams by lazy { StreamsRepository(
        network.stremio, addons, network.addonClient, network.openSubtitles, network.wyzie,
    ) }
    val library by lazy { LibraryRepository(
        mdblist = MdblistLibrarySource(network.mdblist, session),
        trakt = TraktLibrarySource(network.trakt, traktTokenStore),
        simkl = SimklLibrarySource(network.simkl, simklTokenStore),
        preferences = uiPreferences,
        database = database,
    ) }
    val playback by lazy { PlaybackRepository(
        mdblist = MdblistPlaybackSource(network.mdblist, session),
        trakt = TraktPlaybackSource(network.trakt, traktTokenStore),
        simkl = SimklPlaybackSource(network.simkl, simklTokenStore),
        preferences = uiPreferences,
        database = database,
        media = media,
    ) }
    val firebaseSync: FirebaseSyncRepository by lazy {
        FirebaseSyncRepository(network.sync, syncStore, auth, addons, scope)
    }
    val wikipedia by lazy { WikipediaRepository(network.wikipedia, network.tmdb, uiPreferences.language) }
    val trailers by lazy { TrailerRepository(network.imdb) }
    val recommendations by lazy {
        RecommendationsRepository(network.mdblist, network.tmdb, media, session)
    }

    val scheduler by lazy { MetadataScheduler(appContext) }
    val prefetcher by lazy { MetadataPrefetcher(media, scope) }

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

    private var startupArtworkWarmup: Job? = null

    /**
     * Uses the intro's screen time to put the first Home artwork into Coil.
     *
     * The Home is already composed behind the intro, so this does not delay
     * navigation or data loading. It only gives the images most likely to be
     * on the first screen an explicit head start. A second bounded pass picks
     * up account feeds that may have arrived while the intro was playing;
     * Coil de-duplicates anything the first pass or the real UI already
     * requested.
     */
    fun warmHomeArtworkDuringIntro() {
        if (startupArtworkWarmup?.isActive == true) return
        startupArtworkWarmup = scope.launch {
            repeat(STARTUP_ARTWORK_PASSES) { pass ->
                warmCachedHomeArtwork()
                if (pass < STARTUP_ARTWORK_PASSES - 1) delay(STARTUP_ARTWORK_RETRY_MS)
            }
        }
    }

    private suspend fun warmCachedHomeArtwork() {
        val visibleLists = lists.listsOnce()
            .asSequence()
            .filterNot { it.hidden }
            .sortedBy { it.position }
            .take(STARTUP_LIST_LIMIT)
            .toList()

        val listItems = coroutineScope {
            visibleLists.flatMap { list ->
                lists.observeItems(list.id).first().take(STARTUP_CARDS_PER_ROW)
            }
        }
        val feedItems = homeFeeds.observeFeeds().first()
            .asSequence()
            .filterNot { it.hidden }
            .sortedBy { it.position ?: Int.MAX_VALUE }
            .take(STARTUP_FEED_LIMIT)
            .flatMap { feed -> feed.items.take(STARTUP_CARDS_PER_ROW).asSequence() }
            .map { it.media }
            .toList()
        val resumeItems = playback.resumePoints.first()
            .take(STARTUP_CARDS_PER_ROW)
            .map { point ->
                MediaItem(
                    tmdbId = point.tmdbId ?: 0,
                    type = point.type,
                    title = point.title,
                    imdbId = point.imdbId,
                    posterUrl = point.posterUrl,
                    backdropUrl = point.backdropUrl,
                    score = point.score,
                )
            }

        val cards = (resumeItems + feedItems + listItems)
            .distinctBy { it.key }
            .take(STARTUP_CARD_LIMIT)
        val firstCard = cards.firstOrNull()
        val urls = buildList {
            // The hero is the largest image on screen, so it gets first place
            // in the loader queue before the shelf thumbnails.
            firstCard?.backdropUrl?.let(::add)
            firstCard?.landscapeUrl?.let(::add)
            cards.mapNotNullTo(this) { it.posterUrl }
            cards.mapNotNullTo(this) { it.landscapeUrl ?: it.backdropUrl }
        }.distinct().take(STARTUP_URL_LIMIT)

        if (urls.isNotEmpty()) imageWarmer.warm(urls)
    }

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

    suspend fun unlinkSimkl() {
        switchLibraryProvider(LibraryProvider.MDBLIST)
        simklAuth.unlink()
    }

    private companion object {
        const val STARTUP_ARTWORK_PASSES = 2
        const val STARTUP_ARTWORK_RETRY_MS = 1_500L
        const val STARTUP_LIST_LIMIT = 2
        const val STARTUP_FEED_LIMIT = 1
        const val STARTUP_CARDS_PER_ROW = 8
        const val STARTUP_CARD_LIMIT = 16
        const val STARTUP_URL_LIMIT = 24
    }
}
