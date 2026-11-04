package eu.kanade.tachiyomi.extension.zh.cdn

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The 51吃瓜 / 每日大赛 family serves every image from `pic.gyezbp.cn` as **AES-128-CBC ciphertext**,
 * always labelled `Content-Type: binary/octet-stream`.
 *
 * The site decrypts in the browser instead of linking the file directly: its `loadImage` /
 * `loadBackgroundImage` helpers fetch the bytes as an arraybuffer, run `decryptImage()`, and turn
 * the result into a `data:` URI. A plain HTTP client therefore gets an undecodable blob, which is
 * why covers and article images came out blank even though the URLs were correct.
 *
 * The key and IV are constants in `image.<date>.min.js`, written as underscore-separated decimal
 * character codes (`'102_53_100_57_...'`). They are the same for both sites.
 *
 * This interceptor decrypts in place, so the rest of the app (Coil, the page loader, downloads)
 * sees a normal image response and needs no knowledge of the scheme.
 */
object EncryptedImageCdn {

    /** Host that serves ciphertext, and the paths under it that are actually encrypted. */
    private const val CDN_HOST = "pic.gyezbp.cn"

    private val CIPHER_PATHS = listOf("/xiao/", "/upload/upload/", "/upload_01/", "/uploads/")

    /** `f5d965df75336270`, from `'102_53_100_57_54_53_100_102_55_53_51_51_54_50_55_48'`. */
    private val KEY = "f5d965df75336270".toByteArray(Charsets.US_ASCII)

    /** `97b60394abc2fbe1`, from `'57_55_98_54_48_51_57_52_97_98_99_50_102_98_101_49'`. */
    private val IV = "97b60394abc2fbe1".toByteArray(Charsets.US_ASCII)

    /**
     * True when [url] points at a path under the CDN that is served as ciphertext.
     * Exposed so the routing (which hosts get decrypted) can be tested on its own.
     */
    fun isEncryptedUrl(url: String): Boolean {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return false
        if (!parsed.host.equals(CDN_HOST, ignoreCase = true)) return false
        return CIPHER_PATHS.any { parsed.encodedPath.contains(it, ignoreCase = true) }
    }

    /**
     * Wraps [base] so every encrypted CDN image comes back decrypted.
     */
    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .addInterceptor(DecryptInterceptor())
        .build()

    private class DecryptInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            if (!response.isSuccessful) return response

            if (!isEncryptedUrl(response.request.url.toString())) return response

            // Only ciphertext carries this type; a real image is served as image/*.
            val contentType = response.body.contentType()
            if (contentType?.type?.startsWith("image") == true) return response

            val encrypted = runCatching { response.body.bytes() }.getOrNull() ?: return response
            val decrypted = decrypt(encrypted) ?: return response

            val mime = sniffMime(decrypted) ?: "image/jpeg"
            return response.newBuilder()
                .body(decrypted.toResponseBody(mime.toMediaTypeOrNull()))
                .header("Content-Type", mime)
                .removeHeader("Content-Length")
                .build()
        }
    }

    /**
     * AES-128-CBC/PKCS5Padding decrypt, returning null when the payload is not the expected
     * ciphertext (wrong length, or padding that does not validate).
     */
    fun decrypt(encrypted: ByteArray): ByteArray? {
        if (encrypted.isEmpty() || encrypted.size % 16 != 0) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
            val plain = cipher.doFinal(encrypted)
            if (sniffMime(plain) == null) null else plain
        }.getOrNull()
    }

    /** Identifies the image format from its magic bytes. */
    private fun sniffMime(bytes: ByteArray): String? {
        fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF) ?: -1

        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            // RIFF....WEBP
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "image/webp"
            at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70 -> "image/heif"
            else -> null
        }
    }

    /** Exposed for tests: the same decode the site performs before building a data URI. */
    fun decryptBase64(base64: String): ByteArray? = runCatching {
        decrypt(Base64.getDecoder().decode(base64))
    }.getOrNull()
}
