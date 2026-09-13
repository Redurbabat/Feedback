package com.redurbabat.feedback.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeTest {

    @Test
    fun parsesAValidRequestWithSessionId() {
        val result = Envelope.parse(frame(), NOW)
        val envelope = success(result)
        assertEquals(MessageType.SYSTEM_INFO_REQUEST, envelope.type)
        assertEquals(MESSAGE_ID, envelope.messageId)
        assertEquals(SESSION_ID, envelope.sessionId)
        assertEquals(NOW, envelope.timestampEpochMillis)
        assertEquals(1, envelope.version)
        assertEquals(0, envelope.payload.length())
        assertNull(envelope.sessionRequirementError())
    }

    @Test
    fun acceptsExplicitJsonNullSessionIdAndAMissingSessionId() {
        val explicitNull = success(Envelope.parse(frame(sessionIdJson = "null"), NOW))
        assertNull(explicitNull.sessionId)

        val missing = success(
            Envelope.parse(
                """
                {"version":1,"type":"agent.heartbeat","messageId":"$MESSAGE_ID",
                "timestamp":"${Iso8601.format(NOW)}","payload":{}}
                """.trimIndent(),
                NOW,
            ),
        )
        assertNull(missing.sessionId)
    }

    @Test
    fun privilegedMessageWithoutSessionIsSessionExpired() {
        val envelope = success(Envelope.parse(frame(sessionIdJson = "null"), NOW))
        assertEquals(ProtocolError.SESSION_EXPIRED, envelope.sessionRequirementError())
    }

    @Test
    fun acceptsTimestampsAtTheEdgeOfTheSkewWindow() {
        val early = NOW - ProtocolConstants.CLOCK_SKEW_MS
        val late = NOW + ProtocolConstants.CLOCK_SKEW_MS
        assertEquals(early, success(Envelope.parse(frame(timestampMillis = early), NOW)).timestampEpochMillis)
        assertEquals(late, success(Envelope.parse(frame(timestampMillis = late), NOW)).timestampEpochMillis)
    }

    @Test
    fun keepsThePayloadContents() {
        val frame = """
            {"version":1,"type":"agent.hello","messageId":"$MESSAGE_ID","sessionId":null,
            "timestamp":"${Iso8601.format(NOW)}",
            "payload":{"appVersion":"0.2.0","sdkInt":36,"grantedCapabilities":["system.info"]}}
        """.trimIndent()
        val envelope = success(Envelope.parse(frame, NOW))
        assertEquals("0.2.0", envelope.payload.getString("appVersion"))
        assertEquals(36, envelope.payload.getInt("sdkInt"))
        assertEquals(1, envelope.payload.getJSONArray("grantedCapabilities").length())
    }

    @Test
    fun serializeRoundTrips() {
        val payload = JSONObject()
        payload.put("serverTime", Iso8601.format(NOW))
        val original = Envelope.create(
            type = MessageType.AGENT_HEARTBEAT_ACK,
            messageId = MESSAGE_ID,
            sessionId = null,
            timestampEpochMillis = NOW,
            payload = payload,
        )
        val parsed = success(Envelope.parse(original.serialize(), NOW))
        assertEquals(original.type, parsed.type)
        assertEquals(original.messageId, parsed.messageId)
        assertNull(parsed.sessionId)
        assertEquals(original.timestampEpochMillis, parsed.timestampEpochMillis)
        assertEquals(Iso8601.format(NOW), parsed.payload.getString("serverTime"))
        assertTrue(original.serialize().contains("\"version\":1"))
    }

    @Test
    fun createRejectsANonV4MessageId() {
        var rejected = false
        try {
            Envelope.create(
                type = MessageType.AGENT_HELLO,
                messageId = UUID_V1,
                sessionId = null,
                timestampEpochMillis = NOW,
            )
        } catch (error: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun sessionIdMayBeAnyUuidVersion() {
        val envelope = success(Envelope.parse(frame(sessionIdJson = "\"$UUID_V1\""), NOW))
        assertEquals(UUID_V1, envelope.sessionId)
    }

    @Test
    fun rejectsMalformedJson() {
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse("{\"version\":1", NOW))
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse("[]", NOW))
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse("", NOW))
    }

    @Test
    fun rejectsUnsupportedVersion() {
        assertFailure(ProtocolError.UNSUPPORTED, Envelope.parse(frame(versionJson = "2"), NOW))
        assertFailure(ProtocolError.UNSUPPORTED, Envelope.parse(frame(versionJson = "0"), NOW))
    }

    @Test
    fun rejectsVersionThatIsNotAnInteger() {
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(frame(versionJson = "\"1\""), NOW))
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(frame(versionJson = "1.0"), NOW))
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(frame(versionJson = "null"), NOW))
    }

    @Test
    fun rejectsUnknownType() {
        assertFailure(ProtocolError.UNSUPPORTED, Envelope.parse(frame(type = "shell.exec"), NOW))
        assertFailure(ProtocolError.UNSUPPORTED, Envelope.parse(frame(type = "System.Info.Request"), NOW))
    }

    @Test
    fun rejectsMissingOrMistypedFields() {
        val withoutMessageId = """
            {"version":1,"type":"agent.heartbeat","sessionId":null,
            "timestamp":"${Iso8601.format(NOW)}","payload":{}}
        """.trimIndent()
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(withoutMessageId, NOW))

        val withoutTimestamp = """
            {"version":1,"type":"agent.heartbeat","messageId":"$MESSAGE_ID","payload":{}}
        """.trimIndent()
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(withoutTimestamp, NOW))

        val withoutPayload = """
            {"version":1,"type":"agent.heartbeat","messageId":"$MESSAGE_ID",
            "timestamp":"${Iso8601.format(NOW)}"}
        """.trimIndent()
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(withoutPayload, NOW))

        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(frame(payloadJson = "[]"), NOW))
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(frame(payloadJson = "\"x\""), NOW))
    }

    @Test
    fun rejectsInvalidMessageIds() {
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(frame(messageId = "not-a-uuid"), NOW))
        assertFailure(
            ProtocolError.INVALID_MESSAGE,
            Envelope.parse(frame(messageId = "3f2504e04f8941d39a0c0305e82c3301"), NOW),
        )
        // Correct shape but UUID version 1 instead of 4.
        assertFailure(
            ProtocolError.INVALID_MESSAGE,
            Envelope.parse(frame(messageId = UUID_V1), NOW),
        )
        // Correct shape and version but a reserved variant nibble.
        assertFailure(
            ProtocolError.INVALID_MESSAGE,
            Envelope.parse(frame(messageId = "3f2504e0-4f89-41d3-ca0c-0305e82c3301"), NOW),
        )
    }

    @Test
    fun rejectsInvalidSessionIds() {
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(frame(sessionIdJson = "\"42\""), NOW))
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(frame(sessionIdJson = "17"), NOW))
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(frame(sessionIdJson = "false"), NOW))
    }

    @Test
    fun rejectsTimestampsOutsideTheSkewWindow() {
        val tooOld = NOW - ProtocolConstants.CLOCK_SKEW_MS - 1L
        val tooNew = NOW + ProtocolConstants.CLOCK_SKEW_MS + 1L
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(frame(timestampMillis = tooOld), NOW))
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(frame(timestampMillis = tooNew), NOW))
    }

    @Test
    fun rejectsTimestampsThatAreNotIso8601Utc() {
        assertFailure(
            ProtocolError.INVALID_MESSAGE,
            Envelope.parse(frame(timestampJson = "\"2025-09-13T10:40:00Z\""), NOW),
        )
        assertFailure(
            ProtocolError.INVALID_MESSAGE,
            Envelope.parse(frame(timestampJson = "1757760000000"), NOW),
        )
    }

    @Test
    fun rejectsFramesLargerThanTheFrameLimit() {
        val filler = StringBuilder()
        while (filler.length <= ProtocolConstants.MAX_FRAME_BYTES) {
            filler.append('a')
        }
        val oversized = frame(payloadJson = "{\"blob\":\"$filler\"}")
        assertTrue(oversized.toByteArray(Charsets.UTF_8).size > ProtocolConstants.MAX_FRAME_BYTES)
        assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(oversized, NOW))
    }

    @Test
    fun serializeRefusesToEmitAnOversizedFrame() {
        val filler = StringBuilder()
        while (filler.length <= ProtocolConstants.MAX_FRAME_BYTES) {
            filler.append('a')
        }
        val payload = JSONObject()
        payload.put("blob", filler.toString())
        val envelope = Envelope.create(
            type = MessageType.SYSTEM_INFO_RESPONSE,
            messageId = MESSAGE_ID,
            sessionId = SESSION_ID,
            timestampEpochMillis = NOW,
            payload = payload,
        )
        var refused = false
        try {
            envelope.serialize()
        } catch (error: IllegalStateException) {
            refused = true
        }
        assertTrue(refused)
    }

    private fun frame(
        versionJson: String = "1",
        type: String = "system.info.request",
        messageId: String = MESSAGE_ID,
        sessionIdJson: String = "\"$SESSION_ID\"",
        timestampMillis: Long = NOW,
        timestampJson: String = "\"${Iso8601.format(timestampMillis)}\"",
        payloadJson: String = "{}",
    ): String = "{\"version\":$versionJson," +
        "\"type\":\"$type\"," +
        "\"messageId\":\"$messageId\"," +
        "\"sessionId\":$sessionIdJson," +
        "\"timestamp\":$timestampJson," +
        "\"payload\":$payloadJson}"

    // --------------------------------------------------------------- relatesTo

    @Test
    fun aResponseCarriesRelatesToThroughSerialisationAndBack() {
        val envelope = Envelope.create(
            type = MessageType.SYSTEM_INFO_RESPONSE,
            messageId = MESSAGE_ID,
            sessionId = SESSION_ID,
            timestampEpochMillis = NOW,
            payload = JSONObject(),
            relatesTo = OTHER_MESSAGE_ID,
        )
        val parsed = success(Envelope.parse(envelope.serialize(), NOW))
        assertEquals(OTHER_MESSAGE_ID, parsed.relatesTo)
    }

    @Test
    fun aRequestOmitsRelatesToEntirely() {
        val envelope = Envelope.create(
            type = MessageType.SYSTEM_INFO_REQUEST,
            messageId = MESSAGE_ID,
            sessionId = SESSION_ID,
            timestampEpochMillis = NOW,
        )
        // Absent, not null: the field only exists on answers.
        assertTrue(!envelope.serialize().contains("relatesTo"))
        assertNull(success(Envelope.parse(envelope.serialize(), NOW)).relatesTo)
    }

    @Test
    fun aMalformedRelatesToIsRejected() {
        // A correlation key that is not a v4 UUID cannot have been minted by us, so accepting it
        // would mean answering to an id we never issued.
        for (bad in listOf("\"$UUID_V1\"", "\"not-a-uuid\"", "42", "{}")) {
            val frame = "{\"version\":1," +
                "\"type\":\"system.info.response\"," +
                "\"messageId\":\"$MESSAGE_ID\"," +
                "\"sessionId\":\"$SESSION_ID\"," +
                "\"relatesTo\":$bad," +
                "\"timestamp\":\"${Iso8601.format(NOW)}\"," +
                "\"payload\":{}}"
            assertFailure(ProtocolError.INVALID_MESSAGE, Envelope.parse(frame, NOW))
        }
    }

    @Test
    fun anExplicitNullRelatesToParsesAsAbsent() {
        val frame = "{\"version\":1," +
            "\"type\":\"system.info.response\"," +
            "\"messageId\":\"$MESSAGE_ID\"," +
            "\"sessionId\":\"$SESSION_ID\"," +
            "\"relatesTo\":null," +
            "\"timestamp\":\"${Iso8601.format(NOW)}\"," +
            "\"payload\":{}}"
        assertNull(success(Envelope.parse(frame, NOW)).relatesTo)
    }

    private fun success(result: EnvelopeResult): Envelope {
        assertTrue("expected success but was $result", result is EnvelopeResult.Success)
        return (result as EnvelopeResult.Success).envelope
    }

    private fun assertFailure(expected: ProtocolError, result: EnvelopeResult) {
        assertTrue("expected failure but was $result", result is EnvelopeResult.Failure)
        assertEquals(expected, (result as EnvelopeResult.Failure).error)
    }

    companion object {
        private const val MESSAGE_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        private const val SESSION_ID = "b7c17eb2-23b5-4e87-a32b-29c9151cd353"
        private const val UUID_V1 = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        private const val OTHER_MESSAGE_ID = "9d5b1c42-8e77-4a11-b3f0-1c2d3e4f5a6b"
        private const val NOW = 1_757_760_000_000L
    }
}
