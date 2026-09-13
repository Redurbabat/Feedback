package com.redurbabat.feedback.files

import com.redurbabat.feedback.protocol.Capability
import com.redurbabat.feedback.protocol.MessageType
import com.redurbabat.feedback.protocol.ProtocolConstants
import com.redurbabat.feedback.protocol.ProtocolError
import com.redurbabat.feedback.security.CryptoUtils
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val ALL_SHARE_CAPABILITIES = setOf(
    Capability.FILES_READ,
    Capability.MEDIA_PHOTOS_READ,
    Capability.MEDIA_VIDEOS_READ,
)

class FilesRequestHandlerTest {

    private val shareId = "c2hhcmUx"
    private val fileId = "ZmlsZTAwMDAwMDAx"
    private val directoryId = "ZGlyMDAwMDAwMDE"

    // -------------------------------------------------------------- listing

    @Test
    fun `shares are reported in wire form`() {
        val source = FakeFileSource(
            shares = listOf(
                FileShare(shareId, "Documents", FileShareKind.TREE, Capability.FILES_READ, 1_757_754_720_000L),
            ),
        )
        val outcome = FilesRequestHandler(source).handleSharesRequest(ALL_SHARE_CAPABILITIES)
        val payload = replyPayload(outcome, MessageType.FILES_SHARES_RESPONSE)
        val shares = payload.getJSONArray("shares")
        assertEquals(1, shares.length())
        assertEquals(shareId, shares.getJSONObject(0).getString("shareId"))
        assertFalse("no URI may reach the wire", payload.toString().contains("content://"))
    }

    @Test
    fun `listing a share root omits directoryId and still succeeds`() {
        val entry = fileEntry()
        val source = FakeFileSource(page = FileListPage(listOf(entry), nextCursor = null))
        val outcome = FilesRequestHandler(source).handleListRequest(
            JSONObject().put("shareId", shareId),
            ALL_SHARE_CAPABILITIES,
        )
        val payload = replyPayload(outcome, MessageType.FILES_LIST_RESPONSE)
        assertEquals(shareId, payload.getString("shareId"))
        assertEquals(1, payload.getJSONArray("entries").length())
        assertFalse("no cursor on the last page", payload.has("nextCursor"))
        assertEquals(null, source.lastDirectoryId)
    }

    @Test
    fun `listing a subdirectory passes the directory id through`() {
        val source = FakeFileSource(page = FileListPage(emptyList(), nextCursor = "Y3Vyc29y"))
        val outcome = FilesRequestHandler(source).handleListRequest(
            JSONObject().put("shareId", shareId).put("directoryId", directoryId),
            ALL_SHARE_CAPABILITIES,
        )
        val payload = replyPayload(outcome, MessageType.FILES_LIST_RESPONSE)
        assertEquals(directoryId, source.lastDirectoryId)
        assertEquals("Y3Vyc29y", payload.getString("nextCursor"))
    }

    @Test
    fun `a malformed id is a protocol violation, not a lookup`() {
        val source = FakeFileSource()
        val handler = FilesRequestHandler(source)

        assertFailure(handler.handleListRequest(JSONObject(), ALL_SHARE_CAPABILITIES), ProtocolError.INVALID_MESSAGE)
        assertFailure(
            handler.handleListRequest(JSONObject().put("shareId", "content://tree/primary"), ALL_SHARE_CAPABILITIES),
            ProtocolError.INVALID_MESSAGE,
        )
        assertFailure(
            handler.handleListRequest(
                JSONObject().put("shareId", shareId).put("directoryId", "../escape"),
            ALL_SHARE_CAPABILITIES,
        ),
            ProtocolError.INVALID_MESSAGE,
        )
        assertFailure(
            handler.handleListRequest(JSONObject().put("shareId", shareId).put("limit", 0), ALL_SHARE_CAPABILITIES),
            ProtocolError.INVALID_MESSAGE,
        )
        assertFalse("nothing should have been read", source.listCalled)
    }

