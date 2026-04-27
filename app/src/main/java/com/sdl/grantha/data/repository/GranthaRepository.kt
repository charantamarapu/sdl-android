package com.sdl.grantha.data.repository

import com.sdl.grantha.data.crypto.SdlCryptoManager
import com.sdl.grantha.data.download.GranthaDownloadManager
import com.sdl.grantha.data.local.GranthaDao
import com.sdl.grantha.data.local.GranthaEntity
import com.sdl.grantha.data.remote.MobileCatalogApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Repository that coordinates between server API, local database, and encrypted storage.
 */
@Singleton
class GranthaRepository @Inject constructor(
    private val api: MobileCatalogApi,
    private val dao: GranthaDao,
    private val cryptoManager: SdlCryptoManager,
    private val downloadManager: GranthaDownloadManager
) {

    // ======================== Catalog ========================

    /**
     * Sync the catalog from the server into the local Room database.
     * Preserves download state for already-downloaded granthas.
     */
    suspend fun syncCatalog(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val response = api.getCatalog()
            val serverGranthas = response.granthas
            val serverNames = serverGranthas.map { it.name }

            // Get existing downloaded state in one bulk query
            val existingList = dao.getAllGranthasOnce()
            val existingMap = existingList.associateBy { it.name }

            // Find and delete physical files for books that are no longer on the server
            val staleGranthas = existingList.filter { it.name !in serverNames }
            staleGranthas.forEach { stale ->
                stale.filePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) file.delete()
                }
            }

            // Delete books that are no longer on the server
            dao.deleteStaleGranthas(serverNames)

            // Clean up any remaining orphaned files (e.g. from previous bugs)
            val validFilePaths = existingList
                .filter { it.name in serverNames }
                .mapNotNull { it.filePath }
                .toSet()
            downloadManager.cleanupOrphanedFiles(validFilePaths)

            // Upsert: merge server metadata with local download state
            val entities = serverGranthas.map { item ->
                val existing = existingMap[item.name]
                GranthaEntity(
                    name = item.name,
                    tags = item.tags,
                    sourceUrl = item.sourceUrl,
                    identifier = item.identifier,
                    sizeBytes = item.sizeBytes,
                    pageCount = item.pageCount,
                    checksum = item.checksum,
                    booksRaw = item.books,
                    // Preserve local state
                    isDownloaded = existing?.isDownloaded ?: false,
                    downloadDate = existing?.downloadDate,
                    filePath = existing?.filePath
                )
            }

            dao.insertAll(entities)
            Result.success(entities.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ======================== Queries ========================

    fun getAllGranthas(): Flow<List<GranthaEntity>> = dao.getAllGranthas()

    fun getDownloadedGranthas(): Flow<List<GranthaEntity>> = dao.getDownloadedGranthas()

    fun searchGranthas(query: String): Flow<List<GranthaEntity>> = dao.searchGranthas(query)

    fun searchDownloadedGranthas(query: String): Flow<List<GranthaEntity>> =
        dao.searchDownloadedGranthas(query)

    suspend fun getGranthaByName(name: String): GranthaEntity? = dao.getGranthaByName(name)

    suspend fun getTotalCount(): Int = dao.getTotalCount()

    suspend fun getDownloadedCount(): Int = dao.getDownloadedCount()

    suspend fun getDownloadedSizeBytes(): Long = dao.getDownloadedSizeBytes() ?: 0L

    suspend fun getAllDownloadedTags(): List<String> {
        val rawTags = dao.getAllDownloadedTags()
        // Split comma-separated tags and flatten
        return rawTags.flatMap { tagStr ->
            tagStr.split(",").map { it.trim() }
        }.filter { it.isNotBlank() }.distinct().sorted()
    }

    // ======================== Download ========================

    suspend fun downloadGrantha(name: String): String? =
        downloadManager.downloadGrantha(name)

    suspend fun downloadMultiple(names: List<String>) =
        downloadManager.downloadMultiple(names)

    fun cancelDownloads() = downloadManager.cancelDownloads()

    fun resetCancel() = downloadManager.resetCancel()

    fun isCancelled() = downloadManager.isCancelled()

    fun getDownloadProgress() = downloadManager.downloadProgress

    fun getBulkProgress() = downloadManager.bulkProgress

    fun clearProgress() = downloadManager.clearProgress()

    // ======================== Delete ========================

    suspend fun deleteGrantha(name: String) =
        downloadManager.deleteGrantha(name)

    suspend fun deleteAllDownloads() {
        downloadManager.deleteAllDownloads()
        // Mark all as not downloaded in DB
        withContext(Dispatchers.IO) {
            dao.markAllDeleted()
        }
    }

    // ======================== Text Access ========================

    /**
     * Get the decrypted text content of a downloaded grantha.
     * Returns null if not downloaded or decryption fails.
     * Uses timeout to prevent hangs on slow devices.
     */
    suspend fun getGranthaText(name: String): String? = withContext(Dispatchers.IO) {
        val grantha = dao.getGranthaByName(name) ?: return@withContext null
        if (!grantha.isDownloaded || grantha.filePath == null) return@withContext null

        val file = File(grantha.filePath)
        if (!file.exists() || !cryptoManager.isValidSdlFile(file)) {
            // File missing or corrupt — mark as not downloaded
            dao.markDeleted(name)
            return@withContext null
        }

        // Attempt decryption with timeout (60 seconds)
        // This prevents slow devices from hanging indefinitely
        val result = try {
            withTimeoutOrNull(60000L) {
                cryptoManager.decryptText(file)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("GranthaRepository", "Decryption timeout for book: $name")
            null
        } catch (e: OutOfMemoryError) {
            Log.e("GranthaRepository", "OutOfMemory while decrypting: $name", e)
            null
        } catch (e: Exception) {
            Log.e("GranthaRepository", "Error decrypting book: $name", e)
            null
        }
        
        result
    }

    /**
     * Parse sub-books from the booksRaw field.
     * Format: "1-50:BookName1, 51-120:BookName2"
     */
    fun parseSubBooks(booksRaw: String): List<SubBook> {
        if (booksRaw.isBlank()) return emptyList()
        return booksRaw.split(",").mapNotNull { entry ->
            val trimmed = entry.trim()
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx < 0) return@mapNotNull null
            val rangePart = trimmed.substring(0, colonIdx).trim()
            val name = trimmed.substring(colonIdx + 1).trim()
            if (name.isBlank()) return@mapNotNull null

            try {
                if ('-' in rangePart) {
                    val (start, end) = rangePart.split('-', limit = 2).map { it.trim().toInt() }
                    SubBook(start, end, name)
                } else {
                    val page = rangePart.toInt()
                    SubBook(page, page, name)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    data class SubBook(val startPage: Int, val endPage: Int, val name: String)
}
