package eu.kanade.tachiyomi.extension.zh.cdn

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * The image CDN returns AES ciphertext; these samples are real responses captured from
 * `pic.gyezbp.cn`, so a change in key or mode shows up here instead of as blank covers.
 */
class EncryptedImageCdnTest {

    private fun sample(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("cdn/$name")) {
            "missing fixture $name"
        }.use { it.readBytes() }

    @Test
    fun `decrypts a 51cg cover into a jpeg`() {
        val encrypted = sample("cover_51cg.enc")

        // Ciphertext never starts with an image magic, which is why the CDN response is unusable as-is.
        (encrypted[0].toInt() and 0xFF) shouldBe 0x4F

        val decrypted = EncryptedImageCdn.decrypt(encrypted).shouldNotBeNull()

        // JPEG SOI + marker
        (decrypted[0].toInt() and 0xFF) shouldBe 0xFF
        (decrypted[1].toInt() and 0xFF) shouldBe 0xD8
        decrypted.size shouldBeGreaterThan 1000
    }

    @Test
    fun `decrypts a mrds cover into an image`() {
        val decrypted = EncryptedImageCdn.decrypt(sample("cover_mrds.enc")).shouldNotBeNull()

        (decrypted[0].toInt() and 0xFF) shouldBe 0xFF
        (decrypted[1].toInt() and 0xFF) shouldBe 0xD8
    }

    @Test
    fun `decrypts from base64 like the site's decryptImage helper`() {
        val base64 = Base64.getEncoder().encodeToString(sample("cover_51cg.enc"))

        val decrypted = EncryptedImageCdn.decryptBase64(base64).shouldNotBeNull()

        (decrypted[0].toInt() and 0xFF) shouldBe 0xFF
        (decrypted[1].toInt() and 0xFF) shouldBe 0xD8
    }

    @Test
    fun `only encrypted cdn paths are decrypted`() {
        // Covers and article images.
        EncryptedImageCdn.isEncryptedUrl(
            "https://pic.gyezbp.cn/upload_01/xiao/20261101/2026110118214062287.jpeg",
        ) shouldBe true
        EncryptedImageCdn.isEncryptedUrl("https://pic.gyezbp.cn/uploads/2026/x.jpg") shouldBe true

        // Other hosts and other paths must be left alone, even when they look like images.
        EncryptedImageCdn.isEncryptedUrl("https://hls.fgxvqf.cn/videos5/a/b.m3u8") shouldBe false
        EncryptedImageCdn.isEncryptedUrl("https://51cg1.com/usr/themes/Mirages/images/x.jpg") shouldBe false
        EncryptedImageCdn.isEncryptedUrl("not a url") shouldBe false
    }

    @Test
    fun `rejects payloads that are not ciphertext`() {
        // A plain JPEG must not be "decrypted" into garbage.
        EncryptedImageCdn.decrypt(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())).shouldBeNull()

        // Right length, wrong content: padding or magic has to fail.
        EncryptedImageCdn.decrypt(ByteArray(64) { 0x41 }).shouldBeNull()

        EncryptedImageCdn.decrypt(ByteArray(0)).shouldBeNull()
    }
}
