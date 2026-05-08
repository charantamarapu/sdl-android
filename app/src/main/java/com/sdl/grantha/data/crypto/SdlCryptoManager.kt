package com.sdl.grantha.data.crypto

import java.io.File
import java.util.zip.GZIPInputStream
import com.github.luben.zstd.ZstdInputStream

/**
 * Manages storage and retrieval of .sdl grantha files.
 * Uses modern Gzip compression (v2).
 *
 * SDL File Format (v3 - Zstd):
 *   [4 bytes] Magic: "SDL\x03"
 *   [remaining] Zstd-compressed UTF-8 text content
 */
class SdlCryptoManager {

    companion object {
        private const val MAGIC = "SDL"
        private const val VERSION_GZIP: Byte = 0x02
        private const val VERSION_ZSTD: Byte = 0x03
    }

    /**
     * Get the text content from a Gzipped (v2) or Zstd (v3) .sdl file.
     */
    fun decryptText(sdlFile: File): String {
        return sdlFile.inputStream().use { fis ->
            val magic = ByteArray(4)
            fis.read(magic)
            
            if (String(magic, 0, 3, Charsets.US_ASCII) != MAGIC) {
                throw IllegalArgumentException("Invalid SDL file magic")
            }

            when (magic[3]) {
                VERSION_ZSTD -> {
                    // Ultra-fast Zstandard decompression
                    try {
                        ZstdInputStream(fis).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } catch (e: Throwable) {
                        // Handle JNI loading issues (common on some Android versions/archs)
                        throw RuntimeException("Zstd decompression failed on this device. Please use Gzip format instead. Error: ${e.message}")
                    }
                }
                VERSION_GZIP -> {
                    // Legacy fallback for Gzip
                    GZIPInputStream(fis).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                else -> throw IllegalArgumentException("Unsupported SDL file version: ${magic[3]}")
            }
        }
    }

    /**
     * Check if a file is a valid SDL v2 or v3 file.
     */
    fun isValidSdlFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            val magic = String(header, 0, 3, Charsets.US_ASCII)
            val version = header[3]
            magic == MAGIC && (version == VERSION_GZIP || version == VERSION_ZSTD)
        } catch (_: Exception) {
            false
        }
    }
}
