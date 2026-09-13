package com.redurbabat.feedback.files

import com.redurbabat.feedback.protocol.ProtocolConstants
import com.redurbabat.feedback.security.CryptoUtils
import java.io.Closeable
import java.io.InputStream
import java.security.MessageDigest

/** One `files.download.chunk` payload (protocol/PROTOCOL.md section 8.3.6). */
data class FileChunk(
    val sequence: Long,
    val dataBase64: String,
    val last: Boolean,
    val rawBytes: Int,
)

/**
 * Turns a stream into protocol chunks without ever holding the whole file.
 *
 * At most two chunk buffers exist at a time: the one being handed out and the one read ahead to
 * learn whether the stream has ended. Memory use is therefore bounded by the chunk size regardless
 * of how large the file is - which is the guarantee section 8.3.6 asks for.
 *
 * Free of `android.*`, so the chunking, the `last` flag and the digest are unit tested against real
 * byte streams rather than trusted.
 */
class FileChunkReader(
    private val input: InputStream,
    private val chunkSize: Int = ProtocolConstants.FILE_CHUNK_BYTES,
) : Closeable {

    init {
        require(chunkSize > 0) { "chunk size must be positive" }
    }

    private val digest = MessageDigest.getInstance("SHA-256")
    private var nextSequence = 0L
    private var pending: ByteArray? = null
    private var primed = false
    private var exhausted = false
    private var emittedLast = false

    var totalBytes: Long = 0L
        private set

    /**
     * The next chunk, or null once the last one was handed out.
     *
     * An empty file still yields exactly one chunk with `last = true`, so the receiving side sees a
     * well formed transfer rather than an immediate completion with no data.
     */
    fun next(): FileChunk? {
        if (emittedLast) {
            return null
        }
        if (!primed) {
            pending = readFully()
            primed = true
        }

        val current = pending ?: ByteArray(0)
        val lookahead = if (exhausted) null else readFully()
        pending = lookahead

        val last = lookahead == null || lookahead.isEmpty()
        if (last) {
            emittedLast = true
        }

        digest.update(current)
        totalBytes += current.size
        val sequence = nextSequence
        nextSequence += 1L

        return FileChunk(
            sequence = sequence,
            dataBase64 = CryptoUtils.Base64.encode(current),
            last = last,
            rawBytes = current.size,
        )
    }

    /** Lowercase hex SHA-256 over everything handed out so far. Call after the last chunk. */
    fun sha256Hex(): String = CryptoUtils.toHex(digest.digest())

    override fun close() {
        input.close()
    }

    /**
     * Reads exactly [chunkSize] bytes unless the stream ends first. A single `read` may return
     * fewer bytes than asked for even when more are available, so a short read must never be
     * mistaken for the end of the file.
     */
    private fun readFully(): ByteArray? {
        if (exhausted) {
            return null
        }
        val buffer = ByteArray(chunkSize)
        var filled = 0
        while (filled < chunkSize) {
            val read = input.read(buffer, filled, chunkSize - filled)
            if (read < 0) {
                exhausted = true
                break
            }
            filled += read
        }
        if (filled == 0) {
            exhausted = true
            return ByteArray(0)
        }
        return if (filled == chunkSize) buffer else buffer.copyOf(filled)
    }
}
