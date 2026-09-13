package com.redurbabat.feedback.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSharePresentationTest {

    private val hour = 3_600_000L
    private val day = 86_400_000L

    @Test
    fun `both share kinds have distinct wording`() {
        val tree = FileSharePresentation.kindLabel(FileShareKind.TREE)
        val file = FileSharePresentation.kindLabel(FileShareKind.FILE)
        assertEquals("Ordner", tree)
        assertEquals("Einzelne Datei", file)
        assertTrue(tree != file)
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
