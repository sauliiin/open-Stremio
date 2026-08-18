package com.mdblisthub.tv.core.data.repository

import androidx.room.withTransaction
import com.mdblisthub.tv.core.data.CachePolicy
import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.data.mapper.toDomain
import com.mdblisthub.tv.core.data.mapper.toEntity
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.database.entity.ListItemEntity
import com.mdblisthub.tv.core.model.AppError
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaList
import com.mdblisthub.tv.core.model.requireOrFail
import com.mdblisthub.tv.core.network.MdblistApi
import com.mdblisthub.tv.core.network.dto.FirebaseListPreferenceDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The home screen's rows.
 *
 * Every read comes out of Room, never off the wire: `observeLists` and
 * `observeItems` emit whatever was last synced, immediately, and a refresh
 * writes over it in the background. That is what makes the home paint on the
 * first frame of a cold start instead of after twenty-five requests.
 */
class ListsRepository(
    private val api: MdblistApi,
    private val session: SessionStore,
    private val database: HubDatabase,
) {
    private val listDao = database.listDao()
    private val mediaDao = database.mediaDao()

    fun observeLists(): Flow<List<MediaList>> =
        combine(listDao.observeLists(), session.deletedListIds) { rows, deletedIds ->
            rows.filterNot { it.id in deletedIds }.map { it.toDomain() }
        }

    /** A one-shot read of the cached rows, for the workers. */
    suspend fun listsOnce(): List<MediaList> = listDao.lists().map { it.toDomain() }

    fun observeItems(listId: Long): Flow<List<MediaItem>> =
        mediaDao.observeListItems(listId).map { rows -> rows.map { it.toDomain() } }

    suspend fun refreshLists(force: Boolean = false): Result<Unit> = runCatching {
        val key = session.currentKey()
        if (key.isBlank()) return@runCatching

        val existing = listDao.lists()
        if (!force && existing.isNotEmpty() &&
            existing.none { CachePolicy.isStale(it.fetchedAt, CachePolicy.LISTS_MS) }
        ) return@runCatching

        val now = System.currentTimeMillis()
        // No owner-only catalogue: every account sees all of its own non-empty
        // lists. Existing positions survive refreshes; new lists are appended.
        val storedPreferences = session.currentListPreferences()
        val storedCatalogPreferences = session.currentCatalogPreferences()
        val preferencesById = storedPreferences.associateBy { it.id }
        val deletedIds = storedPreferences.filter { it.deleted }.mapTo(mutableSetOf()) { it.id }
        val incoming = api.lists(
            apiKey = key,
            cacheControl = "no-store".takeIf { force },
        ).filter { it.items > 0 && it.id !in deletedIds }
        val byId = existing.associateBy { it.id }
        var nextPosition = maxOf(
            existing.maxOfOrNull { it.position } ?: -1,
            storedPreferences.filterNot { it.deleted }.maxOfOrNull { it.position } ?: -1,
            storedCatalogPreferences.filterNot { it.deleted }.mapNotNull { it.position }.maxOrNull() ?: -1,
        ) + 1

        val incomingEntities = incoming.mapNotNull { dto ->
                val cached = byId[dto.id]
                val preference = preferencesById[dto.id]
                if (preference?.deleted == true) return@mapNotNull null

                dto.toEntity(
                    // A null preference explicitly means "follow MDBList".
                    // Reusing the cached name here made an upstream rename
                    // look like a permanent local rename after one refresh.
                    displayName = preference?.name ?: dto.name,
                    position = preference?.position ?: cached?.position ?: nextPosition++,
                    now = now,
                    hidden = preference?.hidden ?: cached?.hidden ?: false,
                    // The list index and its contents have independent cache
                    // clocks. If MDBList says this list changed, invalidate
                    // the item clock so the lazy row refreshes immediately.
                    itemsFetchedAt = cached?.itemsFetchedAt
                        ?.takeIf { cached.updatedAt == dto.lastUpdatedAt }
                        ?: 0,
                )
            }
        val incomingIds = incomingEntities.mapTo(mutableSetOf()) { it.id }
        database.withTransaction {
            listDao.upsertLists(incomingEntities)
            // Room's `NOT IN (:ids)` has ambiguous SQL semantics for an
            // empty collection. Delete explicit ids instead, including their
            // orphanable item rows, so removing the final MDBList list works.
            existing.asSequence()
                .map { it.id }
                .filterNot { it in incomingIds }
                .forEach { removedId ->
                    listDao.clearItems(removedId)
                    listDao.deleteListRow(removedId)
                }
        }
    }

    suspend fun toggleVisibility(listId: Long, hidden: Boolean) = runCatching {
        listDao.updateHidden(listId, hidden)
        capturePreferences()
    }

    suspend fun move(listId: Long, direction: Int) = runCatching {
        require(direction == -1 || direction == 1)
        listDao.moveList(listId, direction)
        capturePreferences()
    }

    /** Applies the normalized order shared with Stremio catalog rows. */
    suspend fun setPositions(positions: Map<Long, Int>) = runCatching {
        if (positions.isEmpty()) return@runCatching
        database.withTransaction {
            positions.forEach { (id, position) -> listDao.updatePosition(id, position) }
        }
        capturePreferences()
    }

    suspend fun rename(listId: Long, rawName: String) = runCatching {
        val name = rawName.trim()
        requireOrFail(name.isNotEmpty()) { AppError.NameRequired }
        listDao.updateName(listId, name)
        capturePreferences()
    }

    /** Removes the row locally and records a tombstone so refresh cannot restore it. */
    suspend fun delete(listId: Long) = runCatching {
        session.markListDeleted(listId)
        listDao.deleteList(listId)
        capturePreferences()
    }

    /** Snapshots every local customization; SessionStore drives the cloud writer. */
    suspend fun capturePreferences(): List<FirebaseListPreferenceDto> {
        val rows = listDao.lists()
        val liveIds = rows.mapTo(mutableSetOf()) { it.id }
        val tombstones = session.currentListPreferences()
            .filter { it.deleted && it.id !in liveIds }
        val snapshot = rows.map { row ->
            FirebaseListPreferenceDto(
                id = row.id,
                name = row.name.takeIf { it != row.originalName },
                position = row.position,
                hidden = row.hidden,
            )
        } + tombstones
        session.saveListPreferences(snapshot)
        return snapshot
    }

    /** Applies a cloud snapshot to rows already cached; refresh uses the same snapshot for new rows. */
    suspend fun applyStoredPreferences() = runCatching {
        val preferences = session.currentListPreferences()
        database.withTransaction {
            val rowsById = listDao.lists().associateBy { it.id }
            preferences.forEach { preference ->
                if (preference.deleted) {
                    listDao.clearItems(preference.id)
                    listDao.deleteListRow(preference.id)
                } else {
                    val row = rowsById[preference.id] ?: return@forEach
                    listDao.updateName(preference.id, preference.name ?: row.originalName)
                    listDao.updateHidden(preference.id, preference.hidden)
                    listDao.updatePosition(preference.id, preference.position)
                }
            }
        }
    }

    /**
     * Pulls the first page of a list. `replace` rather than merge, because a
     * title removed upstream must disappear — an upsert can only ever add.
     */
    suspend fun refreshItems(listId: Long, force: Boolean = false): Result<Unit> = runCatching {
        val key = session.currentKey()
        if (key.isBlank()) return@runCatching

        val cached = listDao.observeList(listId).first()
        if (!force && cached != null && listDao.itemCount(listId) > 0 &&
            !CachePolicy.isStale(cached.itemsFetchedAt, CachePolicy.LIST_ITEMS_MS)
        ) return@runCatching

        val now = System.currentTimeMillis()
        val items = api.listItems(listId, key, PAGE_SIZE, 0, unified = true, append = APPEND)

        database.withTransaction {
            mediaDao.upsert(items.map { it.toEntity(now) })
            listDao.replaceItems(
                listId,
                items.mapIndexed { index, dto ->
                    val entity = dto.toEntity(now)
                    ListItemEntity(listId, entity.tmdbId, entity.type, index)
                },
            )
            listDao.updateItemsFetchedAt(listId, now)
        }
    }

    /** Appends the next page, for a row scrolled to its end. Answers how many. */
    suspend fun loadMore(listId: Long): Result<Int> = runCatching {
        val key = session.currentKey()
        if (key.isBlank()) return@runCatching 0

        val offset = listDao.itemCount(listId)
        val now = System.currentTimeMillis()
        val items = api.listItems(listId, key, PAGE_SIZE, offset, unified = true, append = APPEND)
        if (items.isEmpty()) return@runCatching 0

        mediaDao.upsert(items.map { it.toEntity(now) })
        listDao.appendItems(
            items.mapIndexed { index, dto ->
                val entity = dto.toEntity(now)
                ListItemEntity(listId, entity.tmdbId, entity.type, offset + index)
            },
        )
        items.size
    }

    private companion object {
        const val PAGE_SIZE = 40
        const val APPEND = "poster,genre,ratings"
    }
}
