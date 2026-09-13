package com.redurbabat.feedback.protocol

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ISO-8601 UTC timestamps in the single shape the protocol allows: `2026-09-13T11:22:33.000Z`.
 *
 * `java.time` is not available on minSdk 24, so `SimpleDateFormat` with a fixed pattern, a fixed
 * `Locale.US` and the UTC time zone is used. Instances are created per call because
 * `SimpleDateFormat` is not thread safe.
 */
object Iso8601 {

    const val PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    const val LENGTH = 24

    fun format(epochMillis: Long): String = formatter().format(Date(epochMillis))

    /** Returns null for anything that is not exactly this shape or not a real calendar date. */
    fun parse(value: String): Long? {
        if (!hasIsoShape(value)) {
            return null
        }
        val position = ParsePosition(0)
        val parsed = formatter().parse(value, position) ?: return null
        if (position.index != value.length) {
            return null
        }
        return parsed.time
    }

    private fun formatter(): SimpleDateFormat {
        val formatter = SimpleDateFormat(PATTERN, Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        formatter.isLenient = false
        return formatter
    }

    private fun hasIsoShape(value: String): Boolean {
        if (value.length != LENGTH) {
            return false
        }
        for (index in value.indices) {
            val character = value[index]
            val valid = when (index) {
                4, 7 -> character == '-'
                10 -> character == 'T'
                13, 16 -> character == ':'
                19 -> character == '.'
                23 -> character == 'Z'
                else -> character in '0'..'9'
            }
            if (!valid) {
                return false
            }
        }
        return true
    }
}
