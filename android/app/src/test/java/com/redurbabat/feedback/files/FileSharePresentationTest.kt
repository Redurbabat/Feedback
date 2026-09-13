package com.redurbabat.feedback.files

import com.redurbabat.feedback.protocol.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSharePresentationTest {

    private val hour = 3_600_000L
    private val day = 86_400_000L

    @Test
    fun `every share kind has its own wording`() {
        val labels = FileShareKind.values().map(FileSharePresentation::kindLabel)
        assertEquals("Ordner", FileSharePresentation.kindLabel(FileShareKind.TREE))
        assertEquals("Einzelne Datei", FileSharePresentation.kindLabel(FileShareKind.FILE))
        assertEquals("Auswahl", FileSharePresentation.kindLabel(FileShareKind.COLLECTION))
        // No two kinds may read the same, or the owner cannot tell them apart in the list.
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `each governing capability is named distinctly`() {
        val files = FileSharePresentation.capabilityLabel(Capability.FILES_READ)
        val photos = FileSharePresentation.capabilityLabel(Capability.MEDIA_PHOTOS_READ)
        val videos = FileSharePresentation.capabilityLabel(Capability.MEDIA_VIDEOS_READ)
        assertEquals(3, setOf(files, photos, videos).size)
        assertTrue(photos != videos)
    }

    @Test
    fun `a share line names the kind, the switch that governs it and its age`() {
        val share = FileShare(
            shareId = "c2hhcmUx",
            displayName = "Fotoauswahl (3)",
            kind = FileShareKind.COLLECTION,
            capability = Capability.MEDIA_PHOTOS_READ,
            addedAtEpochMillis = 0L,
        )
        val line = FileSharePresentation.shareLine(share, nowEpochMillis = 2 * 3_600_000L)
        assertTrue(line.contains("Auswahl"))
        // Naming the capability matters: two areas can look alike and answer to different switches.
        assertTrue(line.contains("Fotos"))
        assertTrue(line.contains("vor 2 Stunden"))
        assertTrue("must not claim videos", !line.contains("Videos"))
    }

    @Test
    fun `a collection says how many items it holds`() {
        assertEquals("Auswahl · 1 Element", FileSharePresentation.collectionLabel(1))
        assertEquals("Auswahl · 12 Elemente", FileSharePresentation.collectionLabel(12))
    }

    @Test
    fun `a fresh share reads as just shared`() {
        assertEquals(
            "gerade freigegeben",
            FileSharePresentation.addedLabel(addedAtEpochMillis = 1_000L, nowEpochMillis = 1_000L),
        )
        assertEquals(
            "gerade freigegeben",
            FileSharePresentation.addedLabel(0L, hour - 1L),
        )
    }

    @Test
    fun `hours and days are singular and plural`() {
        assertEquals("vor 1 Stunde freigegeben", FileSharePresentation.addedLabel(0L, hour))
        assertEquals("vor 5 Stunden freigegeben", FileSharePresentation.addedLabel(0L, 5 * hour))
        assertEquals("vor 23 Stunden freigegeben", FileSharePresentation.addedLabel(0L, 23 * hour))
        assertEquals("vor 1 Tag freigegeben", FileSharePresentation.addedLabel(0L, day))
        assertEquals("vor 3 Tagen freigegeben", FileSharePresentation.addedLabel(0L, 3 * day))
    }

    @Test
    fun `the hour to day boundary does not skip a label`() {
        // One millisecond before a full day is still counted in hours, not as zero days.
        assertEquals("vor 23 Stunden freigegeben", FileSharePresentation.addedLabel(0L, day - 1L))
    }

    @Test
    fun `a clock that moved backwards does not produce a negative age`() {
        val label = FileSharePresentation.addedLabel(addedAtEpochMillis = 10 * day, nowEpochMillis = 0L)
        assertEquals("freigegeben", label)
        assertTrue(!label.contains("-"))
    }

    @Test
    fun `nothing is reported when every grant still holds`() {
        assertNull(FileSharePresentation.unavailableNotice(0))
        assertNull(FileSharePresentation.unavailableNotice(-1))
    }

    @Test
    fun `withdrawn grants are reported and countable`() {
        val one = FileSharePresentation.unavailableNotice(1)
        assertNotNull(one)
        assertTrue(one!!.contains("einen"))

        val many = FileSharePresentation.unavailableNotice(4)
        assertNotNull(many)
        assertTrue(many!!.contains("4"))
    }

    @Test
    fun `an empty share list says so rather than reading as ready`() {
        val summary = FileSharePresentation.shareSummary(0)
        assertTrue(summary.contains("kein"))
        // The point of the sentence: granting the capability alone exposes nothing.
        assertTrue(summary.contains("nichts"))
    }

    @Test
    fun `share counts are singular and plural`() {
        assertEquals("1 Bereich freigegeben.", FileSharePresentation.shareSummary(1))
        assertEquals("7 Bereiche freigegeben.", FileSharePresentation.shareSummary(7))
    }
}
