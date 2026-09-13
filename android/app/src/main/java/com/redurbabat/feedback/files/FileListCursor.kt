package com.redurbabat.feedback.files

import com.redurbabat.feedback.security.CryptoUtils

/**
 * Opaque paging cursor for `files.list` (protocol/PROTOCOL.md section 8.3.5).
 *
 * A cursor is an offset into the provider's own row order, encoded so the server and the browser
 * cannot read or forge a meaningful position. It is session bound like every other id here.
 *
 * Paging deliberately does not sort the whole directory first: that would mean reading every row
 * of a large folder into memory before answering the first page.
 */
object FileListCursor {

    fun encode(offset: Int): String {
        require(offset >= 0) { "offset must not be negative" }
        val payload = PREFIX + offset.toString()
        return CryptoUtils.Base64Url.encode(payload.toByteArray(Charsets.UTF_8))
    }

    /** Null for anything that is not one of our cursors, so a forged value cannot seek. */
    fun decodeOrNull(cursor: String?): Int? {
        if (cursor.isNullOrEmpty()) {
            return null
        }
        val decoded = try {
            String(CryptoUtils.Base64Url.decode(cursor), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!decoded.startsWith(PREFIX)) {
            return null
        }
        val offset = decoded.substring(PREFIX.length).toIntOrNull() ?: return null
        return offset.takeIf { it >= 0 }
    }

    private const val PREFIX = "fbc1:"
}
