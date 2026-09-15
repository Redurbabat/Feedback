package com.redurbabat.feedback.ui

import com.redurbabat.feedback.network.ServerEndpoint
import com.redurbabat.feedback.pairing.SetupLinkArrival
import com.redurbabat.feedback.pairing.SetupLinkDecision
import com.redurbabat.feedback.pairing.SetupLinkPolicy

/**
 * What one arrived setup link does to the visible state. There are exactly two cases, and neither
 * of them is silence: either the address field is filled in and says why, or nothing changes and
 * the owner is told why.
 */
sealed interface SetupLinkOutcome {

    /**
     * The confirmed origin fills in the address field. It starts nothing - the owner presses the
     * button - which is why this carries a notice and not a command.
     */
    data class FillsAddressField(
        val server: ServerEndpoint,
        val notice: String,
    ) : SetupLinkOutcome

    /** Nothing changes. [message] says what happened; [rejected] only picks the colour. */
    data class Announced(
        val message: String,
        val rejected: Boolean,
    ) : SetupLinkOutcome
}

/**
 * Turns an arrived setup link into the one line the owner reads.
 *
 * Deliberately free of `android.*` and of controller state, so that the path from "a link arrived"
 * to "something is on the screen" can be run in a unit test. That path is where a link used to
 * disappear: the branch for an unusable link existed, carried the right sentence, and could not be
 * reached, because an unparsable link was turned into a null that was indistinguishable from no
 * link at all. A total function over [SetupLinkArrival] cannot have that hole - every arrival maps
 * to an outcome, and every outcome is visible.
 *
 * Which origins may be used is not decided here. That is [SetupLinkPolicy]'s answer and it is a
 * trust decision; this object only chooses words for it.
 */
object SetupLinkPresentation {

    fun outcome(
        arrival: SetupLinkArrival,
        buildDefaultOrigin: String,
        registeredOrigin: String?,
        linksIgnored: Boolean,
        paired: Boolean,
        pairingInProgress: Boolean,
    ): SetupLinkOutcome {
        // The owner's own switch, so the link is not even compared - but it is still answered,
        // otherwise turning setup links off would look exactly like the app being broken.
        if (linksIgnored) {
            return SetupLinkOutcome.Announced(
                message = "Ein Einrichtungslink wurde ignoriert, weil du Einrichtungslinks " +
                    "abgeschaltet hast.",
                rejected = true,
            )
        }

        val decision = SetupLinkPolicy.decide(
            offeredOrigin = arrival.offeredOrigin,
            buildDefaultOrigin = buildDefaultOrigin,
            registeredOrigin = registeredOrigin,
        )
        return when (decision) {
            SetupLinkDecision.Unusable -> SetupLinkOutcome.Announced(
                message = "Einrichtungslink abgelehnt: er nennt keine verwendbare Serveradresse.",
                rejected = true,
            )

            is SetupLinkDecision.Refused -> SetupLinkOutcome.Announced(
                message = rejectionMessage(decision),
                rejected = true,
            )

            is SetupLinkDecision.Confirmed -> confirmation(
                server = decision.server,
                paired = paired,
                pairingInProgress = pairingInProgress,
            )
        }
    }

    /**
     * A host is shown only when every character of it is one a host name may contain. Bidi
     * overrides (U+202A..U+202E, U+2066..U+2069) are the reason this is not simply trusted:
     * they would let a refused address render as the trusted one, in a message whose whole point
     * is the difference between the two. ServerEndpoint does not let them through in the first
     * place - and the place that shows a name to a human still does not rely on that.
     *
     * Public because the same card names the server this build was made for, in the sentence that
     * explains what a setup link can and cannot do. One renderer for both, so the two lines cannot
     * disagree about what an address looks like.
     */
    fun displayAuthority(endpoint: ServerEndpoint): String {
        val authority = endpoint.authority
        return if (authority.isNotEmpty() && authority.all(::isDisplayableHostCharacter)) {
            authority
        } else {
            UNRENDERABLE_AUTHORITY
        }
    }

    private fun confirmation(
        server: ServerEndpoint,
        paired: Boolean,
        pairingInProgress: Boolean,
    ): SetupLinkOutcome = when {
        paired -> SetupLinkOutcome.Announced(
            message = "Der Einrichtungslink bestätigt den bereits gekoppelten Server " +
                "${displayAuthority(server)}. Es wurde nichts geändert.",
            rejected = false,
        )

        // A pairing that is already running owns the address it started with; overwriting the
        // field underneath it would describe a session that is not the one on screen.
        pairingInProgress -> SetupLinkOutcome.Announced(
            message = "Der Einrichtungslink wurde nicht übernommen, weil gerade eine " +
                "Kopplung läuft.",
            rejected = true,
        )

        else -> SetupLinkOutcome.FillsAddressField(
            server = server,
            notice = "Serveradresse aus dem Einrichtungslink übernommen: " +
                "${displayAuthority(server)}. Die Kopplung startest du selbst.",
        )
    }

    /** Names both sides. A refusal the owner cannot read is a silent one. */
    private fun rejectionMessage(refusal: SetupLinkDecision.Refused): String =
        if (refusal.trusted.isEmpty()) {
            "Einrichtungslink abgelehnt: er zeigt auf ${displayAuthority(refusal.offered)}. " +
                "Diese App wurde ohne feste Serveradresse gebaut und ist nicht gekoppelt - es " +
                "gibt nichts, womit der Link übereinstimmen könnte."
        } else {
            "Einrichtungslink abgelehnt: er zeigt auf ${displayAuthority(refusal.offered)}, " +
                "diese App vertraut " +
                "${refusal.trusted.joinToString(" bzw. ", transform = ::displayAuthority)}. " +
                "Die Serveradresse wurde nicht verändert."
        }

    private fun isDisplayableHostCharacter(character: Char): Boolean = when (character) {
        in 'a'..'z', in '0'..'9', '.', ':', '-' -> true
        else -> false
    }

    /** Stands in for a host that is not safe to render. Never shown next to a real one. */
    private const val UNRENDERABLE_AUTHORITY = "eine nicht darstellbare Adresse"
}
