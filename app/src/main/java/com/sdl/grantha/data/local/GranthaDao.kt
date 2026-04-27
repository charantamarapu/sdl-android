package com.sdl.grantha.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for granthas table.
 */
@Dao
interface GranthaDao {

    @Query("SELECT * FROM granthas ORDER BY name ASC")
    fun getAllGranthas(): Flow<List<GranthaEntity>>

    @Query("SELECT * FROM granthas")
    suspend fun getAllGranthasOnce(): List<GranthaEntity>

    @Query("SELECT * FROM granthas WHERE isDownloaded = 1 ORDER BY name ASC")
    fun getDownloadedGranthas(): Flow<List<GranthaEntity>>

    @Query("SELECT * FROM granthas WHERE isDownloaded = 1")
    suspend fun getDownloadedGranthasOnce(): List<GranthaEntity>

    @Query("SELECT * FROM granthas WHERE name = :name LIMIT 1")
    suspend fun getGranthaByName(name: String): GranthaEntity?

    @Query("SELECT * FROM granthas WHERE name LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchGranthas(query: String): Flow<List<GranthaEntity>>

    @Query("SELECT * FROM granthas WHERE isDownloaded = 1 AND (name LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%') ORDER BY name ASC")
    fun searchDownloadedGranthas(query: String): Flow<List<GranthaEntity>>

    @Query("SELECT * FROM granthas WHERE isDownloaded = 1 AND (tags LIKE '%' || :tag || '%') ORDER BY name ASC")
    fun getDownloadedGranthasByTag(tag: String): Flow<List<GranthaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(granthas: List<GranthaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(grantha: GranthaEntity)

    @Update
    suspend fun update(grantha: GranthaEntity)

    @Query("UPDATE granthas SET isDownloaded = 1, downloadDate = :date, filePath = :path WHERE name = :name")
    suspend fun markDownloaded(name: String, date: Long, path: String)

    @Query("UPDATE granthas SET isDownloaded = 0, downloadDate = NULL, filePath = NULL WHERE name = :name")
    suspend fun markDeleted(name: String)

    @Query("UPDATE granthas SET isDownloaded = 0, downloadDate = NULL, filePath = NULL WHERE isDownloaded = 1")
    suspend fun markAllDeleted()

    @Query("DELETE FROM granthas WHERE name NOT IN (:serverNames)")
    suspend fun deleteStaleGranthas(serverNames: List<String>)

    @Query("SELECT COUNT(*) FROM granthas")
    fun getTotalCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM granthas WHERE isDownloaded = 1")
    fun getDownloadedCountFlow(): Flow<Int>

    @Query("SELECT SUM(sizeBytes) FROM granthas WHERE isDownloaded = 1")
    fun getDownloadedSizeBytesFlow(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM granthas")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM granthas WHERE isDownloaded = 1")
    suspend fun getDownloadedCount(): Int

    @Query("SELECT SUM(sizeBytes) FROM granthas WHERE isDownloaded = 1")
    suspend fun getDownloadedSizeBytes(): Long?

    @Query("SELECT DISTINCT tags FROM granthas WHERE tags != ''")
    suspend fun getAllTags(): List<String>

    @Query("SELECT DISTINCT tags FROM granthas WHERE isDownloaded = 1 AND tags != ''")
    suspend fun getAllDownloadedTags(): List<String>
}
