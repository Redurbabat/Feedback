package com.redurbabat.feedback.pairing

import android.util.Base64
import com.redurbabat.feedback.security.DeviceIdentity
import com.redurbabat.feedback.security.DeviceIdentityStore
import java.security.SecureRandom

data class PairingInvitation(
    val code: String,
    val expiresAtEpochMillis: Long,
    val payloadBase64Url: String,
    val signatureBase64Url: String,
)

class PairingInvitationFactory(
    private val identityStore: DeviceIdentityStore,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun create(
        identity: DeviceIdentity,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): PairingInvitation {
        val expiresAt = nowEpochMillis + PAIRING_TTL_MILLIS
        val code = secureRandom.nextInt(1_000_000).toString().padStart(6, '0')
        val nonceBytes = ByteArray(18).also(secureRandom::nextBytes)
        val nonce = encodeUrlSafe(nonceBytes)

        val canonicalPayload = listOf(
            "feedback-pairing-v1",
            identity.deviceId,
            identity.publicKeyBase64,
            nonce,
            nowEpochMillis.toString(),
            expiresAt.toString(),
            code,
        ).joinToString("\n")

        val payloadBytes = canonicalPayload.toByteArray(Charsets.UTF_8)
        val signature = identityStore.sign(payloadBytes)

        return PairingInvitation(
            code = code,
            expiresAtEpochMillis = expiresAt,
            payloadBase64Url = encodeUrlSafe(payloadBytes),
            signatureBase64Url = encodeUrlSafe(signature),
        )
    }

    private fun encodeUrlSafe(bytes: ByteArray): String = Base64.encodeToString(
        bytes,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    companion object {
        const val PAIRING_TTL_MILLIS = 5 * 60 * 1000L
    }
}
