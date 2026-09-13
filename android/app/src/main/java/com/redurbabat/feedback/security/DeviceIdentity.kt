package com.redurbabat.feedback.security

/**
 * Cryptographic identity of this installation as defined in protocol/PROTOCOL.md section 3.
 *
 * The derivations are pure functions of the SPKI DER encoded public key so they can be verified
 * in JVM unit tests and re-checked by the server. No `android.*` import belongs in this file.
 */
data class DeviceIdentity(
    val deviceId: String,
    val fingerprint: String,
    val publicKeyBase64: String,
) {
    companion object {
        const val DEVICE_ID_PREFIX = "fb-"
        const val DEVICE_ID_HEX_LENGTH = 24
        const val FINGERPRINT_GROUP_LENGTH = 4

        /** `deviceId = "fb-" + hex(sha256(spkiDer))[0..23]` */
        fun deriveDeviceId(spkiDer: ByteArray): String {
            val hex = fingerprintHex(spkiDer)
            return DEVICE_ID_PREFIX + hex.substring(0, DEVICE_ID_HEX_LENGTH)
        }

        /** `fingerprint = hex(sha256(spkiDer))` in groups of four, joined with `:`. */
        fun formatFingerprint(spkiDer: ByteArray): String =
            fingerprintHex(spkiDer)
                .chunked(FINGERPRINT_GROUP_LENGTH)
                .joinToString(":")

        fun fingerprintHex(spkiDer: ByteArray): String {
            require(spkiDer.isNotEmpty()) { "SPKI public key must not be empty" }
            return CryptoUtils.toHex(CryptoUtils.sha256(spkiDer))
        }

        fun fromSpki(spkiDer: ByteArray): DeviceIdentity = DeviceIdentity(
            deviceId = deriveDeviceId(spkiDer),
            fingerprint = formatFingerprint(spkiDer),
            publicKeyBase64 = CryptoUtils.Base64.encode(spkiDer),
        )
    }
}
