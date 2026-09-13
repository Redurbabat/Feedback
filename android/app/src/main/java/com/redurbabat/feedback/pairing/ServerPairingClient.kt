package com.redurbabat.feedback.pairing

import com.redurbabat.feedback.network.JsonTransport
import com.redurbabat.feedback.protocol.ProtocolConstants
import com.redurbabat.feedback.security.CryptoUtils
import com.redurbabat.feedback.security.DeviceIdentityStore
import java.security.SecureRandom
import org.json.JSONObject

class ServerPairingException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class ServerPairingSession(
    val pairingId: String,
    val ticket: String,
    val deviceSecret: String,
    val displayCode: String,
    val qrPayload: String,
    val expiresAt: String,
    val pollIntervalMs: Long,
)

enum class ServerPairingStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    CONSUMED,
}

data class DeviceRegistration(
    val deviceToken: String,
    val serverDeviceId: String,
    val publicDeviceId: String,
    val name: String,
    val pairedAt: String,
    val grantedCapabilities: List<String>,
)

/** Implements protocol/PROTOCOL.md sections 4-6 for the Android side. */
class ServerPairingClient(
    private val transport: JsonTransport,
    private val identityStore: DeviceIdentityStore,
    private val clock: Clock = SystemWallClock,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    suspend fun start(metadata: DeviceMetadata): ServerPairingSession {
        require(metadata.platform == ProtocolConstants.PLATFORM_ANDROID) {
            "Only the Android platform is supported in protocol v1"
        }
        val identity = identityStore.loadOrCreate()
        val nonceBytes = ByteArray(ProtocolConstants.PAIRING_NONCE_BYTES).also(secureRandom::nextBytes)
        val nonce = CryptoUtils.Base64Url.encode(nonceBytes)
        val issuedAt = clock.nowEpochMillis()
        val canonical = PairingCanonicalPayload.start(
            deviceId = identity.deviceId,
            publicKeyBase64 = identity.publicKeyBase64,
            fingerprint = identity.fingerprint,
            deviceName = metadata.deviceName,
            platform = metadata.platform,
            osVersion = metadata.osVersion,
            sdkInt = metadata.sdkInt,
            appVersion = metadata.appVersion,
            nonceBase64Url = nonce,
            issuedAtEpochMillis = issuedAt,
        )
        val signature = CryptoUtils.Base64Url.encode(
            identityStore.sign(canonical.toByteArray(Charsets.UTF_8)),
        )

        val response = post(
            "/api/v1/pairing/start",
            JSONObject()
                .put("version", ProtocolConstants.PROTOCOL_VERSION)
                .put(
                    "device",
                    JSONObject()
                        .put("deviceId", identity.deviceId)
                        .put("publicKey", identity.publicKeyBase64)
                        .put("fingerprint", identity.fingerprint)
                        .put("deviceName", metadata.deviceName)
                        .put("platform", metadata.platform)
                        .put("osVersion", metadata.osVersion)
                        .put("sdkInt", metadata.sdkInt)
                        .put("appVersion", metadata.appVersion),
                )
                .put("nonce", nonce)
                .put("issuedAt", issuedAt)
                .put("signature", signature),
        )

        return parseStart(response)
    }

    suspend fun status(session: ServerPairingSession): ServerPairingStatus {
        val response = post(
            "/api/v1/pairing/${session.pairingId}/status",
            JSONObject().put("deviceSecret", session.deviceSecret),
        )
        val raw = requiredString(response, "status")
        return when (raw) {
            "pending" -> ServerPairingStatus.PENDING
            "approved" -> ServerPairingStatus.APPROVED
            "rejected" -> ServerPairingStatus.REJECTED
            "expired" -> ServerPairingStatus.EXPIRED
            "consumed" -> ServerPairingStatus.CONSUMED
            else -> throw ServerPairingException("Unknown pairing status")
        }
    }

    suspend fun claim(session: ServerPairingSession): DeviceRegistration {
        val identity = identityStore.loadOrCreate()
        val issuedAt = clock.nowEpochMillis()
        val canonical = PairingCanonicalPayload.claim(
            pairingId = session.pairingId,
            deviceId = identity.deviceId,
            deviceSecretBase64Url = session.deviceSecret,
            issuedAtEpochMillis = issuedAt,
        )
        val signature = CryptoUtils.Base64Url.encode(
            identityStore.sign(canonical.toByteArray(Charsets.UTF_8)),
        )
        val response = post(
            "/api/v1/pairing/${session.pairingId}/claim",
            JSONObject()
                .put("deviceSecret", session.deviceSecret)
                .put("issuedAt", issuedAt)
                .put("signature", signature),
        )
        return parseRegistration(response, identity.deviceId)
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject = try {
        transport.post(path, body)
    } catch (error: ServerPairingException) {
        throw error
    } catch (error: Exception) {
        throw ServerPairingException("Pairing request failed", error)
    }

    private fun parseStart(json: JSONObject): ServerPairingSession = try {
        val ticket = requiredString(json, "ticket")
        val deviceSecret = requiredString(json, "deviceSecret")
        val displayCode = requiredString(json, "displayCode")
        requireSecret(ticket, ProtocolConstants.TICKET_BYTES, "ticket")
        requireSecret(deviceSecret, ProtocolConstants.DEVICE_SECRET_BYTES, "deviceSecret")
        if (displayCode.length != ProtocolConstants.DISPLAY_CODE_DIGITS ||
            displayCode.any { it !in '0'..'9' }
        ) {
            throw ServerPairingException("Server returned an invalid display code")
        }
        val qrPayload = requiredString(json, "qrPayload")
        if (qrPayload != "feedback://pair?v=1&ticket=$ticket") {
            throw ServerPairingException("Server returned an unexpected QR payload")
        }
        val pollInterval = json.getLong("pollIntervalMs")
        if (pollInterval !in 500L..30_000L) {
            throw ServerPairingException("Server returned an unsafe polling interval")
        }
        ServerPairingSession(
            pairingId = requiredString(json, "pairingId"),
            ticket = ticket,
            deviceSecret = deviceSecret,
            displayCode = displayCode,
            qrPayload = qrPayload,
            expiresAt = requiredString(json, "expiresAt"),
            pollIntervalMs = pollInterval,
        )
    } catch (error: ServerPairingException) {
        throw error
    } catch (error: Exception) {
        throw ServerPairingException("Server returned an invalid pairing response", error)
    }

    private fun parseRegistration(json: JSONObject, expectedDeviceId: String): DeviceRegistration = try {
        val deviceToken = requiredString(json, "deviceToken")
        requireSecret(deviceToken, ProtocolConstants.DEVICE_SECRET_BYTES, "deviceToken")
        val device = json.getJSONObject("device")
        val publicDeviceId = requiredString(device, "deviceId")
        if (publicDeviceId != expectedDeviceId) {
            throw ServerPairingException("Server registration belongs to another device identity")
        }
        val capabilities = json.getJSONObject("capabilities").getJSONArray("granted")
        val granted = buildList {
            for (index in 0 until capabilities.length()) {
                val capability = capabilities.getString(index)
                if (capability !in ProtocolConstants.CAPABILITY_WIRE_NAMES) {
                    throw ServerPairingException("Server returned an unknown capability")
                }
                add(capability)
            }
        }
        DeviceRegistration(
            deviceToken = deviceToken,
            serverDeviceId = requiredString(device, "id"),
            publicDeviceId = publicDeviceId,
            name = requiredString(device, "name"),
            pairedAt = requiredString(device, "pairedAt"),
            grantedCapabilities = granted,
        )
    } catch (error: ServerPairingException) {
        throw error
    } catch (error: Exception) {
        throw ServerPairingException("Server returned an invalid registration", error)
    }

    private fun requireSecret(value: String, bytes: Int, name: String) {
        val decoded = runCatching { CryptoUtils.Base64Url.decode(value) }
            .getOrElse { throw ServerPairingException("Server returned invalid $name", it) }
        if (decoded.size != bytes) {
            throw ServerPairingException("Server returned invalid $name length")
        }
    }

    private fun requiredString(json: JSONObject, key: String): String {
        val value = json.getString(key)
        if (value.isBlank()) {
            throw ServerPairingException("Server response field $key is empty")
        }
        return value
    }
}
