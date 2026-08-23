package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.data.UiPreferencesStore
import com.mdblisthub.tv.core.data.repository.source.HomeFeedSource
import com.mdblisthub.tv.core.model.AppError
import com.mdblisthub.tv.core.model.CoreText
import com.mdblisthub.tv.core.model.LibraryProvider
import com.mdblisthub.tv.core.model.requireOrFail
import com.mdblisthub.tv.core.model.MdblistHomeFeed
import com.mdblisthub.tv.core.model.MdblistHomeFeedItem
import com.mdblisthub.tv.core.model.MdblistHomeFeedKeys
import com.mdblisthub.tv.core.network.dto.FirebaseCatalogPreferenceDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The four account-native home rows that are not user-created lists.
 *
 * Which account answers for them is a setting — see [LibraryProvider] — but
 * everything after the network call is the same either way: the artwork pass,
 * the ownership guard, and the rename/hide/delete customizations. Only
 * [HomeFeedSource] differs between providers.
 */
class HomeFeedsRepository(
    private val mdblist: HomeFeedSource,
    private val trakt: HomeFeedSource,
    private val simkl: HomeFeedSource,
    private val preferences: UiPreferencesStore,
    private val session: SessionStore,
    private val media: MediaRepository,
) {
    /** Content is account-scoped; a previous account's rows must never bleed into a new session. */
    private val content = MutableStateFlow(OwnedFeedContent())

    /**
     * Feed customizations deliberately share the catalog-preference envelope.
     * Both are stable string-keyed home rows, so this gives these feeds Google
     * cloud sync without introducing a second, incompatible Firebase shape.
     */
    fun observeFeeds(): Flow<List<MdblistHomeFeed>> = combine(
        session.apiKey,
        preferences.libraryProvider,
        session.catalogPreferences,
        content,
    ) { apiKey, provider, catalogPreferences, content ->
        // The mdblist key still gates the app as a whole: it is what signs the
        // user in, and Trakt only ever replaces where these four rows come
        // from. No key, no home.
        if (apiKey.isBlank()) return@combine emptyList()

        val items = content.items.takeIf { content.ownerKey == ownerKey(provider, apiKey) }.orEmpty()
        val byKey = catalogPreferences.associateBy { it.key }
        defaults().mapNotNull { default ->
            if (provider == LibraryProvider.SIMKL && default.key == MdblistHomeFeedKeys.RECENTLY_ADDED) {
                return@mapNotNull null
            }
            val preference = byKey[default.key]
            if (preference?.deleted == true) return@mapNotNull null
            default.copy(
                name = preference?.name ?: default.originalName,
                position = preference?.position,
                hidden = preference?.hidden ?: false,
                items = items[default.key].orEmpty(),
            )
        }
    }

    suspend fun refresh() {
        val apiKey = session.currentKey()
        if (apiKey.isBlank()) return

        val provider = preferences.currentLibraryProvider()
        val owner = ownerKey(provider, apiKey)
        val loaded = when (provider) {
            LibraryProvider.TRAKT -> trakt
            LibraryProvider.SIMKL -> simkl
            LibraryProvider.MDBLIST -> mdblist
        }.load(FEED_LIMIT)

        coroutineScope {
            loaded.map { (key, items) ->
                async { key to withArtwork(items.take(FEED_LIMIT)) }
            }.awaitAll()
        }.forEach { (key, items) ->
            // The account or the provider may have changed while four network
            // requests were in flight. Discard those late answers instead of
            // ever publishing them under the new one.
            if (ownerKey(preferences.currentLibraryProvider(), session.currentKey()) != owner) {
                return@forEach
            }
            val previous = content.value.takeIf { it.ownerKey == owner }?.items.orEmpty()
            content.value = OwnedFeedContent(ownerKey = owner, items = previous + (key to items))
        }
    }

    /**
     * Fills in what the feed payload does not carry.
     *
     * mdblist hands these rows a poster and an IMDb score; Trakt hands over
     * neither, and neither provider sends the horizontal art Primefly's
     * landscape card reads (`landscapeUrl ?: backdropUrl`, never `posterUrl`).
     * One pass covers all of it, and only fills what is missing, so an
     * mdblist row keeps its own poster and a Trakt row borrows TMDB's — which
     * is what makes the same title look identical under either provider.
     *
     * Mirrors [PlaybackRepository.withArtwork]: `ensureDetail` is a no-op for
     * anything already hydrated, so this is cheap for titles the account has
     * looked at before and a real fetch only for new ones. Bounded rather
     * than unconditional like that resume-row equivalent: a resume row holds a
     * handful of titles, a watchlist can hold up to [FEED_LIMIT], and firing
     * fifty concurrent hydrations at once on a refresh would be its own kind
     * of slow start.
     */
    private suspend fun withArtwork(items: List<MdblistHomeFeedItem>): List<MdblistHomeFeedItem> = coroutineScope {
        val gate = Semaphore(ARTWORK_CONCURRENCY)
        items.map { item ->
            async {
                val tmdbId = item.media.tmdbId.takeIf { it > 0 } ?: return@async item
                gate.withPermit {
                    media.ensureDetail(item.media.type, tmdbId)
                    val detail = media.observeDetail(item.media.type, tmdbId).first()
                        ?: return@withPermit item
                    item.copy(
                        media = item.media.copy(
                            posterUrl = item.media.posterUrl ?: detail.posterUrl,
                            landscapeUrl = detail.landscapeUrl,
                            backdropUrl = detail.backdropUrl,
                            score = item.media.score ?: detail.ratings.firstOrNull()?.score,
                        ),
                    )
                }
            }
        }.awaitAll()
    }

    /**
     * Drops the rows the previous provider filled, so the home does not show
     * one account's watchlist under another's name for the seconds before the
     * refresh lands. Cheap: clearing the owner is enough, since
     * [observeFeeds] already discards content whose owner does not match.
     */
    fun onProviderChanged() {
        content.value = OwnedFeedContent()
    }

    suspend fun toggleVisibility(feed: MdblistHomeFeed, hidden: Boolean) = runCatching {
        updatePreference(feed) { it.copy(hidden = hidden) }
    }

    suspend fun rename(feed: MdblistHomeFeed, rawName: String) = runCatching {
        val name = rawName.trim()
        requireOrFail(name.isNotEmpty()) { AppError.NameRequired }
        updatePreference(feed) {
            it.copy(name = name.takeUnless { value -> value == feed.originalName })
        }
    }

    suspend fun delete(feed: MdblistHomeFeed) = runCatching {
        updatePreference(feed) { it.copy(deleted = true) }
    }

    private suspend fun updatePreference(
        feed: MdblistHomeFeed,
        transform: (FirebaseCatalogPreferenceDto) -> FirebaseCatalogPreferenceDto,
    ) {
        val current = session.currentCatalogPreferences()
        val previous = current.firstOrNull { it.key == feed.key }
            ?: FirebaseCatalogPreferenceDto(
                key = feed.key,
                position = feed.position,
                hidden = feed.hidden,
            )
        session.saveCatalogPreferences(
            current.filterNot { it.key == feed.key } + transform(previous),
        )
    }

    /**
     * Who the cached rows belong to. The provider is part of it, not just the
     * key, so flipping the setting invalidates the cache on its own — without
     * that, mdblist rows would keep showing until the Trakt refresh returned.
     */
    private fun ownerKey(provider: LibraryProvider, apiKey: String): String = "${provider.name}:$apiKey"

    private companion object {
        const val FEED_LIMIT = 50

        /** Same ceiling as the decoy-duration probe: a network+DB round trip per title, bounded. */
        const val ARTWORK_CONCURRENCY = 8

        /**
         * A function, not a `val`. These names come from [CoreText], which
         * reads `Locale.getDefault()` live — freezing them into a `val` would
         * bake in whatever locale happened to be current the first time this
         * companion object was touched, which on a cold start can run before
         * `MainActivity` has mirrored the user's chosen language into the
         * process default at all.
         */
        fun defaults(): List<MdblistHomeFeed> = listOf(
            MdblistHomeFeed(
                key = MdblistHomeFeedKeys.UP_NEXT,
                name = CoreText.upNext,
                originalName = CoreText.upNext,
            ),
            MdblistHomeFeed(
                key = MdblistHomeFeedKeys.RECENTLY_ADDED,
                name = CoreText.recentlyAdded,
                originalName = CoreText.recentlyAdded,
            ),
            MdblistHomeFeed(
                key = MdblistHomeFeedKeys.WATCHLIST,
                name = CoreText.watchlist,
                originalName = CoreText.watchlist,
            ),
            MdblistHomeFeed(
                key = MdblistHomeFeedKeys.RECENTLY_WATCHED,
                name = CoreText.recentlyWatched,
                originalName = CoreText.recentlyWatched,
            ),
        )
    }
}

private data class OwnedFeedContent(
    val ownerKey: String = "",
    val items: Map<String, List<MdblistHomeFeedItem>> = emptyMap(),
)
