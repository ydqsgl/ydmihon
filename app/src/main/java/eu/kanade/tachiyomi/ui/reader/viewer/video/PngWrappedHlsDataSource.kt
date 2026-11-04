package eu.kanade.tachiyomi.ui.reader.viewer.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.InflaterInputStream

/**
 * Unwraps the PNG disguise some CDNs use for HLS content.
 *
 * One source serves its playlist and segments as files named `index.png` / `C-000.png` that are
 * *real* PNG images: a small placeholder picture whose payload is stored in a non-standard `roUd`
 * chunk. The site's own player decodes that chunk in JavaScript, but a plain HLS player only sees
 * `Content-Type: image/png` and refuses to play.
 *
 * The chunk payload comes in two shapes:
 *
 * - **Playlist**: zlib-deflated `#EXTM3U` text, prefixed with one marker byte.
 * - **Segment**: an MPEG-TS stream, also prefixed with one byte, so `0x47` sync bytes land at
 *   offsets 1, 189, 377, ...
 *
 * Only responses that actually start with the PNG signature are buffered and decoded. Everything
 * else — a normal m3u8, a `.ts` segment, any ordinary image — is streamed straight through, so this
 * costs nothing for the other sources in this app.
 */
internal class PngWrappedHlsDataSource(
    private val upstream: DataSource,
) : DataSource {

    private var stream: InputStream? = null

    /** Set when the response was passed through untouched and should keep streaming. */
    private var passthrough = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val opened = upstream.open(dataSpec)
        if (opened < 0) return opened

        // Peek at the first bytes before deciding: buffering every response would defeat streaming
        // for ordinary HLS, where segments are hundreds of kilobytes and must not be fully read
        // before playback can start.
        val head = ByteArray(PNG_SIGNATURE.size)
        val headRead = readUpTo(head)

        if (headRead < PNG_SIGNATURE.size || !head.startsWithPngSignature()) {
            passthrough = true
            stream = SequenceInputStream(head, headRead)
            // The upstream length is still valid; the head bytes are simply replayed first.
            return if (opened == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else opened
        }

        passthrough = false
        val rest = ByteArrayOutputStream().apply { write(head, 0, headRead) }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = upstream.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            rest.write(buffer, 0, read)
        }

        // A PNG is either the disguise (decode it) or an ordinary image (leave it alone).
        val decoded = decodePng(rest.toByteArray())
        stream = ByteArrayInputStream(decoded)
        return decoded.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val input = stream ?: return C.RESULT_END_OF_INPUT
        val read = input.read(buffer, offset, length)
        return if (read == -1) C.RESULT_END_OF_INPUT else read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        stream = null
        passthrough = false
        upstream.close()
    }

    /** Reads until [target] is full or the source ends; returns how many bytes were read. */
    private fun readUpTo(target: ByteArray): Int {
        var total = 0
        while (total < target.size) {
            val read = upstream.read(target, total, target.size - total)
            if (read == C.RESULT_END_OF_INPUT) break
            total += read
        }
        return total
    }

    /** Replays already-consumed [head] bytes, then the rest of the upstream. */
    private inner class SequenceInputStream(
        private val head: ByteArray,
        private val headLength: Int,
    ) : InputStream() {
        private var headPos = 0

        override fun read(): Int {
            if (headPos < headLength) return head[headPos++].toInt() and 0xFF
            return upstream.read(singleByte, 0, 1).let { if (it == C.RESULT_END_OF_INPUT) -1 else singleByte[0].toInt() and 0xFF }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (headPos < headLength) {
                val n = minOf(len, headLength - headPos)
                System.arraycopy(head, headPos, b, off, n)
                headPos += n
                return n
            }
            val read = upstream.read(b, off, len)
            return if (read == C.RESULT_END_OF_INPUT) -1 else read
        }

        private val singleByte = ByteArray(1)
    }

    companion object {
        private val CHUNK_ID = byteArrayOf('r'.code.toByte(), 'o'.code.toByte(), 'U'.code.toByte(), 'd'.code.toByte())
        private val IEND = byteArrayOf('I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte())
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )

        private const val MAX_DECODED_SIZE = 64 * 1024 * 1024

        /**
         * Returns the real payload when [bytes] is a disguised PNG, or [bytes] untouched otherwise.
         */
        fun decode(bytes: ByteArray): ByteArray = decodePng(bytes)

        /**
         * Decodes a PNG's hidden chunk, or returns the input when there is nothing to unwrap.
         *
         * A plain image (no `roUd` chunk, or a chunk that is not HLS) is returned as-is, so showing
         * an actual picture still works.
         */
        private fun decodePng(bytes: ByteArray): ByteArray {
            if (!bytes.startsWithPngSignature()) return bytes
            val chunk = findChunk(bytes) ?: return bytes
            return decodeChunk(chunk) ?: bytes
        }

        private fun ByteArray.startsWithPngSignature(): Boolean {
            if (size < PNG_SIGNATURE.size) return false
            return PNG_SIGNATURE.indices.all { this[it] == PNG_SIGNATURE[it] }
        }

        /**
         * Walks the PNG chunk list looking for the hidden chunk.
         *
         * Chunk layout: 4-byte big-endian length, 4-byte type, payload, 4-byte CRC. The walk stops
         * at IEND or on a malformed length, so a corrupt image cannot loop forever.
         */
        private fun findChunk(bytes: ByteArray): ByteArray? {
            var offset = PNG_SIGNATURE.size
            while (offset + 8 <= bytes.size) {
                val length = readInt(bytes, offset)
                if (length < 0 || offset + 12 + length > bytes.size) return null

                val type = bytes.copyOfRange(offset + 4, offset + 8)
                if (type.contentEquals(CHUNK_ID)) {
                    return bytes.copyOfRange(offset + 8, offset + 8 + length)
                }
                if (type.contentEquals(IEND)) return null

                offset += 12 + length
            }
            return null
        }

        /**
         * Turns a chunk payload into the stream a player expects.
         *
         * Both shapes carry a single leading marker byte; the segment case is recognised by the
         * MPEG-TS sync byte that follows it.
         */
        private fun decodeChunk(chunk: ByteArray): ByteArray? {
            if (chunk.isEmpty()) return null

            for (skip in 0..2) {
                if (chunk.size <= skip) continue
                val inflated = inflate(chunk, skip) ?: continue
                if (inflated.startsWithM3u8()) return inflated
            }

            if (chunk.size > 1) {
                val candidate = chunk.copyOfRange(1, chunk.size)
                if (candidate.isMpegTs()) return candidate
            }
            if (chunk.isMpegTs()) return chunk

            return null
        }

        private fun inflate(bytes: ByteArray, skip: Int): ByteArray? {
            return try {
                InflaterInputStream(ByteArrayInputStream(bytes, skip, bytes.size - skip)).use { stream ->
                    stream.readNBytes(MAX_DECODED_SIZE).takeIf { it.isNotEmpty() }
                }
            } catch (_: IOException) {
                null
            }
        }

        private fun ByteArray.startsWithM3u8(): Boolean {
            val text = String(this, 0, minOf(size, 16), Charsets.US_ASCII).trimStart('\uFEFF', ' ', '\n', '\r')
            return text.startsWith("#EXTM3U")
        }

        /** MPEG-TS packets are 188 bytes and begin with 0x47; two in a row is proof enough. */
        private fun ByteArray.isMpegTs(): Boolean {
            if (size < 188 * 2) return false
            return this[0] == 0x47.toByte() && this[188] == 0x47.toByte()
        }

        private fun readInt(bytes: ByteArray, offset: Int): Int {
            return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        }
    }
}

/**
 * Wraps [this] so disguised PNG responses are decoded before the player sees them.
 *
 * The wrapper only engages for sources whose playlist URL does not end in `.m3u8` — that is the
 * case where a CDN hides HLS behind another extension (肉視頻 serves it as `index.png`). Every
 * other source streams through the plain data source, because sniffing each response would force
 * the playlist and all of its segments through an extra buffering layer for no benefit, and an
 * ordinary `.m3u8` must not be touched.
 *
 * Once engaged, the wrapper covers the whole session: the playlist *and* each segment are separate
 * requests, and both are disguised.
 */
internal fun DataSource.Factory.unwrapPngDisguise(videoUrl: String): DataSource.Factory {
    if (videoUrl.looksLikePlainHls()) return this

    val delegate = this
    return DataSource.Factory {
        PngWrappedHlsDataSource(delegate.createDataSource())
    }
}

/**
 * True when the URL already announces HLS by its extension.
 *
 * A query string is ignored: `.../index.m3u8?token=...` is still an ordinary playlist.
 */
private fun String.looksLikePlainHls(): Boolean {
    val path = substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".mpd")
}
