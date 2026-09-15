package com.redurbabat.feedback.security

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Whether the server on the other end is the server this device paired with.
 *
 * Until this existed, "which server" was answered by an address. The device proved itself and the
 * server proved nothing: the `deviceToken` goes out as a Bearer header in the very first request,
 * the WebSocket upgrade included. So whoever came to own the address owned the trust with it -
 * the attacker who slips a setup link in front of the owner (THREAT_MODEL 4.20), and the next
 * registrant of a domain that lapsed (4.21). A key seen at pairing cannot be inherited.
 *
 * Free of `android.*` so the decision can be tested rather than only reasoned about. The decision
 * is the whole value here: a verifier that answers "fine" too readily is invisible until the day
 * it matters.
 */
sealed interface ServerIdentityCheck {

    /** The answer was signed by the key this device stored when it paired. */
    data object Trusted : ServerIdentityCheck

    /**
     * The other end named a different key. Not a failure to be retried - the address now leads
     * somewhere else, and nothing further may be sent.
     */
    data object KeyChanged : ServerIdentityCheck

    /** The right key was named but the signature over this exact challenge does not hold. */
    data object BadSignature : ServerIdentityCheck

    /** The answer was not shaped like an answer. */
    data object Malformed : ServerIdentityCheck
}

object ServerIdentity {

    const val PAYLOAD_PREFIX = "feedback-server-identity-v1"

    /**
     * The payload the server signs, byte for byte as the server builds it.
     *
     * `deviceId` binds the proof to the device that asked, `nonce` to the one question it asked,
     * and the public key is in there so a signature can never be read as being about a different
     * key than the one it arrived with.
     */
    fun payload(
        deviceId: String,
        nonceBase64Url: String,
        publicKeyBase64: String,
        issuedAtEpochMillis: Long,
    ): String = listOf(
        PAYLOAD_PREFIX,
        deviceId,
        nonceBase64Url,
        publicKeyBase64,
        issuedAtEpochMillis.toString(),
    ).joinToString("\n")

    /**
     * Checks one answer against the pinned key.
     *
     * The order matters and is the point of the whole mechanism: the offered key is compared to
     * the pinned one FIRST, and the signature is then verified against the **pinned** key, never
     * against the offered one. Verifying against the key that came with the answer would always
     * succeed - an impostor signs its own key with its own key perfectly well.
     */
    fun check(
        pinnedPublicKeyBase64: String,
        deviceId: String,
        nonceBase64Url: String,
        offeredPublicKeyBase64: String,
        issuedAtEpochMillis: Long,
        signatureBase64Url: String,
    ): ServerIdentityCheck {
        if (pinnedPublicKeyBase64.isEmpty() || offeredPublicKeyBase64.isEmpty()) {
            return ServerIdentityCheck.Malformed
        }
        if (offeredPublicKeyBase64 != pinnedPublicKeyBase64) {
            return ServerIdentityCheck.KeyChanged
        }

        val key = runCatching {
            val der = CryptoUtils.Base64.decode(pinnedPublicKeyBase64)
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        }.getOrNull() ?: return ServerIdentityCheck.Malformed

        val signature = runCatching {
            CryptoUtils.Base64Url.decode(signatureBase64Url)
        }.getOrNull() ?: return ServerIdentityCheck.Malformed
        if (signature.isEmpty()) {
            return ServerIdentityCheck.Malformed
        }

        val payload = payload(
            deviceId = deviceId,
            nonceBase64Url = nonceBase64Url,
            publicKeyBase64 = offeredPublicKeyBase64,
            issuedAtEpochMillis = issuedAtEpochMillis,
        )
        val verified = runCatching {
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(key)
                update(payload.toByteArray(Charsets.UTF_8))
            }.verify(signature)
        }.getOrDefault(false)

        return if (verified) ServerIdentityCheck.Trusted else ServerIdentityCheck.BadSignature
    }
}
