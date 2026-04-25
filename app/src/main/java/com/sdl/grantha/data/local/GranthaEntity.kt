package com.sdl.grantha.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a grantha (book) in the local database.
 * Stores both catalog metadata (synced from server) and local download state.
 */
@Entity(tableName = "granthas")
data class GranthaEntity(
    @PrimaryKey
    val name: String,
    val tags: String = "",
    val sourceUrl: String = "",
    val identifier: String = "",
    val sizeBytes: Long = 0,
    val pageCount: Int = 0,
    val checksum: String = "",
    val booksRaw: String = "",
    // Local state
    val isDownloaded: Boolean = false,
    val downloadDate: Long? = null,
    val filePath: String? = null
)
