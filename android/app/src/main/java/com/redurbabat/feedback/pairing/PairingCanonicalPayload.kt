package com.redurbabat.feedback.pairing

import com.redurbabat.feedback.protocol.ProtocolConstants

/**
 * Canonical signature payloads from protocol/PROTOCOL.md section 4.
 *
 * A canonical payload is UTF-8, joined line by line with `\n` and carries no trailing newline.
 * A field must not contain `\n`; such a field is rejected here exactly as the server rejects it
 * with INVALID_MESSAGE. Pure logic, free of `android.*`.
 */
object PairingCanonicalPayload {

    const val START_PREFIX = "feedback-pairing-start-v1"
    const val CLAIM_PREFIX = "feedback-pairing-claim-v1"
    const val LOCAL_PREFIX = "feedback-pairing-v1"

    /** Section 4.1 - signed body of `POST /pairing/start`. */
    fun start(
        deviceId: String,
        publicKeyBase64: String,
        fingerprint: String,
        deviceName: String,
        platform: String,
        osVersion: String,
        sdkInt: Int,
        appVersion: String,
        nonceBase64Url: String,
        issuedAtEpochMillis: Long,
    ): String {
        require(deviceName.length <= ProtocolConstants.DEVICE_NAME_MAX) {
            "deviceName exceeds DEVICE_NAME_MAX"
        }
        require(publicKeyBase64.length <= ProtocolConstants.PUBLIC_KEY_MAX_BASE64) {
            "publicKey exceeds PUBLIC_KEY_MAX_BASE64"
        }
        require(sdkInt > 0) { "sdkInt must be positive" }
        return join(
            listOf(
                START_PREFIX,
                field("deviceId", deviceId),
                field("publicKeyBase64", publicKeyBase64),
                field("fingerprint", fingerprint),
                field("deviceName", deviceName),
                field("platform", platform),
                field("osVersion", osVersion),
                sdkInt.toString(),
                field("appVersion", appVersion),
                field("nonceBase64Url", nonceBase64Url),
                issuedAtEpochMillis.toString(),
            ),
        )
    }

    /** Section 4.2 - signed body of `POST /pairing/{pairingId}/claim`. */
    fun claim(
        pairingId: String,
        deviceId: String,
        deviceSecretBase64Url: String,
        issuedAtEpochMillis: Long,
    ): String = join(
        listOf(
            CLAIM_PREFIX,
            field("pairingId", pairingId),
            field("deviceId", deviceId),
            field("deviceSecretBase64Url", deviceSecretBase64Url),
            issuedAtEpochMillis.toString(),
        ),
    )

    /** Section 4.3 - local offline/diagnosis proof, not usable online. */
    fun local(
        deviceId: String,
        publicKeyBase64: String,
        nonceBase64Url: String,
        issuedAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
        sixDigitCode: String,
    ): String {
        require(sixDigitCode.length == ProtocolConstants.DISPLAY_CODE_DIGITS) {
            "code must have ${ProtocolConstants.DISPLAY_CODE_DIGITS} digits"
        }
        for (character in sixDigitCode) {
            require(character in '0'..'9') { "code must be numeric" }
        }
        return join(
            listOf(
                LOCAL_PREFIX,
                field("deviceId", deviceId),
                field("publicKeyBase64", publicKeyBase64),
                field("nonceBase64Url", nonceBase64Url),
                issuedAtEpochMillis.toString(),
                expiresAtEpochMillis.toString(),
                sixDigitCode,
            ),
        )
    }

    private fun join(lines: List<String>): String = lines.joinToString("\n")

    private fun field(name: String, value: String): String {
        require(value.isNotEmpty()) { "$name must not be empty" }
        require(value.indexOf('\n') < 0) { "$name must not contain a line break" }
        return value
    }
}
