package eu.kanade.tachiyomi.ui.reader.viewer.video

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * Checks the PNG-disguise decoder against real files captured from the live CDN.
 *
 * A regression here is invisible: playback just fails with "source error", which is exactly the
 * bug this decoder exists to fix. The fixtures are the actual bytes the CDN served, not synthetic
 * samples, so they also pin down the chunk layout the site uses.
 */
class PngWrappedHlsDataSourceTest {

    private fun load(name: String): ByteArray {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("pngwrap/$name")) {
            "missing fixture $name"
        }
        return stream.use { it.readBytes() }
    }

    @Test
    fun `a disguised playlist decodes into an m3u8`() {
        val png = load("playlist.png")

        // Sanity: the file really is a PNG, which is why a normal player rejects it.
        png.copyOfRange(0, 4) shouldBe byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        val decoded = PngWrappedHlsDataSource.decode(png)
        val text = String(decoded, Charsets.UTF_8)

        text shouldStartWith "#EXTM3U"
        text shouldContain "#EXT-X-TARGETDURATION"
        text shouldContain ".png"
    }

    @Test
    fun `a disguised segment decodes into a transport stream`() {
        val png = load("segment.png")

        val decoded = PngWrappedHlsDataSource.decode(png)

        // MPEG-TS: 188-byte packets, each starting with 0x47.
        (decoded.size > 188 * 100) shouldBe true
        decoded[0] shouldBe 0x47.toByte()
        decoded[188] shouldBe 0x47.toByte()
        decoded[376] shouldBe 0x47.toByte()
        (decoded.size % 188 == 0) shouldBe true
    }

    @Test
    fun `decoded segment is not the placeholder image`() {
        val png = load("segment.png")
        val decoded = PngWrappedHlsDataSource.decode(png)

        // The placeholder picture is ~160x96; the real segment is a few hundred kilobytes.
        decoded.size shouldBeGreaterThan 200_000
        (decoded.copyOfRange(0, 4).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))) shouldBe false
    }

    @Test
    fun `an ordinary m3u8 passes through untouched`() {
        val plain = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()

        PngWrappedHlsDataSource.decode(plain) shouldBe plain
    }

    @Test
    fun `a real hls playlist with aes keys is not mangled`() {
        // 51吃瓜 / 每日大赛 serve playlists like this: plain text, AES-128 key, .ts segments.
        // The wrapper must not touch it — buffering or rewriting here breaks their playback.
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-KEY:METHOD=AES-128,URI="https://mts.example.com/crypt.key?auth_key=1-2-3",IV=0x6b48
            #EXT-X-TARGETDURATION:6
            #EXTINF:5.756,
            https://mts.example.com/seg0.ts?auth_key=1-2-3
            #EXTINF:4.796,
            https://mts.example.com/seg1.ts?auth_key=1-2-3
        """.trimIndent().toByteArray()

        val decoded = PngWrappedHlsDataSource.decode(playlist)

        decoded shouldBe playlist
        String(decoded, Charsets.UTF_8) shouldStartWith "#EXTM3U"
    }

    @Test
    fun `a raw transport stream passes through untouched`() {
        // Segments from these sources are plain MPEG-TS, not PNG-wrapped.
        val ts = ByteArray(188 * 4).apply { this[0] = 0x47; this[188] = 0x47 }
        PngWrappedHlsDataSource.decode(ts) shouldBe ts
    }

    @Test
    fun `an ordinary png without the chunk passes through untouched`() {
        // A minimal PNG: signature + IHDR + IEND, no custom chunk.
        val plainPng = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
            0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F.toByte(), 0x15, 0xC4.toByte(), 0x89.toByte(),
            0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )

        PngWrappedHlsDataSource.decode(plainPng) shouldBe plainPng
    }

    @Test
    fun `a truncated png does not throw`() {
        val truncated = load("playlist.png").copyOfRange(0, 40)

        // Must not throw; returning the input unchanged lets the player report the real problem.
        PngWrappedHlsDataSource.decode(truncated).size shouldBe 40
    }

    @Test
    fun `an empty body does not throw`() {
        PngWrappedHlsDataSource.decode(ByteArray(0)).size shouldBe 0
    }

    @Test
    fun `a png whose chunk payload is not HLS is left alone`() {
        // Signature + a "roUd" chunk holding bytes that are neither zlib nor TS.
        val garbage = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val chunk = buildPngWithChunk(garbage)

        PngWrappedHlsDataSource.decode(chunk) shouldBe chunk
    }

    /** Builds a PNG-shaped file whose `roUd` chunk carries [payload]. */
    private fun buildPngWithChunk(payload: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).forEach { out.add(it.toByte()) }

        fun addInt(value: Int) {
            out.add((value ushr 24).toByte())
            out.add((value ushr 16).toByte())
            out.add((value ushr 8).toByte())
            out.add(value.toByte())
        }

        addInt(payload.size)
        out.addAll("roUd".toByteArray().toList())
        out.addAll(payload.toList())
        addInt(0) // CRC is not checked by the decoder.
        return out.toByteArray()
    }
}
