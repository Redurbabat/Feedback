package com.redurbabat.feedback.files

import com.redurbabat.feedback.protocol.ProtocolConstants
import com.redurbabat.feedback.security.CryptoUtils
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileChunkReaderTest {

    @Test
    fun `an empty file still produces one final chunk`() {
        val reader = FileChunkReader(ByteArrayInputStream(ByteArray(0)), chunkSize = 8)
        val chunk = reader.next()
        assertEquals(0L, chunk?.sequence)
        assertEquals(0, chunk?.rawBytes)
        assertEquals("", chunk?.dataBase64)
        assertTrue(chunk?.last == true)
        assertNull("nothing follows the last chunk", reader.next())
        assertEquals(0L, reader.totalBytes)
        assertEquals(sha256Hex(ByteArray(0)), reader.sha256Hex())
    }

    @Test
    fun `a file shorter than one chunk is a single chunk`() {
        val content = "kurz".toByteArray(Charsets.UTF_8)
        val chunks = drain(content, chunkSize = 32)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].last)
        assertArrayEquals(content, rebuild(chunks))
    }

    @Test
    fun `a file exactly one chunk long does not produce an empty trailer`() {
        val content = ByteArray(16) { it.toByte() }
        val chunks = drain(content, chunkSize = 16)
        assertEquals("an exact multiple must not emit a trailing empty chunk", 1, chunks.size)
        assertTrue(chunks[0].last)
        assertEquals(16, chunks[0].rawBytes)
        assertArrayEquals(content, rebuild(chunks))
    }

    @Test
    fun `an exact multiple of the chunk size ends on the last full chunk`() {
        val content = ByteArray(48) { (it * 7).toByte() }
        val chunks = drain(content, chunkSize = 16)
        assertEquals(3, chunks.size)
        assertTrue(chunks.last().last)
        assertFalse(chunks[0].last)
        assertFalse(chunks[1].last)
        assertArrayEquals(content, rebuild(chunks))
    }

    @Test
    fun `sequences are gapless and accepted by the receiving side`() {
        val content = ByteArray(70) { it.toByte() }
        val chunks = drain(content, chunkSize = 16)
        val sequence = FileChunkSequence()
        for (chunk in chunks) {
            assertTrue(
                "chunk ${chunk.sequence} must be accepted",
                sequence.accept(chunk.sequence, chunk.last),
            )
        }
        assertTrue(sequence.isFinished)
        assertEquals(chunks.indices.map(Int::toLong), chunks.map(FileChunk::sequence))
    }

    @Test
    fun `the digest covers the whole content`() {
        val content = ByteArray(1_000) { (it % 251).toByte() }
        val reader = FileChunkReader(ByteArrayInputStream(content), chunkSize = 64)
        while (reader.next() != null) {
            // drain
        }
        assertEquals(content.size.toLong(), reader.totalBytes)
        assertEquals(sha256Hex(content), reader.sha256Hex())
    }

    @Test
    fun `a stream that returns short reads is still chunked correctly`() {
        val content = ByteArray(100) { it.toByte() }
        val reader = FileChunkReader(DribbleStream(content, maxPerRead = 3), chunkSize = 16)
        val chunks = generateSequence { reader.next() }.toList()

        assertEquals(7, chunks.size)
        for (chunk in chunks.dropLast(1)) {
            assertEquals("a short read must not end a chunk early", 16, chunk.rawBytes)
        }
        assertEquals(4, chunks.last().rawBytes)
        assertArrayEquals(content, rebuild(chunks))
        assertEquals(sha256Hex(content), reader.sha256Hex())
    }

    @Test
    fun `memory use stays bounded for a file far larger than one chunk`() {
        val content = ByteArray(ProtocolConstants.FILE_CHUNK_BYTES * 4 + 11) { (it % 97).toByte() }
        val reader = FileChunkReader(ByteArrayInputStream(content))
        var count = 0
        var largest = 0
        while (true) {
            val chunk = reader.next() ?: break
            count += 1
            largest = maxOf(largest, chunk.rawBytes)
        }
        assertEquals(5, count)
        assertEquals(ProtocolConstants.FILE_CHUNK_BYTES, largest)
        assertEquals(content.size.toLong(), reader.totalBytes)
        assertEquals(
            FileTransferPolicy.chunkCount(content.size.toLong()),
            count.toLong(),
        )
    }

    @Test
    fun `close releases the underlying stream`() {
        val stream = ByteArrayInputStream(ByteArray(4))
        var closed = false
        val tracking = object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun close() {
                closed = true
            }
        }
        FileChunkReader(tracking, chunkSize = 4).use { it.next() }
        assertTrue(closed)
    }

    @Test
    fun `a non positive chunk size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FileChunkReader(ByteArrayInputStream(ByteArray(0)), chunkSize = 0)
        }
    }

    private fun drain(content: ByteArray, chunkSize: Int): List<FileChunk> {
        val reader = FileChunkReader(ByteArrayInputStream(content), chunkSize = chunkSize)
        return generateSequence { reader.next() }.toList()
    }

    private fun rebuild(chunks: List<FileChunk>): ByteArray {
        var result = ByteArray(0)
        for (chunk in chunks) {
            result += CryptoUtils.Base64.decode(chunk.dataBase64)
        }
        return result
    }

    private fun sha256Hex(content: ByteArray): String =
        CryptoUtils.toHex(MessageDigest.getInstance("SHA-256").digest(content))

    /** Returns at most [maxPerRead] bytes per call, the way a real content provider may. */
    private class DribbleStream(
        private val content: ByteArray,
        private val maxPerRead: Int,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position >= content.size) {
                return -1
            }
            return content[position++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= content.size) {
                return -1
            }
            val count = minOf(maxPerRead, len, content.size - position)
            System.arraycopy(content, position, b, off, count)
            position += count
            return count
        }

        @Throws(IOException::class)
        override fun close() = Unit
    }
}
