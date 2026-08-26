package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.data.mapper.toDomain
import com.mdblisthub.tv.core.data.mapper.toEntity
import com.mdblisthub.tv.core.data.mapper.toAddonEntity
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.database.entity.AddonEntity
import com.mdblisthub.tv.core.model.Addon
import com.mdblisthub.tv.core.model.AddonCatalog
import com.mdblisthub.tv.core.model.AddonCatalogItem
import com.mdblisthub.tv.core.model.AddonImportSkipReason
import com.mdblisthub.tv.core.model.AppError
import com.mdblisthub.tv.core.model.CoreText
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.fail
import com.mdblisthub.tv.core.model.requireOrFail
import com.mdblisthub.tv.core.network.HttpClients
import com.mdblisthub.tv.core.network.StremioApi
import com.mdblisthub.tv.core.network.dto.StremioCollectionEntryDto
import com.mdblisthub.tv.core.network.dto.StremioManifestDto
import com.mdblisthub.tv.core.network.dto.StremioMetaDto
import com.mdblisthub.tv.core.network.dto.FirebaseCatalogPreferenceDto
import com.mdblisthub.tv.core.model.StremioImportFailure
import com.mdblisthub.tv.core.model.StremioImportReport
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt
import com.mdblisthub.tv.core.model.MdblistAddonExportReport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The addons the user installed.
 *
 * Nothing ships with the app: every URL is pasted by whoever is using it, the
 * same model Stremio uses. Re-installing an addon refreshes its manifest
 * rather than duplicating it, which is how a re-configured addon — a new
 * debrid key, say — gets updated.
 */
