package com.redurbabat.feedback.files

import com.redurbabat.feedback.protocol.ProtocolConstants
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileEntryTest {

    private val file = FileEntry(
        id = "Zm9vYmFyMTIz",
        name = "Rechnung Januar.pdf",
        mimeType = "application/pdf",
        size = 182_734L,
        modifiedAtEpochMillis = 1_757_754_720_000L,
        kind = FileEntryKind.FILE,
    )

    private val directory = FileEntry(
        id = "ZGlyMDAx",
        name = "Belege",
        mimeType = null,
        size = null,
        modifiedAtEpochMillis = null,
        kind = FileEntryKind.DIRECTORY,
    )

    @Test
    fun `a file entry round trips`() {
        assertEquals(file, FileEntry.fromJsonOrNull(file.toJson()))
    }

    @Test
    fun `a directory round trips with null size and mime type`() {
        val json = directory.toJson()
        assertEquals(JSONObject.NULL, json.get(FileEntry.FIELD_SIZE))
        assertEquals(JSONObject.NULL, json.get(FileEntry.FIELD_MIME_TYPE))
        assertEquals(JSONObject.NULL, json.get(FileEntry.FIELD_MODIFIED_AT))
        assertEquals(directory, FileEntry.fromJsonOrNull(json))
    }

    @Test
    fun `names with a path separator are rejected`() {
        val rejected = listOf(
            "../secrets.txt",
            "..",
            ".",
            "sub/dir.txt",
            "windows\\path.txt",
            "/absolute.txt",
            "",
        )
        for (name in rejected) {
            assertFalse("must reject name: $name", FileNames.isAcceptableEntryName(name))
            assertNull(
                "must reject entry: $name",
                FileEntry.fromJsonOrNull(rawEntry(name = name)),
            )
        }
    }

    @Test
    fun `ordinary names including spaces and dots are accepted`() {
        val accepted = listOf(
            "Rechnung Januar.pdf",
            "foto 2026-09-13.jpg",
            ".hidden",
            "a",
            "ümlaut & sonderzeichen (1).txt",
            "a".repeat(ProtocolConstants.FILE_NAME_MAX),
        )
        for (name in accepted) {
            assertTrue("must accept name: $name", FileNames.isAcceptableEntryName(name))
        }
        assertFalse(
            FileNames.isAcceptableEntryName("a".repeat(ProtocolConstants.FILE_NAME_MAX + 1)),
        )
    }

    @Test
    fun `control characters in a name are rejected`() {
        assertFalse(FileNames.isAcceptableEntryName("line\nbreak.txt"))
        assertFalse(FileNames.isAcceptableEntryName("tab\tname.txt"))
        assertFalse(FileNames.isAcceptableEntryName("nul\u0000name.txt"))
    }

    @Test
    fun `an opaque id never looks like a path`() {
        assertTrue(FileNames.isAcceptableOpaqueId("abcDEF123-_"))
        val rejected = listOf(
            "",
            "content://com.android.providers/document/1234",
            "/data/user/0/file",
            "has space",
            "has=padding",
            "has+plus",
            "a".repeat(129),
        )
        for (id in rejected) {
            assertFalse("must reject id: $id", FileNames.isAcceptableOpaqueId(id))
        }
    }

    @Test
    fun `a directory carrying a size is rejected`() {
        val json = rawEntry(kind = "directory")
        json.put(FileEntry.FIELD_SIZE, 42)
        assertNull(FileEntry.fromJsonOrNull(json))

        assertThrows(IllegalArgumentException::class.java) {
            FileEntry("ZGlyMDAx", "Belege", null, 42L, null, FileEntryKind.DIRECTORY)
        }
    }

    @Test
    fun `a negative size is rejected`() {
        val json = rawEntry()
        json.put(FileEntry.FIELD_SIZE, -1)
        assertNull(FileEntry.fromJsonOrNull(json))
    }

    @Test
    fun `an unknown kind is rejected rather than defaulted`() {
        assertNull(FileEntry.fromJsonOrNull(rawEntry(kind = "symlink")))
        assertNull(FileEntry.fromJsonOrNull(rawEntry(kind = "")))
        assertNull(FileEntryKind.fromWire("device"))
        assertNull(FileEntryKind.fromWire(null))
    }

    @Test
    fun `a malformed modified timestamp is rejected instead of dropped`() {
        val json = rawEntry()
        json.put(FileEntry.FIELD_MODIFIED_AT, "13.09.2026")
        assertNull(FileEntry.fromJsonOrNull(json))
    }

    @Test
    fun `a listing is rejected whole when one entry is unacceptable`() {
        val good = JSONArray().put(file.toJson()).put(directory.toJson())
        assertEquals(listOf(file, directory), FileEntry.parseArrayOrNull(good))

        val bad = JSONArray().put(file.toJson()).put(rawEntry(name = "../escape"))
        assertNull(FileEntry.parseArrayOrNull(bad))

        val notObjects = JSONArray().put("plain string")
        assertNull(FileEntry.parseArrayOrNull(notObjects))
    }

    @Test
    fun `a listing beyond the protocol limit is rejected`() {
        val array = JSONArray()
        for (index in 0..ProtocolConstants.FILE_MAX_LIST_ENTRIES) {
            array.put(file.toJson())
        }
        assertNull(FileEntry.parseArrayOrNull(array))
    }

    @Test
    fun `a share round trips and rejects a non opaque id`() {
        val share = FileShare("c2hhcmUx", "Documents", FileShareKind.TREE, 1_757_754_720_000L)
        assertEquals(share, FileShare.fromJsonOrNull(share.toJson()))

        val json = share.toJson()
        json.put(FileShare.FIELD_SHARE_ID, "content://tree/primary%3ADocuments")
        assertNull(FileShare.fromJsonOrNull(json))

        json.put(FileShare.FIELD_SHARE_ID, "c2hhcmUx")
        json.put(FileShare.FIELD_KIND, "everything")
        assertNull(FileShare.fromJsonOrNull(json))
    }

    @Test
    fun `share kinds are exactly tree and file`() {
        assertNotNull(FileShareKind.fromWire("tree"))
        assertNotNull(FileShareKind.fromWire("file"))
        assertNull(FileShareKind.fromWire("directory"))
    }

    private fun rawEntry(
        name: String = "Rechnung Januar.pdf",
        kind: String = "file",
    ): JSONObject = JSONObject()
        .put(FileEntry.FIELD_ID, "Zm9vYmFyMTIz")
        .put(FileEntry.FIELD_NAME, name)
        .put(FileEntry.FIELD_MIME_TYPE, "application/pdf")
        .put(FileEntry.FIELD_SIZE, 182_734)
        .put(FileEntry.FIELD_MODIFIED_AT, "2026-09-13T09:12:00.000Z")
        .put(FileEntry.FIELD_KIND, kind)
}
