package com.redurbabat.feedback.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileIdRegistryTest {

    private val share = "c2hhcmUx"
    private val otherShare = "c2hhcmUy"

    @Test
    fun `the same document keeps the same id within a session`() {
        val registry = FileIdRegistry()
        val first = registry.idFor(share, "primary:Documents/a.pdf")
        val second = registry.idFor(share, "primary:Documents/a.pdf")
        assertEquals(first, second)
        assertEquals(1, registry.size)
    }

    @Test
    fun `different documents get different ids`() {
        val registry = FileIdRegistry()
        val first = registry.idFor(share, "primary:Documents/a.pdf")
        val second = registry.idFor(share, "primary:Documents/b.pdf")
        assertNotEquals(first, second)
    }

    @Test
    fun `the same document in two shares gets two ids`() {
        val registry = FileIdRegistry()
        val first = registry.idFor(share, "primary:Documents/a.pdf")
        val second = registry.idFor(otherShare, "primary:Documents/a.pdf")
        assertNotEquals(first, second)
    }

    @Test
    fun `an id is opaque and never contains the document id`() {
        val registry = FileIdRegistry()
        val documentId = "primary:Documents/Rechnung.pdf"
        val fileId = registry.idFor(share, documentId)

        assertTrue(FileNames.isAcceptableOpaqueId(fileId))
        assertEquals(16, fileId.length)
        assertTrue(fileId.none { it == '/' || it == ':' || it == '.' })
        assertTrue(!fileId.contains("Documents"))
        assertTrue(!fileId.contains("Rechnung"))
        assertTrue(!documentId.contains(fileId))
    }

    @Test
    fun `resolve returns the reference and clear ends the session`() {
        val registry = FileIdRegistry()
        val fileId = registry.idFor(share, "primary:Documents/a.pdf")
        assertEquals(
            FileIdRegistry.Reference(share, "primary:Documents/a.pdf"),
            registry.resolve(fileId),
        )

        registry.clear()
        assertNull("an id must not survive its session", registry.resolve(fileId))
        assertEquals(0, registry.size)
    }

    @Test
    fun `an id cannot be redeemed against another share`() {
        val registry = FileIdRegistry()
        val fileId = registry.idFor(share, "primary:Documents/a.pdf")
        assertNull(registry.resolveWithin(otherShare, fileId))
        assertEquals(
            FileIdRegistry.Reference(share, "primary:Documents/a.pdf"),
            registry.resolveWithin(share, fileId),
        )
    }

    @Test
    fun `an unknown id resolves to nothing`() {
        val registry = FileIdRegistry()
        assertNull(registry.resolve("bmV2ZXJtaW50ZWQ"))
        assertNull(registry.resolveWithin(share, ""))
    }

    @Test
    fun `withdrawing a share releases exactly its ids`() {
        val registry = FileIdRegistry()
        val mine = registry.idFor(share, "primary:Documents/a.pdf")
        val theirs = registry.idFor(otherShare, "primary:Pictures/b.jpg")

        registry.releaseShare(share)
        assertNull(registry.resolve(mine))
        assertEquals(
            FileIdRegistry.Reference(otherShare, "primary:Pictures/b.jpg"),
            registry.resolve(theirs),
        )
    }

    @Test
    fun `browsing a large tree evicts the oldest ids instead of growing forever`() {
        val registry = FileIdRegistry(maxEntries = 3)
        val first = registry.idFor(share, "doc-0")
        registry.idFor(share, "doc-1")
        registry.idFor(share, "doc-2")
        assertEquals(3, registry.size)

        registry.idFor(share, "doc-3")
        assertEquals(3, registry.size)
        assertNull("the oldest id should have been evicted", registry.resolve(first))
    }

    @Test
    fun `a document still being used is not the first evicted`() {
        val registry = FileIdRegistry(maxEntries = 3)
        val first = registry.idFor(share, "doc-0")
        registry.idFor(share, "doc-1")
        registry.idFor(share, "doc-2")

        // Touching doc-0 makes doc-1 the least recently used.
        registry.idFor(share, "doc-0")
        registry.idFor(share, "doc-3")

        assertEquals(
            FileIdRegistry.Reference(share, "doc-0"),
            registry.resolve(first),
        )
    }

    @Test
    fun `a non positive bound is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { FileIdRegistry(maxEntries = 0) }
    }
}