class AddonsRepository(
    private val api: StremioApi,
    /**
     * Only [install] uses this. It is the same endpoints on a far more patient
     * client, because a first request to a freshly configured addon URL can
     * take tens of seconds where every later one takes milliseconds.
     */
    private val installApi: StremioApi,
    private val database: HubDatabase,
    private val session: SessionStore,
    private val lists: ListsRepository,
) {
    private val dao = database.addonDao()

    /**
     * Called after install, remove, or an import commits — anything that
     * should propagate to the cloud. Late-bound because the object that
     * actually knows how to push, `FirebaseSyncRepository`, is built on top
     * of this one, not underneath it — same reason `DataGraph.imageWarmer` is
     * a `var` rather than a constructor parameter. The no-op default means a
     * mutation that runs before the graph finishes wiring itself simply
     * pushes nothing, which is correct: nothing has told this repository
     * sync is even possible yet.
     *
     * Deliberately not called from [merge] or [replaceAll] themselves: both
     * are also how a *remote* change lands locally (`enable`'s join, `pull`),
     * and pushing there would echo that same data straight back. The higher
     * callers that mean a genuine local edit — [install], [remove],
     * [importCollection] — call it themselves instead.
     */
    var onLocalChange: suspend () -> Unit = {}

    fun observeAddons(): Flow<List<Addon>> =
        dao.observeAddons().map { rows -> rows.map { it.toDomain() } }

    /** Catalog rows are kept in the raw manifest and decorated with local/cloud preferences. */
    fun observeCatalogs(): Flow<List<AddonCatalog>> =
        combine(dao.observeAddons(), session.catalogPreferences) { rows, preferences ->
            val byKey = preferences.associateBy { it.key }
            rawCatalogs(rows).mapNotNull { catalog ->
                // Read the pre-instance-scoped key for compatibility. Any
                // subsequent edit migrates it atomically to the new key.
                val preference = byKey[catalog.key] ?: byKey[catalog.legacyKey]
                if (preference?.deleted == true) return@mapNotNull null
                catalog.copy(
                    name = preference?.name ?: catalog.originalName,
                    position = preference?.position,
                    hidden = preference?.hidden ?: false,
                )
            }
        }

    suspend fun toggleCatalogVisibility(catalog: AddonCatalog, hidden: Boolean) = runCatching {
        updateCatalogPreference(catalog) { it.copy(hidden = hidden) }
    }

    suspend fun renameCatalog(catalog: AddonCatalog, rawName: String) = runCatching {
        val name = rawName.trim()
        requireOrFail(name.isNotEmpty()) { AppError.NameRequired }
        updateCatalogPreference(catalog) {
            it.copy(name = name.takeUnless { value -> value == catalog.originalName })
        }
    }

    suspend fun deleteCatalog(catalog: AddonCatalog) = runCatching {
        updateCatalogPreference(catalog) { it.copy(deleted = true) }
    }

    /** Writes the normalized home order in one DataStore update. */
    suspend fun setCatalogPositions(positions: Map<String, Int>) = runCatching {
        if (positions.isEmpty()) return@runCatching
        val current = session.currentCatalogPreferences()
        val byKey = current.associateBy { it.key }.toMutableMap()
        positions.forEach { (key, position) ->
            val legacyKey = legacyCatalogPreferenceKey(key)
            val previous = byKey[key] ?: byKey[legacyKey]
                ?: FirebaseCatalogPreferenceDto(key = key)
            if (legacyKey != key) byKey.remove(legacyKey)
            byKey[key] = previous.copy(key = key, position = position)
        }
        session.saveCatalogPreferences(byKey.values.toList())
    }

    suspend fun addons(): List<Addon> = dao.addons().map { it.toDomain() }

    suspend fun catalogItems(catalog: AddonCatalog): Result<List<AddonCatalogItem>> = runCatching {
        val url = "${catalog.addonBase.trimEnd('/')}/catalog/" +
            "${encodeSegment(catalog.type)}/${encodeSegment(catalog.id)}.json"

        // Two passes rather than one, because a catalog entry is not always a
        // title. An addon may answer with a *container* — a channel whose
        // contents are episodes of several different shows — and the whole
        // row is then behind a `meta` call the catalog itself does not carry.
        // Containers are expanded concurrently and titles cost nothing extra,
        // so a catalog with none of them behaves exactly as it did before.
        val metas = api.catalog(url).metas
        val gate = Semaphore(CONTAINER_CONCURRENCY)
        val expanded = coroutineScope {
            metas.map { meta ->
                async {
                    if (isContainerId(meta.id)) {
                        gate.withPermit { containerItems(catalog, meta.id, meta.type) }
                    } else {
                        listOfNotNull(titleItem(meta))
                    }
                }
            }.awaitAll()
        }.flatten()

        expanded.distinctBy { "${it.media.key}:${it.season ?: 0}:${it.episode ?: 0}" }
    }

    /** A catalog entry that is a title in its own right — the ordinary case. */
    private fun titleItem(meta: StremioMetaDto): AddonCatalogItem? {
        if (meta.id.isBlank() || meta.name.isBlank()) return null
        val tmdbId = meta.id
            .removePrefix("tmdb:")
            .substringAfterLast(':')
            .toIntOrNull()
            ?: 0
        val imdbId = meta.id.takeIf { it.startsWith("tt", ignoreCase = true) }
        if (tmdbId <= 0 && imdbId == null) return null

        return AddonCatalogItem(
            media = MediaItem(
                tmdbId = tmdbId,
                imdbId = imdbId,
                type = MediaType.fromStremio(meta.type),
                title = meta.name,
                year = meta.releaseInfo?.take(4)?.toIntOrNull(),
                posterUrl = meta.poster,
                backdropUrl = meta.background,
                score = meta.imdbRating?.toDoubleOrNull()?.times(10)?.roundToInt(),
            ),
        )
    }

    /**
     * The line-up behind a container entry, as one card per scheduled episode.
     *
     * A failure here is an empty list, not an exception: one unreachable
     * channel must not empty the whole row alongside the channels that
     * answered.
     */
    private suspend fun containerItems(
        catalog: AddonCatalog,
        containerId: String,
        metaType: String,
    ): List<AddonCatalogItem> {
        val type = metaType.ifBlank { catalog.type }
        val url = "${catalog.addonBase.trimEnd('/')}/meta/" +
            "${encodeSegment(type)}/${encodeSegment(containerId)}.json"
        val detail = runCatching { api.meta(url).meta }.getOrNull() ?: return emptyList()

        return detail.videos.mapNotNull { video ->
            val episodeId = parseEpisodeId(video.id) ?: return@mapNotNull null
            val title = video.title?.takeIf { it.isNotBlank() }
                ?: video.name?.takeIf { it.isNotBlank() }
                ?: detail.name
            if (title.isBlank()) return@mapNotNull null

            AddonCatalogItem(
                media = MediaItem(
                    tmdbId = 0,
                    imdbId = episodeId.imdbId,
                    type = MediaType.fromStremio(type),
                    title = title,
                    // The episode still is the only artwork the line-up
                    // carries. The show's own poster would mean resolving
                    // every IMDb id to TMDB before the row could paint, which
                    // is a request per card for artwork the addon already
                    // supplied one of.
                    posterUrl = video.thumbnail,
                    backdropUrl = video.thumbnail,
                ),
                season = episodeId.season,
                episode = episodeId.episode,
            )
        }
    }

    /** Raw rows, manifest included — what the Firebase push needs to write. */
    suspend fun entities(): List<AddonEntity> = dao.addons()

    suspend fun install(rawUrl: String): Result<Addon> = runCatching {
        val base = Addon.normaliseUrl(rawUrl)

        val manifest = runCatching { installApi.manifest("$base/manifest.json") }.getOrElse { cause ->
            val timedOut = cause is java.io.InterruptedIOException ||
                cause is java.net.SocketTimeoutException
            fail(if (timedOut) AppError.AddonInstallTimedOut else AppError.AddonManifestUnreadable)
        }

        // A host's own PWA manifest has `name` but no `id`, and more than one
        // addon service serves exactly that at its root — so this is the error
        // people actually hit when they paste the site instead of the URL it
        // generated for them.
        if (manifest.id.isBlank() || manifest.name.isBlank()) {
            fail(AppError.AddonManifestInvalid)
        }

        // A configurable addon commonly answers on its *unconfigured* address
        // too, with a complete-looking manifest whose catalog list is empty
        // and whose `configurationRequired` is set. Installing that puts a
        // name in the addon list and nothing anywhere else, which reads as
        // "the app does not support this addon" — so it is refused here
        // instead. One mistyped character in a per-user URL lands exactly on
        // this path, because the host answers the wrong token with the
        // placeholder rather than a 404.
        if (manifest.behaviorHints?.configurationRequired == true) {
            fail(AppError.AddonNotConfigured)
        }

        val entity = manifest.toEntity(base, System.currentTimeMillis())
        dao.upsert(listOf(entity))
        onLocalChange()
        entity.toDomain()
    }

    suspend fun remove(base: String) {
        dao.delete(base)
        onLocalChange()
    }

    private suspend fun updateCatalogPreference(
        catalog: AddonCatalog,
        transform: (FirebaseCatalogPreferenceDto) -> FirebaseCatalogPreferenceDto,
    ) {
        val current = session.currentCatalogPreferences()
        val previous = current.firstOrNull { it.key == catalog.key }
            ?: current.firstOrNull { it.key == catalog.legacyKey }
            ?: FirebaseCatalogPreferenceDto(
                key = catalog.key,
                position = catalog.position,
                hidden = catalog.hidden,
            )
        session.saveCatalogPreferences(
            current.filterNot { it.key == catalog.key || it.key == catalog.legacyKey } +
                transform(previous.copy(key = catalog.key)),
        )
    }

    /**
     * Folds already-built addons in, answering how many were new to this
     * device. Backs the Firebase pull, where the entries were written by this
     * same app and need no re-validation beyond parsing.
     */
    suspend fun merge(incoming: List<AddonEntity>): Int {
        val known = dao.addons().map { it.base }.toSet()
        val fresh = incoming.count { it.base !in known }
        if (incoming.isNotEmpty()) {
            val bases = incoming.map { it.base }.toSet()
            val existing = dao.addons().filterNot { it.base in bases }
            dao.replaceAll(existing + incoming)
        }
        return fresh
    }

    /**
     * Makes this device's list exactly `next` — the "Baixar" action, which is
     * what lets a removal made elsewhere actually land here.
     */
    suspend fun replaceAll(next: List<AddonEntity>) = dao.replaceAll(next)

    suspend fun importCollection(entries: List<StremioCollectionEntryDto>): StremioImportReport {
        val imported = mutableListOf<AddonEntity>()
        val skipped = mutableListOf<StremioImportFailure>()
        val now = System.currentTimeMillis()

        entries.forEach { entry ->
            val url = entry.transportUrl.orEmpty()
            val manifest = entry.manifest
            val name = manifest?.get("name")?.jsonPrimitive?.contentOrNull
                ?: url.ifBlank { CoreText.unnamedAddon }

            if (url.isBlank()) {
                skipped += StremioImportFailure(name, url, AddonImportSkipReason.NO_URL)
                return@forEach
            }
            if (manifest?.get("id")?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                skipped += StremioImportFailure(name, url, AddonImportSkipReason.NO_MANIFEST_ID)
                return@forEach
            }

            val base = runCatching { Addon.normaliseUrl(url) }.getOrElse {
                skipped += StremioImportFailure(name, url, AddonImportSkipReason.UNPARSABLE_URL)
                return@forEach
            }
            val entity = manifest.toAddonEntity(base, now)
            if (entity == null) {
                skipped += StremioImportFailure(name, url, AddonImportSkipReason.INVALID_MANIFEST)
            } else {
                imported += entity
            }
        }

        merge(imported)
        onLocalChange()
        return StremioImportReport(
            received = entries.size,
            imported = imported.map { it.name },
            skipped = skipped,
        )
    }

    /** Adds every MDBList row as a catalog addon to the local OmniStream repository. */
    suspend fun exportAllMdblistLists(): Result<MdblistAddonExportReport> = runCatching {
        val apiKey = session.currentKey()
        requireOrFail(apiKey.isNotBlank()) { AppError.MdblistNotLinked }

        lists.refreshLists(force = true).getOrThrow()
        val mdblistLists = lists.listsOnce()
        // No lists is not a failure. A valid key with no lists of its own still
        // powers ratings, trailers and the rest of the metadata enrichment —
        // there is simply nothing to turn into a catalog addon here.
        if (mdblistLists.isEmpty()) {
            return@runCatching MdblistAddonExportReport(exported = emptyList(), failed = emptyList())
        }

        val manifestGate = Semaphore(4)
        val manifestResults = coroutineScope {
            mdblistLists.map { list ->
                async {
                    val transportUrl = mdblistTransportUrl(list.id, apiKey)
                    list to manifestGate.withPermit {
                        runCatching {
                            val manifest = installApi.rawManifest(transportUrl)
                            StremioCollectionEntryDto(
                                transportUrl = transportUrl,
                                manifest = manifest,
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        val exported = manifestResults.mapNotNull { (list, result) ->
            result.getOrNull()?.let { list to it }
        }
        val failed = manifestResults.mapNotNull { (list, result) ->
            list.name.takeIf { result.isFailure }
        }
        requireOrFail(exported.isNotEmpty()) { AppError.MdblistNoValidManifests }

        // Remove the old ones first to ensure clean state
        dao.addons()
            .filter { isMdblistCatalogAddon(it.base) }
            .forEach { dao.delete(it.base) }

        // Import the new ones
        importCollection(exported.map { it.second })

        MdblistAddonExportReport(
            exported = exported.map { it.first.name },
            failed = failed,
        )
    }

    /** Removes only the catalog addons generated from MDBList, preserving every other addon. */
    suspend fun disableMdblistAddons(): Result<Int> = runCatching {
        val current = dao.addons()
        val toRemove = current.filter { isMdblistCatalogAddon(it.base) }
        
        toRemove.forEach { dao.delete(it.base) }
        if (toRemove.isNotEmpty()) onLocalChange()

        toRemove.size
    }
}

/** New catalog keys append `@baseHash`; feed keys and legacy keys are unchanged. */
private fun legacyCatalogPreferenceKey(key: String): String =
    key.substringBeforeLast('@', missingDelimiterValue = key)

private fun rawCatalogs(rows: List<AddonEntity>): List<AddonCatalog> = rows.flatMap { row ->
    runCatching {
        HttpClients.json.decodeFromString(StremioManifestDto.serializer(), row.manifestJson)
    }.getOrNull()?.catalogs.orEmpty().mapNotNull { catalog ->
        if (catalog.id.isBlank() || catalog.type.isBlank()) return@mapNotNull null
        val originalName = catalog.name.ifBlank { row.name }
        AddonCatalog(
            addonBase = row.base,
            addonId = row.addonId,
            id = catalog.id,
            type = catalog.type,
            name = originalName,
            originalName = originalName,
        )
    }
}

/**
 * Whether a catalog entry is a container rather than a title.
 *
 * `channel_` is the prefix the Stremio protocol reserves for this, and the
 * addons that serve one declare it in their manifest's `idPrefixes` beside
 * `tt`. Anything else is read as a title and either resolves to an id this
 * app can use or is dropped, exactly as before.
 */
private fun isContainerId(id: String): Boolean =
    id.startsWith("channel_", ignoreCase = true)

private data class EpisodeId(val imdbId: String, val season: Int, val episode: Int)

/** `tt0412142:1:11` — the only place a scheduled episode is actually named. */
private fun parseEpisodeId(raw: String): EpisodeId? {
    val parts = raw.split(':')
    if (parts.size < 3) return null
    val imdbId = parts[0].takeIf { it.startsWith("tt", ignoreCase = true) } ?: return null
    val season = parts[1].toIntOrNull() ?: return null
    val episode = parts[2].toIntOrNull() ?: return null
    return EpisodeId(imdbId, season, episode)
}

/** Bounded like every other fan-out here: a catalog may list many channels. */
private const val CONTAINER_CONCURRENCY = 4

private fun encodeSegment(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

private const val MDBLIST_CATALOG_HOST = "stremio-mdblist.baby-beamup.club"

private fun isMdblistCatalogAddon(base: String): Boolean =
    base.contains(MDBLIST_CATALOG_HOST, ignoreCase = true)

private fun mdblistTransportUrl(listId: Long, apiKey: String): String =
    "https://1fe84bc728af-stremio-mdblist.baby-beamup.club/unified/" +
        "$listId/${encodeSegment(apiKey)}/mdblist/manifest.json"
