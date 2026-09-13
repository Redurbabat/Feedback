package com.redurbabat.feedback.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class Iso8601Test {

    @Test
    fun formatsEpochZero() {
        assertEquals("1970-01-01T00:00:00.000Z", Iso8601.format(0L))
    }

    @Test
    fun formatsWithMillisecondPrecision() {
        assertEquals("2025-09-13T10:40:00.000Z", Iso8601.format(1_757_760_000_000L))
        assertEquals("2025-09-13T10:40:00.007Z", Iso8601.format(1_757_760_000_007L))
    }

    @Test
    fun formatIsIndependentOfTheDefaultLocaleAndTimeZone() {
        val previousLocale = Locale.getDefault()
        val previousZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals("2025-09-13T10:40:00.000Z", Iso8601.format(1_757_760_000_000L))
            assertEquals(1_757_760_000_000L, Iso8601.parse("2025-09-13T10:40:00.000Z"))
        } finally {
            Locale.setDefault(previousLocale)
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun roundTripsAcrossManyInstants() {
        var millis = 0L
        while (millis < 4_000_000_000_000L) {
            assertEquals(millis, Iso8601.parse(Iso8601.format(millis)))
            millis += 997_003_001L
        }
    }

    @Test
    fun parsesTheProtocolExample() {
        assertEquals(1_789_298_553_000L, Iso8601.parse("2026-09-13T11:22:33.000Z"))
    }

    @Test
    fun rejectsMissingMilliseconds() {
        assertNull(Iso8601.parse("2026-09-13T11:22:33Z"))
    }

    @Test
    fun rejectsNumericOffsetInsteadOfZulu() {
        assertNull(Iso8601.parse("2026-09-13T12:22:33.000+01:00"))
    }

    @Test
    fun rejectsSpaceSeparator() {
        assertNull(Iso8601.parse("2026-09-13 11:22:33.000Z"))
    }

    @Test
    fun rejectsImpossibleCalendarValues() {
        assertNull(Iso8601.parse("2026-13-01T00:00:00.000Z"))
        assertNull(Iso8601.parse("2026-09-31T00:00:00.000Z"))
        assertNull(Iso8601.parse("2026-09-13T25:00:00.000Z"))
    }

    @Test
    fun rejectsEmptyAndTrailingContent() {
        assertNull(Iso8601.parse(""))
        assertNull(Iso8601.parse("2026-09-13T11:22:33.000Z "))
        assertNull(Iso8601.parse("x2026-09-13T11:22:33.000Z"))
    }

    @Test
    fun rejectsNonDigitCharactersInsideTheShape() {
        assertNull(Iso8601.parse("20x6-09-13T11:22:33.000Z"))
        assertNull(Iso8601.parse("2026-09-13t11:22:33.000Z"))
        assertNull(Iso8601.parse("2026-09-13T11:22:33.000z"))
    }
}
