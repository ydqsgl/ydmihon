package eu.kanade.tachiyomi.ui.reader.viewer.video

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
 * Unwraps the PNG disguise used by some CDNs for HLS content.
 *
 * One of the sources (肉視頻) serves its playlist and segments as files named `index.png` /
 * `C-000.png` that are *real* PNG images: a small placeholder picture, with the actual payload
 * stored in a non-standard `roUd` chunk. The site's own player parses that chunk in JavaScript, but
 * a plain HLS player only sees `Content-Type: image/png` and refuses to play.
 *
 * The chunk payload comes in two shapes:
 *
 * - **Playlist**: zlib-deflated `#EXTM3U` text, prefixed with one marker byte.
 * - **Segment**: an MPEG-TS stream, also prefixed with one byte, so `0x47` sync bytes land at
 *   offsets 1, 189, 377, ...
 *
 * This data source detects the chunk, decodes it, and hands the result upstream. Everything else —
 * a normal PNG, a normal m3u8, any unrelated URL — is passed through byte for byte, so wrapping a
 * whole playback session in this source is safe.
 */
internal class PngWrappedHlsDataSource(
    private val upstream: DataSource,
) : DataSource {

    private var stream: InputStream? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val opened = upstream.open(dataSpec)
        if (opened < 0) return opened

        // Drain the wrapped source: playlists and segments are small enough to buffer, and the
        // payload cannot be decoded until the whole PNG (including its trailing chunk) is present.
        val raw = ByteArrayOutputStream().also { sink ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = upstream.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                sink.write(buffer, 0, read)
            }
        }.toByteArray()

        val decoded = decode(raw)
        stream = ByteArrayInputStream(decoded)
        return decoded.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val input = stream ?: return C.RESULT_END_OF_INPUT
        val read = input.read(buffer, offset, length)
        return if (read == -1) C.RESULT_END_OF_INPUT else read
    }

    override fun getUri(): android.net.Uri? = upstream.uri

    override fun close() {
        stream = null
        upstream.close()
    }

    companion object {
        /** The custom PNG chunk the CDN uses to carry the real payload. */
        private val CHUNK_ID = byteArrayOf('r'.code.toByte(), 'o'.code.toByte(), 'U'.code.toByte(), 'd'.code.toByte())

        private const val PNG_SIGNATURE_LENGTH = 8
        private const val MAX_DECODED_SIZE = 64 * 1024 * 1024

        /**
         * Returns the real payload when [bytes] is a disguised PNG, or [bytes] untouched otherwise.
         */
        fun decode(bytes: ByteArray): ByteArray {
            if (!isPng(bytes)) return bytes

            val chunk = findChunk(bytes) ?: return bytes
            val decoded = decodeChunk(chunk) ?: return bytes
            return decoded
        }

        private fun isPng(bytes: ByteArray): Boolean {
            if (bytes.size < PNG_SIGNATURE_LENGTH + 8) return false
            val signature = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            )
            return signature.indices.all { bytes[it] == signature[it] }
        }

        /**
         * Walks the PNG chunk list looking for [CHUNK_ID].
         *
         * Chunk layout: 4-byte big-endian length, 4-byte type, payload, 4-byte CRC. The walk stops
         * at IEND; a malformed length also stops it, so a corrupt image cannot loop forever.
         */
        private fun findChunk(bytes: ByteArray): ByteArray? {
            var offset = PNG_SIGNATURE_LENGTH
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

            // Playlist: zlib stream, often one byte in.
            for (skip in 0..2) {
                if (chunk.size <= skip) continue
                val inflated = inflate(chunk, skip) ?: continue
                if (inflated.startsWithM3u8()) return inflated
            }

            // Segment: drop the marker byte and confirm the TS sync chain.
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
                    val out = stream.readNBytes(MAX_DECODED_SIZE)
                    out.takeIf { it.isNotEmpty() }
                }
            } catch (_: IOException) {
                null
            }
        }

        /**
         * A playlist starts with `#EXTM3U`; tolerating leading whitespace costs nothing and avoids
         * rejecting a valid stream over a stray BOM.
         */
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

        private val IEND = byteArrayOf('I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte())
    }
}

/**
 * Wraps [this] so every response is checked for the PNG disguise.
 *
 * Applied to the whole HTTP data source rather than to one URL: the playlist and each segment are
 * separate requests, and every one of them is disguised the same way.
 */
internal fun DataSource.Factory.unwrapPngDisguise(): DataSource.Factory {
    val delegate = this
    return DataSource.Factory {
        PngWrappedHlsDataSource(delegate.createDataSource())
    }
}
