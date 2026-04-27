package com.sdl.grantha.data.download

import android.content.Context
import com.sdl.grantha.data.local.GranthaDao
import com.sdl.grantha.data.remote.MobileCatalogApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

/**
 * Manages downloading grantha .sdl files from the server.
 */
class GranthaDownloadManager(
    private val context: Context,
    private val api: MobileCatalogApi,
    private val dao: GranthaDao
) {

    data class DownloadProgress(
        val granthaName: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val isComplete: Boolean = false,
        val error: String? = null
    )

    data class BulkProgress(
        val currentIndex: Int,
        val totalCount: Int,
        val currentGrantha: String,
        val overallProgress: Float, // 0.0 to 1.0
        val isComplete: Boolean = false,
        val failedCount: Int = 0
    )

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: Flow<DownloadProgress?> = _downloadProgress.asStateFlow()

    private val _bulkProgress = MutableStateFlow<BulkProgress?>(null)
    val bulkProgress: Flow<BulkProgress?> = _bulkProgress.asStateFlow()

    @Volatile
    private var _isCancelled = false

    fun isCancelled() = _isCancelled

    private fun getGranthasDir(): File {
        val dir = File(context.filesDir, "granthas")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Download a single grantha.
     * Returns the file path on success, null on failure.
     */
    suspend fun downloadGrantha(
        name: String,
        downloadedBytesBefore: Long = 0L,
        totalBytesAll: Long = 0L
    ): String? = withContext(Dispatchers.IO) {
        try {
            _downloadProgress.value = DownloadProgress(name, 0, 0)

            val encodedName = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            val response = api.downloadGrantha(encodedName)

            if (!response.isSuccessful || response.body() == null) {
                _downloadProgress.value = DownloadProgress(name, 0, 0, error = "Server error: ${response.code()}")
                return@withContext null
            }

            val body = response.body()!!
            val totalBytes = body.contentLength()
            val file = File(getGranthasDir(), "${name.hashCode()}.sdl")

            body.byteStream().use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var read: Int

                    while (inputStream.read(buffer).also { read = it } != -1) {
                        if (isCancelled()) {
                            file.delete()
                            _downloadProgress.value = DownloadProgress(name, 0, 0, error = "Cancelled")
                            return@withContext null
                        }
                        outputStream.write(buffer, 0, read)
                        bytesRead += read
                        _downloadProgress.value = DownloadProgress(name, bytesRead, totalBytes)

                        // Update bulk progress if part of a bulk download
                        if (totalBytesAll > 0) {
                            val currentBulk = _bulkProgress.value
                            if (currentBulk != null) {
                                _bulkProgress.value = currentBulk.copy(
                                    overallProgress = (downloadedBytesBefore + bytesRead).toFloat() / totalBytesAll
                                )
                            }
                        }
                    }
                }
            }

            // Mark as downloaded in database
            dao.markDownloaded(name, System.currentTimeMillis(), file.absolutePath)

            _downloadProgress.value = DownloadProgress(name, file.length(), file.length(), isComplete = true)
            file.absolutePath
        } catch (e: Exception) {
            _downloadProgress.value = DownloadProgress(name, 0, 0, error = e.message ?: "Unknown error")
            null
        }
    }

    /**
     * Download multiple granthas sequentially.
     */
    suspend fun downloadMultiple(names: List<String>) = withContext(Dispatchers.IO) {
        _isCancelled = false
        var failedCount = 0

        // Calculate total bytes
        var totalBytesAll = 0L
        for (name in names) {
            val grantha = dao.getGranthaByName(name)
            totalBytesAll += grantha?.sizeBytes ?: 0L
        }

        var downloadedBytesAll = 0L

        for ((index, name) in names.withIndex()) {
            if (isCancelled()) break

            val grantha = dao.getGranthaByName(name)
            val currentGranthaSize = grantha?.sizeBytes ?: 0L

            _bulkProgress.value = BulkProgress(
                currentIndex = index,
                totalCount = names.size,
                currentGrantha = name,
                overallProgress = if (totalBytesAll > 0) downloadedBytesAll.toFloat() / totalBytesAll else 0f,
                failedCount = failedCount
            )

            val result = downloadGrantha(name, downloadedBytesAll, totalBytesAll)
            if (result == null) failedCount++

            downloadedBytesAll += currentGranthaSize
        }

        _bulkProgress.value = BulkProgress(
            currentIndex = names.size,
            totalCount = names.size,
            currentGrantha = "",
            overallProgress = 1f,
            isComplete = true,
            failedCount = failedCount
        )
    }

    /**
     * Delete a downloaded grantha file.
     */
    suspend fun deleteGrantha(name: String) = withContext(Dispatchers.IO) {
        val grantha = dao.getGranthaByName(name) ?: return@withContext
        grantha.filePath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }
        dao.markDeleted(name)
    }

    /**
     * Delete all downloaded grantha files.
     */
    suspend fun deleteAllDownloads() = withContext(Dispatchers.IO) {
        val dir = getGranthasDir()
        dir.listFiles()?.forEach { it.delete() }
        // DAO will be updated by the repository
    }

    /**
     * Clean up any physical files that are not referenced in the validPaths list.
     */
    suspend fun cleanupOrphanedFiles(validPaths: Set<String>) = withContext(Dispatchers.IO) {
        val dir = getGranthasDir()
        dir.listFiles()?.forEach { file ->
            if (file.absolutePath !in validPaths) {
                file.delete()
            }
        }
    }

    fun cancelDownloads() {
        _isCancelled = true
        // Mark progress as complete/error so UI resets immediately
        _bulkProgress.value = _bulkProgress.value?.copy(isComplete = true)
        _downloadProgress.value = _downloadProgress.value?.copy(error = "Cancelled")
    }

    fun resetCancel() {
        _isCancelled = false
    }

    fun clearProgress() {
        _downloadProgress.value = null
        _bulkProgress.value = null
    }
}
