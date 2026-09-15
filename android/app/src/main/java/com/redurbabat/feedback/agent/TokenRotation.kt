package com.redurbabat.feedback.agent

import com.redurbabat.feedback.protocol.ProtocolError
import org.json.JSONObject

/**
 * What the device does with the server's answer to `POST /agent/token` (THREAT_MODEL 4.13).
 *
 * Split out of [DeviceAgentClient] because the wrong reading of any of these answers is expensive
 * and none of them is easy to produce against a live server: storing a token that is not one
 * leaves the device unable to connect at all, and treating "the pairing is over" as "try again"
 * turns a finished pairing into a reconnect loop that never stops.
 */
sealed interface TokenRotationOutcome {

    /** The token is not old enough yet. Nothing changes. */
    data object NotDue : TokenRotationOutcome

    /** A replacement arrived. It must be stored before anything else uses it. */
    data class Rotated(val deviceToken: String) : TokenRotationOutcome

    /** The server ended this pairing - by the owner, or because the chain was held twice. */
    data object Revoked : TokenRotationOutcome

    /**
     * The token is no longer accepted, without the device having been revoked.
     *
     * Kept apart from [Revoked] so the app does not tell the owner they revoked something they
     * did not. What it means in practice is the same: pair again.
     */
    data object Rejected : TokenRotationOutcome

    /** Nobody answered. Not a statement about the token - the backoff retries it. */
    data object Unreachable : TokenRotationOutcome

    /**
     * An answer arrived that this version cannot read.
     *
     * The existing token is untouched and stays in use: a server that answers something
     * unexpected is a reason to keep what works, not to throw away the only credential.
     */
    data object Malformed : TokenRotationOutcome
}

object TokenRotation {

    /**
     * A token is base64url and at least as long as the 32 bytes the server issues.
     *
     * Checked before anything is stored, because a stored non-token is indistinguishable from a
     * broken pairing afterwards - and the old token, which still works, would already be gone.
     */
    private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43,512}$")

    fun parse(httpStatus: Int, body: String?): TokenRotationOutcome = when (httpStatus) {
        200 -> parseSuccess(body)
        ProtocolError.DEVICE_REVOKED.httpStatus -> forbidden(body)
        ProtocolError.UNAUTHORIZED.httpStatus -> TokenRotationOutcome.Rejected
        // Anything else - a 500, a proxy error page, a rate limit - says nothing about the token.
        else -> TokenRotationOutcome.Unreachable
    }

    private fun parseSuccess(body: String?): TokenRotationOutcome {
        val json = runCatching { JSONObject(requireNotNull(body)) }.getOrNull()
            ?: return TokenRotationOutcome.Malformed
        if (!json.optBoolean("rotated", false)) {
            return TokenRotationOutcome.NotDue
        }
        val token = json.optString("deviceToken")
        if (!TOKEN_PATTERN.matches(token)) {
            return TokenRotationOutcome.Malformed
        }
        return TokenRotationOutcome.Rotated(token)
    }

    /**
     * 403 is shared by several codes. Only DEVICE_REVOKED ends the pairing; the others are the
     * server refusing this one request, which is not a reason to discard a registration.
     */
    private fun forbidden(body: String?): TokenRotationOutcome {
        val code = runCatching {
            JSONObject(requireNotNull(body)).getJSONObject("error").getString("code")
        }.getOrNull()
        return if (code == ProtocolError.DEVICE_REVOKED.code) {
            TokenRotationOutcome.Revoked
        } else {
            TokenRotationOutcome.Unreachable
        }
    }
}
