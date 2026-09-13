package com.redurbabat.feedback.protocol

/**
 * Error codes from protocol/PROTOCOL.md section 10. The wire code is the contract, the HTTP
 * status is what the server maps it to.
 */
enum class ProtocolError(val code: String, val httpStatus: Int) {
    UNAUTHORIZED("UNAUTHORIZED", 401),
    FORBIDDEN("FORBIDDEN", 403),
    SESSION_EXPIRED("SESSION_EXPIRED", 409),
    DEVICE_REVOKED("DEVICE_REVOKED", 403),
    CAPABILITY_DENIED("CAPABILITY_DENIED", 403),
    PERMISSION_REQUIRED("PERMISSION_REQUIRED", 403),
    UNSUPPORTED("UNSUPPORTED", 400),
    INVALID_MESSAGE("INVALID_MESSAGE", 400),
    RATE_LIMITED("RATE_LIMITED", 429),
    PAIRING_EXPIRED("PAIRING_EXPIRED", 410),
    PAIRING_ALREADY_USED("PAIRING_ALREADY_USED", 409),
    NOT_FOUND("NOT_FOUND", 404),
    INTERNAL("INTERNAL", 500);

    companion object {
        /** Deny by default: an unknown code stays unknown instead of becoming INTERNAL. */
        fun fromCode(value: String): ProtocolError? {
            for (candidate in values()) {
                if (candidate.code == value) {
                    return candidate
                }
            }
            return null
        }
    }
}
