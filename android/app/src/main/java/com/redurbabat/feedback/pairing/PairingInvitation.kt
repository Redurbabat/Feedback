package com.redurbabat.feedback.pairing

import com.redurbabat.feedback.protocol.ProtocolConstants
import com.redurbabat.feedback.security.CryptoUtils
import com.redurbabat.feedback.security.DeviceIdentity
import com.redurbabat.feedback.security.DeviceIdentityStore
import java.security.SecureRandom

/**
 * Locally signed pairing proof (`feedback-pairing-v1`, protocol/PROTOCOL.md section 4.4).
 *
 * This is the offline/diagnosis path and explicitly not an online usable ticket. Online pairing
 * runs through `pairing/start` and `pairing/{id}/claim`.
 */
data class PairingInvitation(
    val code: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val nonceBase64Url: String,
    val payloadBase64Url: String,
    val signatureBase64Url: String,
) {
    fun isExpired(nowEpochMillis: Long): Boolean = nowEpochMillis >= expiresAtEpochMillis
}

class PairingInvitationFactory(
    private val identityStore: DeviceIdentityStore,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val clock: Clock = SystemWallClock,
) {
    fun create(
        identity: DeviceIdentity,
        nowEpochMillis: Long = clock.nowEpochMillis(),
    ): PairingInvitation {
        val expiresAt = nowEpochMillis + PAIRING_TTL_MILLIS
        val code = secureRandom.nextInt(CODE_BOUND)
            .toString()
            .padStart(ProtocolConstants.DISPLAY_CODE_DIGITS, '0')
        val nonceBytes = ByteArray(ProtocolConstants.PAIRING_NONCE_BYTES)
        secureRandom.nextBytes(nonceBytes)
        val nonce = CryptoUtils.Base64Url.encode(nonceBytes)

        val canonicalPayload = PairingCanonicalPayload.local(
            deviceId = identity.deviceId,
            publicKeyBase64 = identity.publicKeyBase64,
            nonceBase64Url = nonce,
            issuedAtEpochMillis = nowEpochMillis,
            expiresAtEpochMillis = expiresAt,
            sixDigitCode = code,
        )

        val payloadBytes = canonicalPayload.toByteArray(Charsets.UTF_8)
        val signature = identityStore.sign(payloadBytes)

        return PairingInvitation(
            code = code,
            issuedAtEpochMillis = nowEpochMillis,
            expiresAtEpochMillis = expiresAt,
            nonceBase64Url = nonce,
            payloadBase64Url = CryptoUtils.Base64Url.encode(payloadBytes),
            signatureBase64Url = CryptoUtils.Base64Url.encode(signature),
        )
    }

    companion object {
        const val PAIRING_TTL_MILLIS = ProtocolConstants.PAIRING_TTL_MS
        private const val CODE_BOUND = 1_000_000
    }
}
