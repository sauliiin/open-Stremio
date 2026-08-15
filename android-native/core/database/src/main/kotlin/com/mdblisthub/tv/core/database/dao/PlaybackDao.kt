package com.mdblisthub.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mdblisthub.tv.core.database.entity.LibraryEntity
import com.mdblisthub.tv.core.database.entity.ResumeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackDao {

    @Query("SELECT * FROM resume_points ORDER BY updatedAt DESC")
    fun observeResumePoints(): Flow<List<ResumeEntity>>

    /**
     * The whole row is replaced rather than merged: mdblist owns the sessions,
     * so anything not in its answer has been finished or cleared elsewhere and
     * must stop appearing here.
     */
    @Transaction
    suspend fun replaceResumePoints(points: List<ResumeEntity>) {
        clearResumePoints()
        upsertResumePoints(points)
    }

    @Upsert
    suspend fun upsertResumePoints(points: List<ResumeEntity>)

    @Query("DELETE FROM resume_points")
    suspend fun clearResumePoints()

    /**
     * One row, by key. The entity rather than the domain type on purpose: the
     * caller wants `playbackId`, which is a provider detail deliberately kept
     * out of [com.mdblisthub.tv.core.model.ResumePoint].
     */
    @Query("SELECT * FROM resume_points WHERE `key` = :key")
    suspend fun resumePoint(key: String): ResumeEntity?

    @Query("DELETE FROM resume_points WHERE `key` = :key")
    suspend fun deleteResumePoint(key: String)

    // ------------------------------------------------------------- library

    @Query("SELECT tmdbId FROM library WHERE bucket = :bucket")
    fun observeBucket(bucket: String): Flow<List<Int>>

    @Query("SELECT EXISTS(SELECT 1 FROM library WHERE bucket = :bucket AND tmdbId = :tmdbId)")
    fun observeMembership(bucket: String, tmdbId: Int): Flow<Boolean>

    @Transaction
    suspend fun replaceBucket(bucket: String, entries: List<LibraryEntity>) {
        clearBucket(bucket)
        upsertLibrary(entries)
    }

    @Upsert
    suspend fun upsertLibrary(entries: List<LibraryEntity>)

    @Query("DELETE FROM library WHERE bucket = :bucket")
    suspend fun clearBucket(bucket: String)

    @Query("DELETE FROM library WHERE bucket = :bucket AND tmdbId = :tmdbId")
    suspend fun removeFromBucket(bucket: String, tmdbId: Int)

    @Query("DELETE FROM library")
    suspend fun clearLibrary()
}