    @Test
    fun `an unknown share answers NOT_FOUND rather than FORBIDDEN`() {
        val handler = FilesRequestHandler(FakeFileSource(page = null))
        assertFailure(
            handler.handleListRequest(JSONObject().put("shareId", shareId), ALL_SHARE_CAPABILITIES),
            ProtocolError.NOT_FOUND,
        )
    }

    @Test
    fun `metadata returns the entry and an unknown id is not found`() {
        val entry = fileEntry()
        val handler = FilesRequestHandler(FakeFileSource(entry = entry))
        val payload = replyPayload(
            handler.handleMetadataRequest(
                JSONObject().put("shareId", shareId).put("fileId", fileId),
            ALL_SHARE_CAPABILITIES,
        ),
            MessageType.FILES_METADATA_RESPONSE,
        )
        assertEquals(entry.name, payload.getJSONObject("entry").getString("name"))

        val empty = FilesRequestHandler(FakeFileSource(entry = null))
        assertFailure(
            empty.handleMetadataRequest(
                JSONObject().put("shareId", shareId).put("fileId", fileId),
            ALL_SHARE_CAPABILITIES,
        ),
            ProtocolError.NOT_FOUND,
        )
    }

    // ------------------------------------------------------------- download

    @Test
    fun `a download streams chunks, completes and matches the digest`() {
        val content = ByteArray(200) { (it % 251).toByte() }
        val handler = handlerFor(content, size = content.size.toLong(), chunkSize = 64)

        assertEquals(FilesOutcome.Accepted, handler.handleDownloadStart(startPayload(), ALL_SHARE_CAPABILITIES))

        val received = ArrayList<ByteArray>()
        var complete: JSONObject? = null
        var guard = 0
        while (guard++ < 50) {
            val messages = handler.drainReadyMessages()
            if (messages.isEmpty()) {
                break
            }
            for (message in messages) {
                when (message.type) {
                    MessageType.FILES_DOWNLOAD_CHUNK -> {
                        received.add(CryptoUtils.Base64.decode(message.payload.getString("data")))
                        handler.handleDownloadAck(
                            JSONObject()
                                .put("transferId", TRANSFER_ID)
                                .put("sequence", message.payload.getLong("sequence")),
                        )
                    }

                    MessageType.FILES_DOWNLOAD_COMPLETE -> complete = message.payload
                    else -> Unit
                }
            }
        }

        assertArrayEquals(content, received.fold(ByteArray(0)) { acc, part -> acc + part })
        assertNotNull("the transfer must complete", complete)
        assertEquals(content.size.toLong(), complete!!.getLong("totalBytes"))
        assertEquals(sha256Hex(content), complete.getString("sha256"))
        assertEquals("the transfer must be released", 0, handler.activeTransferCount)
    }

    @Test
    fun `backpressure stops the sender at the window until an ack arrives`() {
        val content = ByteArray(64 * 20)
        val handler = handlerFor(content, size = content.size.toLong(), chunkSize = 64)
        handler.handleDownloadStart(startPayload(), ALL_SHARE_CAPABILITIES)

        val first = handler.drainReadyMessages()
        assertEquals(
            "only a full window may go out unacknowledged",
            ProtocolConstants.FILE_TRANSFER_WINDOW,
            first.count { it.type == MessageType.FILES_DOWNLOAD_CHUNK },
        )

        assertTrue(
            "nothing more may be sent before an ack",
            handler.drainReadyMessages().isEmpty(),
        )

        handler.handleDownloadAck(
            JSONObject().put("transferId", TRANSFER_ID).put("sequence", 0L),
        )
        assertEquals(
            1,
            handler.drainReadyMessages().count { it.type == MessageType.FILES_DOWNLOAD_CHUNK },
        )
    }

    @Test
    fun `chunks arrive gapless and the last one is flagged`() {
        val content = ByteArray(150)
        val handler = handlerFor(content, size = content.size.toLong(), chunkSize = 64)
        handler.handleDownloadStart(startPayload(), ALL_SHARE_CAPABILITIES)

        val sequence = FileChunkSequence()
        var guard = 0
        while (guard++ < 50) {
            val messages = handler.drainReadyMessages()
            if (messages.isEmpty()) {
                break
            }
            for (message in messages.filter { it.type == MessageType.FILES_DOWNLOAD_CHUNK }) {
                val seq = message.payload.getLong("sequence")
                assertTrue(
                    "chunk $seq must satisfy the receiving sequence rules",
                    sequence.accept(seq, message.payload.getBoolean("last")),
                )
                handler.handleDownloadAck(
                    JSONObject().put("transferId", TRANSFER_ID).put("sequence", seq),
                )
            }
        }
        assertTrue(sequence.isFinished)
    }

