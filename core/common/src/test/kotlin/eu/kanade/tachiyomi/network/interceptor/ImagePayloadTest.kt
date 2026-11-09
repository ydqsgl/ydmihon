package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Checks the AES-CBC unwrapping used for the encrypted covers.
 *
 * The ciphertext below was produced with the site's own key and IV from a 16-byte payload, so a
 * regression in the key, the IV or the padding mode fails here rather than as a blank thumbnail on
 * the device.
 */
class ImagePayloadTest {

    /** AES-128-CBC(`GIF89a` + filler) under the constants in the site's `lazyload.js`. */
    private val encrypted = hex("3156b097dbc3a400fea61470d0bab5d3cad6046f49c2dadebe3758efcec0e4a2")

    @Test
    fun `decrypts an encrypted cover back into an image`() {
        val plain = requireNotNull(ImagePayload.decrypt(encrypted))

        String(plain.copyOf(6), Charsets.US_ASCII) shouldBe "GIF89a"
        ImagePayload.mimeType(plain) shouldBe "image/gif"
    }

    @Test
    fun `leaves an already decoded image untouched`() {
        val plain = requireNotNull(ImagePayload.decrypt(encrypted))

        // A second pass must not treat a real image as ciphertext.
        ImagePayload.decrypt(plain) shouldBe null
    }

    @Test
    fun `rejects a payload that is not a whole number of blocks`() {
        ImagePayload.decrypt(encrypted.copyOf(encrypted.size - 1)) shouldBe null
    }

    @Test
    fun `encrypted payload is not mistaken for an image`() {
        ImagePayload.mimeType(encrypted) shouldBe null
    }

    @Test
    fun `sniffs the formats these CDNs actually serve`() {
        ImagePayload.mimeType(hex("ffd8ffe000104a464946")) shouldBe "image/jpeg"
        ImagePayload.mimeType(hex("89504e470d0a1a0a")) shouldBe "image/png"
        ImagePayload.mimeType(hex("474946383961")) shouldBe "image/gif"
        ImagePayload.mimeType(hex("5249464610000000574542505650")) shouldBe "image/webp"
        ImagePayload.mimeType("not an image".toByteArray()) shouldBe null
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
