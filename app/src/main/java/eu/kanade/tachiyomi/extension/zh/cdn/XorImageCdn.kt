package eu.kanade.tachiyomi.extension.zh.cdn

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * 天天热瓜网 serves every image from `webimg.rlkfus.cn` as raw ciphertext: the first 100 bytes
 * are XOR-ed with a fixed 13-byte key, and the response is labelled `application/octet-stream`.
 *
 * The site decrypts in the browser instead of linking the file directly: its `imgDecypt.js`
 * fetches the bytes, XORs the first 100 bytes with `"2019ysapp7527"`, and turns the result into a
 * blob URL. A plain HTTP client therefore gets an undecodable blob, which is why covers came out
 * blank even though the URLs were correct.
 *
 * This interceptor decrypts in place, so the rest of the app (Coil, the page loader, downloads)
 * sees a normal image response and needs no knowledge of the scheme.
 */
object XorImageCdn {

    /** Host that serves ciphertext; every path on it is encrypted. */
    private const val CDN_HOST = "webimg.rlkfus.cn"

    /** `2019ysapp7527`, the XOR key from `imgDecypt.js`. */
    private val XOR_KEY = "2019ysapp7527".toByteArray(Charsets.UTF_8)

    /** Number of leading bytes the browser XORs. */
    private const val XOR_LENGTH = 100

    /** True when [url] points at the ciphertext CDN. Exposed for tests. */
    fun isEncryptedUrl(url: String): Boolean {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return false
        return parsed.host.equals(CDN_HOST, ignoreCase = true)
    }

    /** Wraps [base] so every CDN image comes back decrypted. */
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
     * XORs the first 100 bytes with the site key, returning null when the payload is not the
     * expected ciphertext (too short, or the result is not a recognised image).
     */
    fun decrypt(encrypted: ByteArray): ByteArray? {
        if (encrypted.isEmpty()) return null
        val out = encrypted.copyOf()
        val limit = minOf(XOR_LENGTH, out.size)
        for (i in 0 until limit) {
            out[i] = (out[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        return if (sniffMime(out) == null) null else out
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
            else -> null
        }
    }
}