    @Test
    fun `a file beyond the transfer limit is refused before anything is opened`() {
        val source = FakeFileSource(
            entry = fileEntry(size = 5_000L),
            content = ByteArray(5_000),
        )
        val handler = FilesRequestHandler(source, maxDownloadBytes = 1_000L)
        assertFailure(handler.handleDownloadStart(startPayload(), ALL_SHARE_CAPABILITIES), ProtocolError.UNSUPPORTED)
        assertFalse("the file must not be opened", source.openCalled)
        assertEquals(0, handler.activeTransferCount)
    }

    @Test
    fun `a directory is never downloadable`() {
        val source = FakeFileSource(
            entry = FileEntry(fileId, "Belege", null, null, null, FileEntryKind.DIRECTORY),
        )
        val handler = FilesRequestHandler(source)
        assertFailure(handler.handleDownloadStart(startPayload(), ALL_SHARE_CAPABILITIES), ProtocolError.NOT_FOUND)
        assertFalse(source.openCalled)
    }

    @Test
    fun `too many concurrent transfers are rate limited`() {
        val content = ByteArray(4_096)
        val source = FakeFileSource(
            entry = fileEntry(size = content.size.toLong()),
            content = content,
        )
        val handler = FilesRequestHandler(source, maxConcurrentTransfers = 1, chunkSize = 64)
        assertEquals(FilesOutcome.Accepted, handler.handleDownloadStart(startPayload("dHJhbnMx"), ALL_SHARE_CAPABILITIES))
        assertFailure(
            handler.handleDownloadStart(startPayload("dHJhbnMy"), ALL_SHARE_CAPABILITIES),
            ProtocolError.RATE_LIMITED,
        )
    }

    @Test
    fun `a duplicate transfer id is refused`() {
        val content = ByteArray(4_096)
        val handler = handlerFor(content, size = content.size.toLong(), chunkSize = 64)
        handler.handleDownloadStart(startPayload(), ALL_SHARE_CAPABILITIES)
        assertFailure(handler.handleDownloadStart(startPayload(), ALL_SHARE_CAPABILITIES), ProtocolError.INVALID_MESSAGE)
    }

    @Test
    fun `an ack for something never sent aborts the transfer`() {
        val content = ByteArray(4_096)
        val handler = handlerFor(content, size = content.size.toLong(), chunkSize = 64)
        handler.handleDownloadStart(startPayload(), ALL_SHARE_CAPABILITIES)
        handler.drainReadyMessages()

        assertFailure(
            handler.handleDownloadAck(
                JSONObject().put("transferId", TRANSFER_ID).put("sequence", 99L),
            ),
            ProtocolError.INVALID_MESSAGE,
        )
        assertEquals("a violation ends the transfer", 0, handler.activeTransferCount)
    }

    @Test
    fun `an ack for an unknown transfer is not found`() {
        val handler = FilesRequestHandler(FakeFileSource())
        assertFailure(
            handler.handleDownloadAck(
                JSONObject().put("transferId", TRANSFER_ID).put("sequence", 0L),
            ),
            ProtocolError.NOT_FOUND,
        )
    }

    @Test
    fun `cancel releases the transfer and closes the stream`() {
        val content = ByteArray(4_096)
        val source = FakeFileSource(
            entry = fileEntry(size = content.size.toLong()),
            content = content,
        )
        val handler = FilesRequestHandler(source, chunkSize = 64)
        handler.handleDownloadStart(startPayload(), ALL_SHARE_CAPABILITIES)
        handler.drainReadyMessages()

        handler.handleCancel(JSONObject().put("transferId", TRANSFER_ID))
        assertEquals(0, handler.activeTransferCount)
        assertTrue("the stream must be closed", source.closed)
        assertTrue(handler.drainReadyMessages().isEmpty())
    }

