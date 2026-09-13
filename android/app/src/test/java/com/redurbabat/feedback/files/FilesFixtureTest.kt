package com.redurbabat.feedback.files

import com.redurbabat.feedback.protocol.Capability
import com.redurbabat.feedback.protocol.MessageType
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android half of the `files.read` contract test.
 *
 * Same arrangement as [com.redurbabat.feedback.screen.ScreenFixtureTest]: the canonical frames in
 * `protocol/fixtures/files-v1.json` are read here and by the server tests, so a field renamed on
 * one side fails on the other instead of on a phone.
 *
 * Two things are checked. The requests in the fixtures are fed to the real handler, and what it
 * answers is compared field for field with the responses in the fixtures. The share and entry
 * objects are additionally run through this side's own parsers, which is where the rules about
 * opaque ids and display names live.
 */
class FilesFixtureTest {

    private val allowed = setOf(
        Capability.FILES_READ,
        Capability.MEDIA_PHOTOS_READ,
        Capability.MEDIA_VIDEOS_READ,
    )

    private val fixtures: JSONObject by lazy { JSONObject(fixtureFile().readText()) }

    private fun fixtureFile(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "protocol/fixtures/files-v1.json")
            if (candidate.isFile) {
                return candidate
            }
            directory = directory.parentFile
        }
        throw AssertionError("protocol/fixtures/files-v1.json nicht gefunden")
    }

    private fun entries(group: String, type: String, direction: String): List<JSONObject> {
        val array: JSONArray = fixtures.getJSONArray(group)
        val result = ArrayList<JSONObject>()
        for (index in 0 until array.length()) {
            val entry = array.getJSONObject(index)
            if (entry.getString("type") == type && entry.getString("direction") == direction) {
                result.add(entry.getJSONObject("payload"))
            }
        }
        return result
    }

    private fun first(group: String, type: String, direction: String): JSONObject {
        val found = entries(group, type, direction)
        assertTrue("kein Fixture fuer $type ($direction)", found.isNotEmpty())
        return found[0]
    }

    private fun keysOf(payload: JSONObject): Set<String> {
        val keys = LinkedHashSet<String>()
        for (key in payload.keys()) {
            keys.add(key)
        }
        return keys
    }

    private fun payloadOf(outcome: FilesOutcome, type: MessageType): JSONObject {
        assertTrue("erwartet: Antwort, bekommen: $outcome", outcome is FilesOutcome.Reply)
        val message = (outcome as FilesOutcome.Reply).message
        assertEquals(type, message.type)
        return message.payload
    }

    @Test
    fun `the fixture file is actually there and not empty`() {
        assertTrue(fixtures.getJSONArray("accepted").length() > 10)
        assertTrue(fixtures.getJSONArray("rejected").length() > 5)
    }

    @Test
    fun `the shares answer carries exactly the fields the fixtures declare`() {
        val expected = first("accepted", "files.shares.response", "device-to-server")
        val expectedShare = expected.getJSONArray("shares").getJSONObject(0)

        val source = FixtureSource(
            shares = listOf(
                FileShare(
                    shareId = expectedShare.getString("shareId"),
                    displayName = expectedShare.getString("displayName"),
                    kind = requireNotNull(FileShareKind.fromWire(expectedShare.getString("kind"))),
                    capability = requireNotNull(
                        Capability.fromWire(expectedShare.getString("capability")),
                    ),
                    addedAtEpochMillis = 1_757_754_720_000L,
                ),
            ),
        )
        val payload = payloadOf(
            FilesRequestHandler(source).handleSharesRequest(allowed),
            MessageType.FILES_SHARES_RESPONSE,
        )

        assertEquals(keysOf(expected), keysOf(payload))
        assertEquals(keysOf(expectedShare), keysOf(payload.getJSONArray("shares").getJSONObject(0)))
        assertTrue("keine URI darf auf die Leitung", !payload.toString().contains("content://"))
    }

    @Test
    fun `the listing answer carries exactly the fields the fixtures declare`() {
        val request = entries("accepted", "files.list.request", "server-to-device").last()
        val expected = first("accepted", "files.list.response", "device-to-server")
        val expectedEntry = expected.getJSONArray("entries").getJSONObject(1)

        val source = FixtureSource(
            page = FileListPage(
                entries = listOf(entryFrom(expectedEntry)),
                nextCursor = expected.getString("nextCursor"),
            ),
        )
        val payload = payloadOf(
            FilesRequestHandler(source).handleListRequest(request, allowed),
            MessageType.FILES_LIST_RESPONSE,
        )

        assertEquals(keysOf(expected), keysOf(payload))
        assertEquals(keysOf(expectedEntry), keysOf(payload.getJSONArray("entries").getJSONObject(0)))
        // The request fixture uses a subdirectory and a cursor; both have to arrive.
        assertEquals(request.getString("directoryId"), source.lastDirectoryId)
        assertEquals(request.getString("cursor"), source.lastCursor)
    }

    @Test
    fun `the metadata answer carries exactly the fields the fixtures declare`() {
        val request = first("accepted", "files.metadata.request", "server-to-device")
        val expected = first("accepted", "files.metadata.response", "device-to-server")
        val expectedEntry = expected.getJSONObject("entry")

        val source = FixtureSource(entry = entryFrom(expectedEntry))
        val payload = payloadOf(
            FilesRequestHandler(source).handleMetadataRequest(request, allowed),
            MessageType.FILES_METADATA_RESPONSE,
        )
        assertEquals(keysOf(expected), keysOf(payload))
        assertEquals(keysOf(expectedEntry), keysOf(payload.getJSONObject("entry")))
    }

    @Test
    fun `a download produces chunks and a completion shaped like the fixtures`() {
        val start = first("accepted", "files.download.start", "server-to-device")
        val expectedChunk = first("accepted", "files.download.chunk", "device-to-server")
        val expectedComplete = first("accepted", "files.download.complete", "device-to-server")

        val source = FixtureSource(
            entry = entryFrom(first("accepted", "files.metadata.response", "device-to-server").getJSONObject("entry")),
            content = ByteArray(64) { 7 },
        )
        val handler = FilesRequestHandler(source)
        assertEquals(FilesOutcome.Accepted, handler.handleDownloadStart(start, allowed))

        val produced = handler.drainReadyMessages()
        val chunk = produced.first { it.type == MessageType.FILES_DOWNLOAD_CHUNK }
        assertEquals(keysOf(expectedChunk), keysOf(chunk.payload))
        assertEquals(start.getString("transferId"), chunk.payload.getString("transferId"))

        val complete = produced.firstOrNull { it.type == MessageType.FILES_DOWNLOAD_COMPLETE }
        assertNotNull("Der Transfer hat keinen Abschluss erzeugt", complete)
        assertEquals(keysOf(expectedComplete), keysOf(complete!!.payload))
        // 64 hex characters, because a shorter digest silently compares against nothing on the
        // receiving side.
        assertEquals(64, complete.payload.getString("sha256").length)
    }

    @Test
    fun `a cancel from the server is understood and answered in the same shape`() {
        val start = first("accepted", "files.download.start", "server-to-device")
        val cancel = first("accepted", "files.download.cancel", "server-to-device")
        val expected = first("accepted", "files.download.cancel", "device-to-server")

        val source = FixtureSource(
            entry = entryFrom(first("accepted", "files.metadata.response", "device-to-server").getJSONObject("entry")),
            content = ByteArray(16),
        )
        val handler = FilesRequestHandler(source)
        handler.handleDownloadStart(start, allowed)
        handler.drainReadyMessages()

        assertEquals(FilesOutcome.Accepted, handler.handleCancel(cancel))
        assertEquals(0, handler.activeTransferCount)

        // And the other direction: what this side sends when it gives up has to match the
        // fixture the server validates.
        val second = FilesRequestHandler(source)
        second.handleDownloadStart(start, allowed)
        val produced = second.cancelAll(FileTransferCancelReason.READ_ERROR)
        assertEquals(1, produced.size)
        assertEquals(MessageType.FILES_DOWNLOAD_CANCEL, produced[0].type)
        assertEquals(keysOf(expected), keysOf(produced[0].payload))
        assertEquals(expected.getString("reason"), produced[0].payload.getString("reason"))
    }

    @Test
    fun `every reason and capability in the fixtures exists on this side`() {
        for (payload in entries("accepted", "files.download.cancel", "device-to-server") +
            entries("accepted", "files.download.cancel", "server-to-device")) {
            val reason = payload.getString("reason")
            assertNotNull(
                "Unbekannter Abbruchgrund im Fixture: $reason",
                FileTransferCancelReason.fromWire(reason),
            )
        }
        val shares = first("accepted", "files.shares.response", "device-to-server")
            .getJSONArray("shares")
        for (index in 0 until shares.length()) {
            val share = shares.getJSONObject(index)
            assertNotNull(FileShareKind.fromWire(share.getString("kind")))
            val capability = Capability.fromWire(share.getString("capability"))
            assertNotNull("Unbekannte Capability im Fixture", capability)
            assertTrue("Diese Capability regiert keinen Bereich", capability in allowed)
        }
    }

    @Test
    fun `this side refuses the same shares the server refuses`() {
        for (payload in entries("rejected", "files.shares.response", "device-to-server")) {
            val shares = payload.getJSONArray("shares")
            for (index in 0 until shares.length()) {
                // Same rules, checked by this side's own parser rather than a copy of the
                // server's: an opaque id that is a path, or a capability that governs no area.
                val share = FileShare.fromJsonOrNull(shares.getJSONObject(index))
                val capability = Capability.fromWire(shares.getJSONObject(index).getString("capability"))
                assertTrue(
                    "Fixture haette abgelehnt werden muessen: ${shares.getJSONObject(index)}",
                    share == null || capability !in allowed,
                )
            }
        }
    }

    @Test
    fun `this side refuses the same entries the server refuses`() {
        for (payload in entries("rejected", "files.metadata.response", "device-to-server")) {
            assertNull(
                "Fixture haette abgelehnt werden muessen: $payload",
                FileEntry.fromJsonOrNull(payload.getJSONObject("entry")),
            )
        }
    }

    private fun entryFrom(json: JSONObject): FileEntry = FileEntry(
        id = json.getString("id"),
        name = json.getString("name"),
        mimeType = if (json.isNull("mimeType")) null else json.getString("mimeType"),
        size = if (json.isNull("size")) null else json.getLong("size"),
        modifiedAtEpochMillis = null,
        kind = requireNotNull(FileEntryKind.fromWire(json.getString("kind"))),
    )

    private class FixtureSource(
        private val shares: List<FileShare> = emptyList(),
        private val page: FileListPage? = FileListPage(emptyList(), null),
        private val entry: FileEntry? = null,
        private val content: ByteArray? = null,
    ) : FileSource {
        var lastDirectoryId: String? = null
        var lastCursor: String? = null

        override fun shares(): List<FileShare> = shares

        override fun findShare(shareId: String): FileShare? =
            shares.firstOrNull { it.shareId == shareId }
                ?: FileShare(shareId, "Test", FileShareKind.TREE, Capability.FILES_READ, 0L)

        override fun list(
            shareId: String,
            directoryId: String?,
            cursor: String?,
            limit: Int?,
        ): FileListPage? {
            lastDirectoryId = directoryId
            lastCursor = cursor
            return page
        }

        override fun metadata(shareId: String, fileId: String): FileEntry? = entry

        override fun openStream(shareId: String, fileId: String): InputStream? =
            content?.let { ByteArrayInputStream(it) }
    }
}
