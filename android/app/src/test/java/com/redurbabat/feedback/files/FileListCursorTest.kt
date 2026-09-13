package com.redurbabat.feedback.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileListCursorTest {

    @Test
    fun `offsets round trip`() {
        for (offset in listOf(0, 1, 200, 4_321, Int.MAX_VALUE)) {
            assertEquals(offset, FileListCursor.decodeOrNull(FileListCursor.encode(offset)))
        }
    }

    @Test
    fun `a cursor is opaque and does not read as a number`() {
        val cursor = FileListCursor.encode(200)
        assertTrue(FileNames.isAcceptableOpaqueId(cursor))
        assertNull(cursor.toIntOrNull())
        assertTrue(!cursor.contains("200"))
    }

    @Test
    fun `a forged or foreign cursor does not seek`() {
        val rejected = listOf(
            null,
            "",
            "200",
            "not-base64url!",
            com.redurbabat.feedback.security.CryptoUtils.Base64Url.encode(
                "200".toByteArray(Charsets.UTF_8),
            ),
            com.redurbabat.feedback.security.CryptoUtils.Base64Url.encode(
                "fbc1:-5".toByteArray(Charsets.UTF_8),
            ),
            com.redurbabat.feedback.security.CryptoUtils.Base64Url.encode(
                "fbc1:abc".toByteArray(Charsets.UTF_8),
            ),
        )
        for (cursor in rejected) {
            assertNull("must reject cursor: $cursor", FileListCursor.decodeOrNull(cursor))
        }
    }

    @Test
    fun `a negative offset is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) { FileListCursor.encode(-1) }
    }
}