    @Test
    fun `cancelAll ends every transfer with the stated reason`() {
        val content = ByteArray(4_096)
        val source = FakeFileSource(
            entry = fileEntry(size = content.size.toLong()),
            content = content,
        )
        val handler = FilesRequestHandler(source, chunkSize = 64)
        handler.handleDownloadStart(startPayload(), ALL_SHARE_CAPABILITIES)

        val messages = handler.cancelAll(FileTransferCancelReason.CAPABILITY_REVOKED)
        assertEquals(1, messages.size)
        assertEquals(MessageType.FILES_DOWNLOAD_CANCEL, messages[0].type)
        assertEquals("capability_revoked", messages[0].payload.getString("reason"))
        assertEquals(0, handler.activeTransferCount)
        assertTrue(source.closed)
    }

    // ---------------------------------------------------------------- utils

    private fun handlerFor(content: ByteArray, size: Long, chunkSize: Int): FilesRequestHandler =
        FilesRequestHandler(
            FakeFileSource(entry = fileEntry(size = size), content = content),
            chunkSize = chunkSize,
        )

    private fun startPayload(transferId: String = TRANSFER_ID): JSONObject = JSONObject()
        .put("transferId", transferId)
        .put("shareId", shareId)
        .put("fileId", fileId)

    private fun fileEntry(size: Long = 182_734L) = FileEntry(
        id = fileId,
        name = "Rechnung Januar.pdf",
        mimeType = "application/pdf",
        size = size,
        modifiedAtEpochMillis = 1_757_754_720_000L,
        kind = FileEntryKind.FILE,
    )

    private fun replyPayload(outcome: FilesOutcome, expected: MessageType): JSONObject {
        assertTrue("expected a reply but got $outcome", outcome is FilesOutcome.Reply)
        val reply = (outcome as FilesOutcome.Reply).message
        assertEquals(expected, reply.type)
        return reply.payload
    }

    private fun assertFailure(outcome: FilesOutcome, expected: ProtocolError) {
        assertTrue("expected a failure but got $outcome", outcome is FilesOutcome.Failure)
        assertEquals(expected, (outcome as FilesOutcome.Failure).error)
    }

    private fun sha256Hex(content: ByteArray): String =
        CryptoUtils.toHex(MessageDigest.getInstance("SHA-256").digest(content))

    private class FakeFileSource(
        private val shares: List<FileShare> = emptyList(),
        private val page: FileListPage? = FileListPage(emptyList(), null),
        private val entry: FileEntry? = null,
        private val content: ByteArray? = null,
    ) : FileSource {
        var lastDirectoryId: String? = null
        var listCalled = false
        var openCalled = false
        var closed = false

        override fun shares(): List<FileShare> = shares

        override fun findShare(shareId: String): FileShare? =
            shares.firstOrNull { it.shareId == shareId } ?: defaultShare(shareId)

        /**
         * Most tests never declare a share; they exercise listing and download instead. Treating an
         * unknown id as a files.read share keeps those tests about what they are about - the
         * capability rules get their own tests below.
         */
        private fun defaultShare(shareId: String): FileShare? =
            if (shares.isEmpty()) {
                FileShare(shareId, "Test", FileShareKind.TREE, Capability.FILES_READ, 0L)
            } else {
                null
            }

        override fun list(
            shareId: String,
            directoryId: String?,
            cursor: String?,
            limit: Int?,
        ): FileListPage? {
            listCalled = true
            lastDirectoryId = directoryId
            return page
        }

        override fun metadata(shareId: String, fileId: String): FileEntry? = entry

        override fun openStream(shareId: String, fileId: String): InputStream? {
            openCalled = true
            val bytes = content ?: return null
            val delegate = ByteArrayInputStream(bytes)
            return object : InputStream() {
                override fun read(): Int = delegate.read()

                override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

                override fun close() {
                    closed = true
                }
            }
        }
    }

    private companion object {
        const val TRANSFER_ID = "dHJhbnNmZXIx"
    }
}
