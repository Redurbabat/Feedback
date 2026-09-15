package com.redurbabat.feedback.pairing

import com.redurbabat.feedback.network.ServerEndpoint

/** What a setup link is allowed to do, once [SetupLinkPolicy] has looked at it. */
sealed interface SetupLinkDecision {

    /**
     * The link named an origin the app already knows. It confirms; it does not introduce. Nothing
     * about the pairing itself follows from this - the owner still starts that.
     */
    data class Confirmed(val server: ServerEndpoint) : SetupLinkDecision

    /**
     * The link named some other origin. Both sides are carried out of here so the refusal can name
     * them: a refusal that does not say what was refused teaches the owner nothing.
     */
    data class Refused(
        val offered: ServerEndpoint,
        val trusted: List<ServerEndpoint>,
    ) : SetupLinkDecision

    /** Not an origin at all. Nothing to compare, nothing to name. */
    data object Unusable : SetupLinkDecision
}

/**
 * The one rule that makes setup links safe: **a link never introduces a server, it only confirms
 * one the app already knows.**
 *
 * An origin from a link is accepted exactly when it is character-identical to the origin this build
 * was made for, or to the origin of the registration the device already holds. Anything else is
 * refused, visibly.
 *
 * Why equality and not provenance: a link is a string with no binding of any kind to whoever
 * delivered it. A prepared link in a message, an explicit intent from another installed app aimed
 * at the exported entry activity, a swapped QR code on a printed sheet - all three arrive here as
 * the same kind of input, and no amount of checking where it "came from" can tell them apart. With
 * the equality rule none of that matters, because all three fail the same single test. The trust
 * anchor is the build, not the link.
 *
 * The comparison is always over the whole normalised origin - scheme, host and port - never over
 * the host alone. `https://feedback.example.com` and `https://feedback.example.com:8443` are
 * different origins, and so are `feedback.example.com` and `feedback.example.com.evil.example`.
 * Normalisation happens in [ServerEndpoint], on both sides, so that equality of strings means
 * equality of origins.
 *
 * This lives in `pairing` rather than next to the UI that shows the result, because it is a trust
 * decision.
 */
object SetupLinkPolicy {

    fun decide(
        offeredOrigin: String?,
        buildDefaultOrigin: String,
        registeredOrigin: String?,
    ): SetupLinkDecision {
        val offered = offeredOrigin?.let { ServerEndpoint.parseOrNull(it) }
            ?: return SetupLinkDecision.Unusable
        val trusted = trustedOrigins(buildDefaultOrigin, registeredOrigin)
        return if (trusted.any { it.baseUrl == offered.baseUrl }) {
            SetupLinkDecision.Confirmed(offered)
        } else {
            SetupLinkDecision.Refused(offered = offered, trusted = trusted)
        }
    }

    /**
     * The origins a link may be equal to, in the order the owner would expect to read them. Empty
     * is a legitimate answer: a build without a default that has never been paired knows no server
     * at all, and then every link is refused for want of anything to match.
     *
     * The registration is the second anchor so that a device paired by hand - before this build
     * carried a default, or with a server the build does not name - still recognises its own.
     */
    private fun trustedOrigins(
        buildDefaultOrigin: String,
        registeredOrigin: String?,
    ): List<ServerEndpoint> = listOfNotNull(
        ServerEndpoint.parseOrNull(buildDefaultOrigin),
        registeredOrigin?.let { ServerEndpoint.parseOrNull(it) },
    ).distinctBy { it.baseUrl }
}
