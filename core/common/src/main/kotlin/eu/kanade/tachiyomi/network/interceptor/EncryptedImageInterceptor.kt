package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts the cover art that some sources store as ciphertext on their CDN.
 *
 * 91porna does not serve thumbnails as images: the CDN answers `200` with
 * `Content-Type: binary/octet-stream` and an AES-CBC ciphertext, and the page's own
 * `lazyload.js` decrypts it before handing the bytes to `<img>`. Without the same step every
 * cover on that source renders as a broken placeholder.
 *
 * The key and IV are fixed constants inside the site's `lazyload.js`, so they are reproduced
 * here rather than negotiated. Decryption is attempted only for requests whose path looks like
 * an image and only when the payload is not already a recognisable image, which keeps ordinary
 * traffic — and any site that starts serving plain images again — untouched.
 */
class EncryptedImageInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if (!response.isSuccessful) return response
        if (!looksLikeImageRequest(chain.request().url.encodedPath)) return response

        val body = response.body ?: return response
        // A real image, or a CDN that stopped encrypting: nothing to do.
        if (body.contentType()?.type == "image") return response

        val payload = try {
            body.bytes()
        } catch (_: IOException) {
            return response
        }

        val decrypted = ImagePayload.decrypt(payload)

        return response.newBuilder()
            .body(
                (decrypted ?: payload).toResponseBody(
                    ImagePayload.mimeType(decrypted ?: payload)?.toMediaTypeOrNull()
                        ?: body.contentType(),
                ),
            )
            .build()
    }

    private fun looksLikeImageRequest(path: String): Boolean {
        val lower = path.lowercase()
        return IMAGE_EXTENSIONS.any(lower::endsWith)
    }

    private companion object {
        val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp")
    }
}

/** AES-CBC payload handling shared by the interceptor and its tests. */
internal object ImagePayload {

    /** `CryptoJS.enc.Utf8.parse(...)` values from 91porna's `lazyload.js`. */
    private val KEY = "f5d965df75336270".toByteArray(Charsets.UTF_8)
    private val IV = "97b60394abc2fbe1".toByteArray(Charsets.UTF_8)

    private const val BLOCK_SIZE = 16

    /** Returns the plaintext, or null when [payload] is not one of these encrypted images. */
    fun decrypt(payload: ByteArray): ByteArray? {
        if (payload.isEmpty()) return null
        if (payload.size % BLOCK_SIZE != 0) return null
        if (mimeType(payload) != null) return null

        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
            cipher.doFinal(payload)
        }.getOrNull()?.takeIf { mimeType(it) != null }
    }

    /** Sniffs the format from the magic bytes; null when the payload is not a known image. */
    fun mimeType(bytes: ByteArray): String? = when {
        bytes.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> "image/png"
        bytes.startsWith(0x47, 0x49, 0x46, 0x38) -> "image/gif"
        bytes.startsWith(0x42, 0x4D) -> "image/bmp"
        bytes.startsWith(0x52, 0x49, 0x46, 0x46) && bytes.asciiAt(8, 4) == "WEBP" -> "image/webp"
        bytes.asciiAt(4, 4) == "ftyp" -> "image/avif"
        else -> null
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it].toByte() }
    }

    private fun ByteArray.asciiAt(offset: Int, length: Int): String? {
        if (size < offset + length) return null
        return String(this, offset, length, Charsets.US_ASCII)
    }
}
