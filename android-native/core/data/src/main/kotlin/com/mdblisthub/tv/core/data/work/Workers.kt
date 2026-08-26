package com.mdblisthub.tv.core.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mdblisthub.tv.core.data.CachePolicy
import com.mdblisthub.tv.core.data.repository.AddonsRepository
import com.mdblisthub.tv.core.data.repository.ListsRepository
import com.mdblisthub.tv.core.data.repository.MediaRepository
import com.mdblisthub.tv.core.data.repository.PlaybackRepository
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.model.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Walks the account's lists and writes them into Room.
 *
 * This is what the home screen actually reads from. Running it on a schedule
 * rather than on open is the whole trick: the rows are already in the database
 * when the app launches, so the first frame is painted from disk and the
 * network is never on the critical path.
 */
class ListSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val lists: ListsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)

        lists.refreshLists(force).getOrElse { return retryOrFail() }

        // Each row is refreshed on its own so that one failing list does not
        // cost the other twenty-four theirs.
        var failures = 0
        lists.listsOnce().forEach { list ->
            lists.refreshItems(list.id, force).onFailure { failures++ }
            // A gentle spacing: twenty-five back-to-back requests is how an
            // API decides you are worth rate limiting.
            delay(SPACING_MS)
        }

        return if (failures > 0) retryOrFail() else Result.success()
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        const val NAME = "list-sync"
        const val KEY_FORCE = "force"
        private const val SPACING_MS = 120L
        private const val MAX_ATTEMPTS = 3
    }
}

/**
 * Hydrates the heavy half of the metadata cache: cast, artwork, ratings and
 * seasons, for titles that have a card but no detail row yet.
 *
 * Bounded on purpose. Hydrating a thousand titles in one pass would be three
 * thousand requests and a warm TV box; a batch at a time, run whenever the
 * device is idle and charging, gets there over a few nights without anyone
 * noticing it happened.
 */
class MetadataWorker(
    context: Context,
    params: WorkerParameters,
    private val media: MediaRepository,
    private val database: HubDatabase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - CachePolicy.DETAIL_MS
        val batch = database.mediaDao().staleDetails(cutoff, BATCH)
        if (batch.isEmpty()) return Result.success()

        var failures = 0
        batch.forEach { key ->
            media.ensureDetail(MediaType.parse(key.type), key.tmdbId)
                .onFailure { failures++ }
            delay(SPACING_MS)
        }

        // Something is left over almost every time, and that is by design —
        // WorkManager re-runs this on the next idle window.
        return if (failures == batch.size) Result.retry() else Result.success()
    }

    companion object {
        const val NAME = "metadata-hydrate"
        private const val BATCH = 40
        private const val SPACING_MS = 150L
    }
}

/**
 * Pre-warms the posters of the first screenful of every row.
 *
 * A poster that is already decoded on disk is the difference between a home
 * that appears and one that fills in tile by tile — the single most visible
 * thing about a TV app feeling light.
 */
class ArtworkWorker(
    context: Context,
    params: WorkerParameters,
    private val database: HubDatabase,
    private val addons: AddonsRepository,
    private val warmer: ImageWarmer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val listDao = database.listDao()
        val mediaDao = database.mediaDao()

        listDao.lists().forEach { list ->
            val urls = mediaDao.posterUrls(list.id, VISIBLE_PER_ROW)
            if (urls.isNotEmpty()) warmer.warm(urls)
        }

        // Catalogue rows used to be skipped entirely, because this only ever
        // walked `lists()` — which holds MDBList rows and nothing else. A home
        // screen made mostly of addon catalogues therefore got none of the
        // pre-warming this worker exists to provide, and filled in card by
        // card on every visit. Their posters do not live in Room (the
        // catalogue is fetched per session), so they are read from the addon
        // rather than the database.
        runCatching {
            addons.observeCatalogs().first()
                .filterNot { it.hidden }
                .forEach { catalog ->
                    val urls = addons.catalogItems(catalog).getOrDefault(emptyList())
                        .asSequence()
                        .mapNotNull { it.media.posterUrl }
                        .take(VISIBLE_PER_ROW)
                        .toList()
                    if (urls.isNotEmpty()) warmer.warm(urls)
                    delay(SPACING_MS)
                }
        }

        return Result.success()
    }

    companion object {
        const val NAME = "artwork-warm"

        /** Roughly what fits on a 1080p row, plus one card of lead-in. */
        private const val VISIBLE_PER_ROW = 8

        /** Same courtesy the other workers extend to the APIs they walk. */
        private const val SPACING_MS = 120L
    }
}

/** Keeps "continue watching" in step with what was watched elsewhere. */
class ResumeSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val playback: PlaybackRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        playback.refreshResumePoints().fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )

    companion object {
        const val NAME = "resume-sync"
    }
}

/**
 * Drops what the cache no longer owes anyone: detail rows for titles that have
 * fallen out of every list, and episode rows nobody has opened in a while.
 *
 * Without this the database only ever grows — a year of browsing would leave
 * thousands of detail rows for titles long since removed from the lists.
 */
class CachePruneWorker(
    context: Context,
    params: WorkerParameters,
    private val database: HubDatabase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = database.mediaDao()
        val now = System.currentTimeMillis()

        dao.pruneDetails(now - CachePolicy.ORPHAN_DETAIL_MS)
        dao.pruneEpisodes(now - CachePolicy.ORPHAN_DETAIL_MS)
        return Result.success()
    }

    companion object {
        const val NAME = "cache-prune"
    }
}
