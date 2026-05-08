package com.sdl.grantha.data.repository

import android.content.Context
import android.os.Environment
import android.util.Log
import com.sdl.grantha.data.crypto.SdlCryptoManager
import com.sdl.grantha.data.download.GranthaDownloadManager
import com.sdl.grantha.data.local.GranthaDao
import com.sdl.grantha.data.local.GranthaEntity
import com.sdl.grantha.data.remote.MobileCatalogApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.sdl.grantha.BuildConfig
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
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
    private val downloadManager: GranthaDownloadManager,
    @ApplicationContext private val context: Context
) {

    private fun getBackupPassword(): String {
        // Derive a highly complex password from the internal SDL_KEY combined with a unique salt
        // This ensures the zip is extremely difficult to crack even with brute force
        val salt = "SDL_Vault_Secure_Salt_v1_2024_Sanskrit"
        val combined = BuildConfig.SDL_KEY + salt
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(combined.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

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
                    subbooksRaw = item.subbooks,
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

    suspend fun getAllGranthasOnce(): List<GranthaEntity> = dao.getAllGranthasOnce()

    fun getDownloadedGranthas(): Flow<List<GranthaEntity>> = dao.getDownloadedGranthas()

    fun searchGranthas(query: String): Flow<List<GranthaEntity>> = dao.searchGranthas(query)

    fun searchDownloadedGranthas(query: String): Flow<List<GranthaEntity>> =
        dao.searchDownloadedGranthas(query)

    suspend fun getGranthaByName(name: String): GranthaEntity? = dao.getGranthaByName(name)

    suspend fun getTotalCount(): Int = dao.getTotalCount()

    suspend fun getDownloadedCount(): Int = dao.getDownloadedCount()

    suspend fun getDownloadedSizeBytes(): Long = dao.getDownloadedSizeBytes() ?: 0L

    fun getTotalCountFlow(): Flow<Int> = dao.getTotalCountFlow()

    fun getDownloadedCountFlow(): Flow<Int> = dao.getDownloadedCountFlow()

    fun getDownloadedSizeBytesFlow(): Flow<Long> = dao.getDownloadedSizeBytesFlow().map { it ?: 0L }

    suspend fun getAllTags(): List<String> {
        val rawTags = dao.getAllTags()
        return rawTags.flatMap { tagStr ->
            tagStr.split(",").map { it.trim() }
        }.filter { it.isNotBlank() }.distinct().sorted()
    }

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
        // This will remove EVERY file in the granthas directory
        downloadManager.deleteAllDownloads()
        // Mark all as not downloaded in DB
        withContext(Dispatchers.IO) {
            dao.markAllDeleted()
        }
    }

    data class HealthCheckResult(
        val totalFixed: Int,
        val missingFilesFixed: Int,
        val orphanedFilesDeleted: Int
    )

    // ======================== Health & Backup ========================

    /**
     * Library Health Check:
     * 1. Sync catalog first to remove books no longer on server
     * 2. Check if all books marked as 'isDownloaded' actually exist on disk.
     * 3. Clean up orphaned files in the download folder.
     */
    suspend fun libraryHealthCheck(): Result<HealthCheckResult> = withContext(Dispatchers.IO) {
        try {
            var missingFixed = 0

            // 1. Sync catalog
            syncCatalog()

            // 2. Check for missing files
            val downloaded = dao.getDownloadedGranthasOnce()
            downloaded.forEach { grantha ->
                val file = grantha.filePath?.let { File(it) }
                if (file == null || !file.exists()) {
                    dao.markDeleted(grantha.name)
                    missingFixed++
                }
            }

            // 3. Clean up orphaned files
            val validPaths = dao.getAllGranthasOnce()
                .filter { it.isDownloaded }
                .mapNotNull { it.filePath }
                .toSet()
            val orphanedDeleted = downloadManager.cleanupOrphanedFiles(validPaths)

            Result.success(HealthCheckResult(
                totalFixed = missingFixed + orphanedDeleted,
                missingFilesFixed = missingFixed,
                orphanedFilesDeleted = orphanedDeleted
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Backup library data to Downloads/SDL_Backup
     */
    suspend fun backupLibrary(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val backupDir = File(downloadsDir, "SDL_Backup")
            if (!backupDir.exists()) backupDir.mkdirs()

            val backupFile = File(backupDir, "SDL_Library_Backup.zip")
            if (backupFile.exists()) backupFile.delete()

            val granthasDir = File(context.filesDir, "granthas")
            if (!granthasDir.exists() || granthasDir.listFiles().isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No downloaded books found to backup"))
            }

            val zipFile = ZipFile(backupFile, getBackupPassword().toCharArray())
            val parameters = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.ZIP_STANDARD
                compressionMethod = net.lingala.zip4j.model.enums.CompressionMethod.STORE
            }

            val filesToBackup = granthasDir.listFiles { _, name -> name.endsWith(".sdl") }?.toList() ?: emptyList()
            if (filesToBackup.isEmpty()) {
                return@withContext Result.failure(Exception("No .sdl files found in library"))
            }

            zipFile.addFiles(filesToBackup, parameters)

            Result.success(backupFile.absolutePath)
        } catch (e: Exception) {
            Log.e("GranthaRepository", "Backup failed", e)
            Result.failure(e)
        }
    }

    /**
     * Restore library data from Downloads/SDL_Backup
     */
    suspend fun restoreLibrary(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val backupDir = File(downloadsDir, "SDL_Backup")
            val backupFile = File(backupDir, "SDL_Library_Backup.zip")
            
            if (!backupFile.exists()) {
                return@withContext Result.failure(Exception("Backup file 'SDL_Library_Backup.zip' not found in Downloads/SDL_Backup"))
            }

            val granthasDir = File(context.filesDir, "granthas")
            if (!granthasDir.exists()) granthasDir.mkdirs()

            val zipFile = ZipFile(backupFile, getBackupPassword().toCharArray())
            if (!zipFile.isValidZipFile) {
                return@withContext Result.failure(Exception("Invalid or corrupted backup file"))
            }
            
            if (zipFile.isEncrypted) {
                // Try to extract with key
                zipFile.extractAll(granthasDir.absolutePath)
            } else {
                return@withContext Result.failure(Exception("Backup is not encrypted as expected"))
            }

            var restoreCount = 0
            granthasDir.listFiles()?.forEach { file ->
                if (file.extension == "sdl" && cryptoManager.isValidSdlFile(file)) {
                    restoreCount++
                }
            }

            // 2. Sync Database with the restored files in bulk
            val allGranthas = dao.getAllGranthasOnce()
            val restoredEntities = allGranthas.mapNotNull { grantha ->
                val expectedFileName = "${grantha.name.hashCode()}.sdl"
                val file = File(granthasDir, expectedFileName)
                if (file.exists()) {
                    grantha.copy(
                        isDownloaded = true,
                        downloadDate = System.currentTimeMillis(),
                        filePath = file.absolutePath
                    )
                } else null
            }
            
            if (restoredEntities.isNotEmpty()) {
                dao.insertAll(restoredEntities)
            }

            Result.success(restoreCount)
        } catch (e: Exception) {
            Log.e("GranthaRepository", "Restore failed", e)
            Result.failure(e)
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
     * Parse sub-books from the subbooksRaw field.
     * Format: "1-50:BookName1, 51-120:BookName2"
     */
    fun parseSubBooks(subbooksRaw: String): List<SubBook> {
        if (subbooksRaw.isBlank()) return emptyList()
        return subbooksRaw.split(",").mapNotNull { entry ->
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
