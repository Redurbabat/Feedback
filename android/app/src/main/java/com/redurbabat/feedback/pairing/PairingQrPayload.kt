package com.redurbabat.feedback.pairing

/**
 * The QR transport for a pairing session, as defined in `protocol/PROTOCOL.md` section 6.1.
 *
 * ```text
 * feedback://pair?v=1&ticket=<base64url, 32 Bytes>
 * ```
 *
 * The QR code carries the high-entropy ticket, never the six-digit display code: that code is a
 * convenience lookup with roughly 20 bits of entropy and is explicitly not a security anchor. It
 * also never carries a private key, the `deviceSecret` or a device token.
 *
 * The payload is only as valid as the pairing session it belongs to. The ticket is single use, the
 * session expires after five minutes, and the server invalidates it on claim, reject or expiry.
 */
object PairingQrPayload {
    const val SCHEME = "feedback"
    const val HOST = "pair"
    const val VERSION = 1

    /** Base64url length of 32 random bytes without padding. */
    const val MIN_TICKET_LENGTH = 43
    const val MAX_TICKET_LENGTH = 128

    private const val PREFIX = "$SCHEME://$HOST?v=$VERSION&ticket="

    fun build(ticket: String): String {
        require(isAcceptableTicket(ticket)) { "Pairing ticket does not meet the transport policy" }
        return PREFIX + ticket
    }

    /**
     * Strict parser. Returns the ticket, or null for anything that is not exactly a version 1
     * payload with a plausible high-entropy ticket. Deny-by-default: no tolerance for extra
     * parameters, a different parameter order, or a shortened ticket.
     */
    fun parseTicketOrNull(payload: String): String? {
        if (!payload.startsWith(PREFIX)) {
            return null
        }
        return payload.substring(PREFIX.length).takeIf(::isAcceptableTicket)
    }

    /**
     * A ticket is base64url without padding and long enough that a display code or another
     * low-entropy value cannot be smuggled in as one.
     */
    private fun isAcceptableTicket(ticket: String): Boolean {
        if (ticket.length !in MIN_TICKET_LENGTH..MAX_TICKET_LENGTH) {
            return false
        }
        return ticket.all(::isBase64UrlCharacter)
    }

    private fun isBase64UrlCharacter(character: Char): Boolean = when (character) {
        in 'A'..'Z', in 'a'..'z', in '0'..'9', '-', '_' -> true
        else -> false
    }
}
