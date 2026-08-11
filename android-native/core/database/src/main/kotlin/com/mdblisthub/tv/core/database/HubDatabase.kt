package com.mdblisthub.tv.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction
import com.mdblisthub.tv.core.database.dao.AddonDao
import com.mdblisthub.tv.core.database.dao.ListDao
import com.mdblisthub.tv.core.database.dao.MediaDao
import com.mdblisthub.tv.core.database.dao.PlaybackDao
import com.mdblisthub.tv.core.database.entity.AddonEntity
import com.mdblisthub.tv.core.database.entity.EpisodeEntity
import com.mdblisthub.tv.core.database.entity.LibraryEntity
import com.mdblisthub.tv.core.database.entity.ListEntity
import com.mdblisthub.tv.core.database.entity.ListItemEntity
import com.mdblisthub.tv.core.database.entity.MediaDetailEntity
import com.mdblisthub.tv.core.database.entity.MediaEntity
import com.mdblisthub.tv.core.database.entity.ResumeEntity

@Database(
    entities = [
        MediaEntity::class,
        MediaDetailEntity::class,
        ListEntity::class,
        ListItemEntity::class,
        EpisodeEntity::class,
        AddonEntity::class,
        ResumeEntity::class,
        LibraryEntity::class,
    ],
    // 9 separates landscape card art from the full-screen backdrop. Destructive
    // migration is correct here for the same reason as always — every table is
    // a cache with an upstream source of truth — and re-syncing is cheaper
    // than the two full table scans the missing indices cost.
    version = 9,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HubDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun listDao(): ListDao
    abstract fun addonDao(): AddonDao
    abstract fun playbackDao(): PlaybackDao

    /**
     * Clears only data owned by the linked MDBList account. Addons are owned
     * by the Google/Firebase session and must survive replacing an MDBList API
     * key under that same Google account.
     */
    suspend fun clearMdblistAccountData() = withTransaction {
        listDao().clearAllItems()
        listDao().clearLists()
        mediaDao().clearEpisodes()
        mediaDao().clearDetails()
        mediaDao().clearMedia()
        playbackDao().clearResumePoints()
        playbackDao().clearLibrary()
    }

    companion object {
        private const val NAME = "mdblist-hub.db"

        fun create(context: Context): HubDatabase =
            Room.databaseBuilder(context.applicationContext, HubDatabase::class.java, NAME)
                // Everything in here is a cache with an upstream source of
                // truth, so throwing it away and re-syncing is a correct
                // migration for as long as that stays true.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
