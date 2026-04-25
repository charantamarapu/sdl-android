package com.sdl.grantha.data.crypto

import com.sdl.grantha.BuildConfig
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages encryption/decryption of .sdl grantha files.
 *
 * SDL File Format:
 *   [4 bytes] Magic: "SDL\x01"
 *   [12 bytes] AES-GCM IV
 *   [4 bytes] Metadata length (big-endian uint32)
 *   [N bytes] Encrypted metadata JSON (with 16-byte GCM tag appended)
 *   [remaining] Encrypted text content (with 16-byte GCM tag appended)
 */
class SdlCryptoManager {

    companion object {
        private const val MAGIC = "SDL"
        private const val MAGIC_VERSION: Byte = 0x01
        private const val IV_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val HEADER_SIZE = 4 + IV_LENGTH + 4 // magic(4) + iv(12) + metaLen(4) = 20

        private val KEY: ByteArray by lazy {
            hexToBytes(BuildConfig.SDL_KEY)
        }

        private fun hexToBytes(hex: String): ByteArray {
            val len = hex.length
            val data = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                        Character.digit(hex[i + 1], 16)).toByte()
            }
            return data
        }
    }

    /**
     * Decrypt metadata JSON from an .sdl file.
     * Returns a JSONObject with fields: name, tags, source_url, books
     */
    fun decryptMetadata(sdlFile: File): JSONObject {
        val data = sdlFile.readBytes()
        validateMagic(data)

        val iv = data.copyOfRange(4, 4 + IV_LENGTH)
        val metaLen = ByteBuffer.wrap(data, 4 + IV_LENGTH, 4).int

        val encryptedMeta = data.copyOfRange(HEADER_SIZE, HEADER_SIZE + metaLen)
        val decryptedMeta = decryptBlock(iv, encryptedMeta, "sdl-meta".toByteArray())

        return JSONObject(String(decryptedMeta, Charsets.UTF_8))
    }

    /**
     * Decrypt the text content from an .sdl file.
     * Returns the full OCR text with page markers {[(N)]}.
     */
    fun decryptText(sdlFile: File): String {
        val data = sdlFile.readBytes()
        validateMagic(data)

        val iv = data.copyOfRange(4, 4 + IV_LENGTH)
        val metaLen = ByteBuffer.wrap(data, 4 + IV_LENGTH, 4).int

        val textStart = HEADER_SIZE + metaLen
        val encryptedText = data.copyOfRange(textStart, data.size)
        val decryptedText = decryptBlock(iv, encryptedText, "sdl-text".toByteArray())

        return String(decryptedText, Charsets.UTF_8)
    }

    /**
     * Validate that the file starts with the SDL magic header.
     */
    private fun validateMagic(data: ByteArray) {
        require(data.size >= HEADER_SIZE) { "File too small to be a valid SDL file" }
        val magic = String(data, 0, 3, Charsets.US_ASCII)
        require(magic == MAGIC && data[3] == MAGIC_VERSION) {
            "Invalid SDL file: wrong magic header"
        }
    }

    /**
     * Decrypt a single AES-256-GCM block.
     */
    private fun decryptBlock(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(KEY, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Check if a file is a valid SDL file (quick header check only).
     */
    fun isValidSdlFile(file: File): Boolean {
        if (!file.exists() || file.length() < HEADER_SIZE) return false
        return try {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            val magic = String(header, 0, 3, Charsets.US_ASCII)
            magic == MAGIC && header[3] == MAGIC_VERSION
        } catch (e: Exception) {
            false
        }
    }
}
