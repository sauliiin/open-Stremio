package com.mdblisthub.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mdblisthub.tv.core.database.entity.AddonEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AddonDao {

    @Query("SELECT * FROM addons ORDER BY addedAt ASC")
    fun observeAddons(): Flow<List<AddonEntity>>

    @Query("SELECT * FROM addons ORDER BY addedAt ASC")
    suspend fun addons(): List<AddonEntity>

    @Upsert
    suspend fun upsert(addons: List<AddonEntity>)

    /**
     * Makes the table exactly `addons`. Backs the "Baixar" button — a merge
     * can only ever add, so on its own it would resurrect whatever was
     * deleted on another device.
     */
    @Transaction
    suspend fun replaceAll(addons: List<AddonEntity>) {
        clear()
        upsert(addons)
    }

    @Query("DELETE FROM addons WHERE base = :base")
    suspend fun delete(base: String)

    @Query("DELETE FROM addons")
    suspend fun clear()
}
