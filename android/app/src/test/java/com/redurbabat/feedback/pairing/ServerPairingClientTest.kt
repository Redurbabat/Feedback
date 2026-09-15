package com.redurbabat.feedback.pairing

import com.redurbabat.feedback.network.JsonTransport
import com.redurbabat.feedback.protocol.ProtocolConstants
import com.redurbabat.feedback.security.CryptoUtils
import com.redurbabat.feedback.security.DeviceIdentityStore
import com.redurbabat.feedback.testutil.JvmDeviceKeyManager
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPairingClientTest {
    private val now = 1_757_760_000_000L
    private val clock = object : Clock {
        override fun nowEpochMillis(): Long = now
    }

    @Test
    fun `start status and claim use the signed protocol payloads`() = runTest {
        val keys = JvmDeviceKeyManager()
        val identityStore = DeviceIdentityStore(keys)
        val identity = identityStore.loadOrCreate()
        val ticket = token(1)
        val deviceSecret = token(2)
        val deviceToken = token(3)
        val transport = RecordingTransport { path, body ->
            when {
                path == "/api/v1/pairing/start" -> {
                    assertEquals(ProtocolConstants.PROTOCOL_VERSION, body.getInt("version"))
                    val device = body.getJSONObject("device")
                    val canonical = PairingCanonicalPayload.start(
                        deviceId = device.getString("deviceId"),
                        publicKeyBase64 = device.getString("publicKey"),
                        fingerprint = device.getString("fingerprint"),
                        deviceName = device.getString("deviceName"),
                        platform = device.getString("platform"),
                        osVersion = device.getString("osVersion"),
                        sdkInt = device.getInt("sdkInt"),
                        appVersion = device.getString("appVersion"),
                        nonceBase64Url = body.getString("nonce"),
                        issuedAtEpochMillis = body.getLong("issuedAt"),
                    )
                    assertTrue(
                        keys.verify(
                            canonical.toByteArray(Charsets.UTF_8),
                            CryptoUtils.Base64Url.decode(body.getString("signature")),
                        ),
                    )
                    JSONObject()
                        .put("pairingId", "0f3a4c5e-1111-4222-8333-444455556666")
                        .put("ticket", ticket)
                        .put("deviceSecret", deviceSecret)
                        .put("displayCode", "493821")
                        .put("qrPayload", "feedback://pair?v=1&ticket=$ticket")
                        .put("expiresAt", "2026-09-13T11:27:33.000Z")
                        .put("pollIntervalMs", 2000)
                }

                path.endsWith("/status") -> {
                    assertEquals(deviceSecret, body.getString("deviceSecret"))
                    JSONObject().put("status", "approved").put("expiresAt", "2026-09-13T11:27:33.000Z")
                }

                path.endsWith("/claim") -> {
                    assertEquals(deviceSecret, body.getString("deviceSecret"))
                    val pairingId = path.removePrefix("/api/v1/pairing/").removeSuffix("/claim")
                    val canonical = PairingCanonicalPayload.claim(
                        pairingId = pairingId,
                        deviceId = identity.deviceId,
                        deviceSecretBase64Url = deviceSecret,
                        issuedAtEpochMillis = body.getLong("issuedAt"),
                    )
                    assertTrue(
                        keys.verify(
                            canonical.toByteArray(Charsets.UTF_8),
                            CryptoUtils.Base64Url.decode(body.getString("signature")),
                        ),
                    )
                    JSONObject()
                        .put("deviceToken", deviceToken)
                        .put(
                            "device",
                            JSONObject()
                                .put("id", "9d16dce1-6a2b-4660-a04a-e3987451d78f")
                                .put("deviceId", identity.deviceId)
                                .put("name", "Test Phone")
                                .put("pairedAt", "2026-09-13T11:23:20.000Z"),
                        )
                        .put(
                            "capabilities",
                            JSONObject()
                                .put("granted", org.json.JSONArray().put("system.info"))
                                .put("requested", org.json.JSONArray()),
                        )
                        .put("serverTime", "2026-09-13T11:23:20.000Z")
                        .put("serverPublicKey", SERVER_PUBLIC_KEY)
                }

                else -> error("unexpected path $path")
            }
        }
        val client = ServerPairingClient(transport, identityStore, clock)
        val metadata = DeviceMetadata("Test Phone", "android", "16", 36, "0.2.0")

        val session = client.start(metadata)
        assertEquals("493821", session.displayCode)
        assertEquals(ServerPairingStatus.APPROVED, client.status(session))
        val registration = client.claim(session)

        assertEquals(deviceToken, registration.deviceToken)
        // Trust on first use: this is the only moment the device learns which server it belongs
        // to, so a claim that does not carry the key is refused rather than stored half-blind.
        assertEquals(SERVER_PUBLIC_KEY, registration.serverPublicKey)
        assertEquals(identity.deviceId, registration.publicDeviceId)
        assertEquals(listOf("system.info"), registration.grantedCapabilities)
        assertEquals(
            listOf(
                "/api/v1/pairing/start",
                "/api/v1/pairing/${session.pairingId}/status",
                "/api/v1/pairing/${session.pairingId}/claim",
            ),
            transport.paths,
        )
    }

    @Test(expected = ServerPairingException::class)
    fun `rejects a start response with a weak ticket`() = runTest {
        val keys = JvmDeviceKeyManager()
        val transport = RecordingTransport { _, _ ->
            JSONObject()
                .put("pairingId", "0f3a4c5e-1111-4222-8333-444455556666")
                .put("ticket", "short")
                .put("deviceSecret", token(2))
                .put("displayCode", "493821")
                .put("qrPayload", "feedback://pair?v=1&ticket=short")
                .put("expiresAt", "2026-09-13T11:27:33.000Z")
                .put("pollIntervalMs", 2000)
        }
        ServerPairingClient(transport, DeviceIdentityStore(keys), clock).start(
            DeviceMetadata("Test Phone", "android", "16", 36, "0.2.0"),
        )
    }

    private fun token(fill: Int): String =
        CryptoUtils.Base64Url.encode(ByteArray(32) { fill.toByte() })

    private class RecordingTransport(
        private val responder: suspend (String, JSONObject) -> JSONObject,
    ) : JsonTransport {
        val paths = mutableListOf<String>()

        override suspend fun post(path: String, body: JSONObject): JSONObject {
            paths += path
            return responder(path, body)
        }

        override suspend fun get(path: String, bearerToken: String?): JSONObject =
            error("GET not expected")
    }

    private companion object {
        /** The key from `protocol/fixtures/server-identity-v1.json`, so one value means one thing. */
        const val SERVER_PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmDNk66TBNQ6X9RFSuyGMsReTJ+ImZTpNk3cuT03lqM0bnGsRRXoGasOa6Bhl/w6qEB9Cpv1SJW6KG2+9JPk6zA=="
    }
}
