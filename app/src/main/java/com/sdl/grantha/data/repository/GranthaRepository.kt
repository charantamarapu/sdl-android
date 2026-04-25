package com.sdl.grantha.data.repository

import com.sdl.grantha.data.crypto.SdlCryptoManager
import com.sdl.grantha.data.download.GranthaDownloadManager
import com.sdl.grantha.data.local.GranthaDao
import com.sdl.grantha.data.local.GranthaEntity
import com.sdl.grantha.data.remote.MobileCatalogApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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

            // Get existing downloaded state
            val existingMap = mutableMapOf<String, GranthaEntity>()
            // We can't collect a Flow here, so query directly
            for (item in serverGranthas) {
                val existing = dao.getGranthaByName(item.name)
                if (existing != null) {
                    existingMap[item.name] = existing
                }
            }

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

        try {
            cryptoManager.decryptText(file)
        } catch (e: Exception) {
            null
        }
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
