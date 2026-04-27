package com.sdl.grantha.data.crypto

import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Manages storage and retrieval of .sdl grantha files.
 * Uses modern Gzip compression (v2).
 *
 * SDL File Format (v2 - Gzip):
 *   [4 bytes] Magic: "SDL\x02"
 *   [remaining] Gzipped UTF-8 text content
 */
class SdlCryptoManager {

    companion object {
        private const val MAGIC = "SDL"
        private const val VERSION_GZIP: Byte = 0x02
    }

    /**
     * Get basic metadata from an .sdl file.
     */
    fun decryptMetadata(sdlFile: File): JSONObject {
        // v2 doesn't pack metadata in the file (it's in the DB)
        return JSONObject().put("name", sdlFile.nameWithoutExtension)
    }

    /**
     * Get the text content from a Gzipped .sdl file (v2).
     */
    fun decryptText(sdlFile: File): String {
        return sdlFile.inputStream().use { fis ->
            val magic = ByteArray(4)
            fis.read(magic)
            
            if (String(magic, 0, 3, Charsets.US_ASCII) != MAGIC || magic[3] != VERSION_GZIP) {
                throw IllegalArgumentException("Invalid or unsupported SDL file version")
            }

            // Decompress Gzip content
            GZIPInputStream(fis).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    /**
     * Helper to save a text as a Gzipped .sdl file (v2).
     */
    fun saveAsGzippedSdl(text: String, outputFile: File) {
        outputFile.outputStream().use { fos ->
            // Write Header: SDL + Version 0x02
            fos.write(MAGIC.toByteArray(Charsets.US_ASCII))
            fos.write(VERSION_GZIP.toInt())
            
            // Write Gzipped content
            GZIPOutputStream(fos).use { gzos ->
                gzos.write(text.toByteArray(Charsets.UTF_8))
            }
        }
    }

    /**
     * Check if a file is a valid SDL v2 file.
     */
    fun isValidSdlFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            val magic = String(header, 0, 3, Charsets.US_ASCII)
            val version = header[3]
            magic == MAGIC && version == VERSION_GZIP
        } catch (e: Exception) {
            false
        }
    }
}
