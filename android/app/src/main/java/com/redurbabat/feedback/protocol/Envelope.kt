package com.redurbabat.feedback.protocol

import org.json.JSONException
import org.json.JSONObject

/** Outcome of [Envelope.parse]. Failures always carry the protocol error code to answer with. */
sealed class EnvelopeResult {
    data class Success(val envelope: Envelope) : EnvelopeResult()

    /** [reason] is developer facing and must never contain secrets. */
    data class Failure(val error: ProtocolError, val reason: String) : EnvelopeResult()
}

/**
 * WebSocket envelope v1 (protocol/PROTOCOL.md section 7).
 *
 * The parser is strict and denies by default: wrong version, unknown type, missing or wrongly
 * typed fields, malformed ids, timestamps outside CLOCK_SKEW_MS and oversized frames are all
 * rejected. Only `org.json` is used, so the same code runs on the device and in JVM tests.
 */
data class Envelope(
    val type: MessageType,
    val messageId: String,
    val sessionId: String?,
    val timestampEpochMillis: Long,
    val payload: JSONObject,
) {
    val version: Int
        get() = ProtocolConstants.PROTOCOL_VERSION

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put(FIELD_VERSION, ProtocolConstants.PROTOCOL_VERSION)
        json.put(FIELD_TYPE, type.wireName)
        json.put(FIELD_MESSAGE_ID, messageId)
        json.put(FIELD_SESSION_ID, sessionId ?: JSONObject.NULL)
        json.put(FIELD_TIMESTAMP, Iso8601.format(timestampEpochMillis))
        json.put(FIELD_PAYLOAD, payload)
        return json
    }

    /** Serialises the frame and refuses to emit anything larger than MAX_FRAME_BYTES. */
    fun serialize(): String {
        val frame = toJson().toString()
        val size = frame.toByteArray(Charsets.UTF_8).size
        check(size <= ProtocolConstants.MAX_FRAME_BYTES) {
            "Envelope frame exceeds MAX_FRAME_BYTES"
        }
        return frame
    }

    /** True when this message may not be executed without a session id. */
    fun sessionRequirementError(): ProtocolError? =
        if (type.requiresSession && sessionId == null) {
            ProtocolError.SESSION_EXPIRED
        } else {
            null
        }

    companion object {
        const val FIELD_VERSION = "version"
        const val FIELD_TYPE = "type"
        const val FIELD_MESSAGE_ID = "messageId"
        const val FIELD_SESSION_ID = "sessionId"
        const val FIELD_TIMESTAMP = "timestamp"
        const val FIELD_PAYLOAD = "payload"

        fun create(
            type: MessageType,
            messageId: String,
            sessionId: String?,
            timestampEpochMillis: Long,
            payload: JSONObject = JSONObject(),
        ): Envelope {
            require(isUuidV4(messageId)) { "messageId must be a UUID v4" }
            require(sessionId == null || isUuid(sessionId)) { "sessionId must be a UUID" }
            return Envelope(type, messageId, sessionId, timestampEpochMillis, payload)
        }

        fun parse(frame: String, nowEpochMillis: Long): EnvelopeResult {
            if (frame.toByteArray(Charsets.UTF_8).size > ProtocolConstants.MAX_FRAME_BYTES) {
                return EnvelopeResult.Failure(
                    ProtocolError.INVALID_MESSAGE,
                    "frame exceeds MAX_FRAME_BYTES",
                )
            }

            val json = try {
                JSONObject(frame)
            } catch (error: JSONException) {
                return EnvelopeResult.Failure(
                    ProtocolError.INVALID_MESSAGE,
                    "frame is not a JSON object",
                )
            }

            val version = readInt(json, FIELD_VERSION)
                ?: return EnvelopeResult.Failure(
                    ProtocolError.INVALID_MESSAGE,
                    "version is missing or not an integer",
                )
            if (version != ProtocolConstants.PROTOCOL_VERSION) {
                return EnvelopeResult.Failure(ProtocolError.UNSUPPORTED, "unsupported version")
            }

            val typeName = readString(json, FIELD_TYPE)
                ?: return EnvelopeResult.Failure(
                    ProtocolError.INVALID_MESSAGE,
                    "type is missing or not a string",
                )
            val type = MessageType.fromWire(typeName)
                ?: return EnvelopeResult.Failure(ProtocolError.UNSUPPORTED, "unknown type")

            val messageId = readString(json, FIELD_MESSAGE_ID)
                ?: return EnvelopeResult.Failure(
                    ProtocolError.INVALID_MESSAGE,
                    "messageId is missing or not a string",
                )
            if (!isUuidV4(messageId)) {
                return EnvelopeResult.Failure(
                    ProtocolError.INVALID_MESSAGE,
                    "messageId is not a UUID v4",
                )
            }

            val rawSessionId = json.opt(FIELD_SESSION_ID)
            val sessionId: String?
            if (rawSessionId == null || rawSessionId === JSONObject.NULL) {
                sessionId = null
            } else if (rawSessionId is String && isUuid(rawSessionId)) {
                sessionId = rawSessionId
            } else {
                return EnvelopeResult.Failure(
                    ProtocolError.INVALID_MESSAGE,
                    "sessionId is not a UUID",
                )
            }

            val timestampText = readString(json, FIELD_TIMESTAMP)
                ?: return EnvelopeResult.Failure(
                    ProtocolError.INVALID_MESSAGE,
                    "timestamp is missing or not a string",
                )
            val timestamp = Iso8601.parse(timestampText)
                ?: return EnvelopeResult.Failure(
                    ProtocolError.INVALID_MESSAGE,
                    "timestamp is not ISO-8601 UTC",
                )
            val skew = timestamp - nowEpochMillis
            val absoluteSkew = if (skew < 0) -skew else skew
            if (absoluteSkew > ProtocolConstants.CLOCK_SKEW_MS) {
                return EnvelopeResult.Failure(
                    ProtocolError.INVALID_MESSAGE,
                    "timestamp outside CLOCK_SKEW_MS",
                )
            }

            val rawPayload = json.opt(FIELD_PAYLOAD)
            if (rawPayload !is JSONObject) {
                return EnvelopeResult.Failure(
                    ProtocolError.INVALID_MESSAGE,
                    "payload is missing or not an object",
                )
            }

            return EnvelopeResult.Success(
                Envelope(
                    type = type,
                    messageId = messageId,
                    sessionId = sessionId,
                    timestampEpochMillis = timestamp,
                    payload = rawPayload,
                ),
            )
        }

        /** Strict: no coercion of JSON strings or floating point values into integers. */
        private fun readInt(json: JSONObject, field: String): Int? =
            when (val raw = json.opt(field)) {
                is Int -> raw
                is Long -> if (raw >= Int.MIN_VALUE.toLong() && raw <= Int.MAX_VALUE.toLong()) {
                    raw.toInt()
                } else {
                    null
                }

                else -> null
            }

        private fun readString(json: JSONObject, field: String): String? =
            when (val raw = json.opt(field)) {
                is String -> raw
                else -> null
            }

        fun isUuid(value: String): Boolean {
            if (value.length != 36) {
                return false
            }
            for (index in value.indices) {
                val character = value[index]
                val valid = when (index) {
                    8, 13, 18, 23 -> character == '-'
                    else -> isHexDigit(character)
                }
                if (!valid) {
                    return false
                }
            }
            return true
        }

        fun isUuidV4(value: String): Boolean {
            if (!isUuid(value)) {
                return false
            }
            if (value[14] != '4') {
                return false
            }
            return when (value[19]) {
                '8', '9', 'a', 'b', 'A', 'B' -> true
                else -> false
            }
        }

        private fun isHexDigit(character: Char): Boolean =
            character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
    }
}
