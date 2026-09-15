package com.redurbabat.feedback.pairing

import com.redurbabat.feedback.network.ServerEndpoint
import java.net.URI
import java.net.URISyntaxException

/**
 * The setup link the owner taps to hand this app a server address:
 *
 * ```text
 * https://<host>[:port]/pair
 * ```
 *
 * The link carries nothing else. No ticket, no token, no parameters - it is a pointer at an origin
 * and nothing more, which is why the trailing `/pair` is the whole grammar.
 *
 * What this object does NOT decide is whether that origin may be used. A link is a string with no
 * binding whatsoever to whoever delivered it: a prepared link, an explicit intent from another app,
 * a swapped QR code all produce the same input here. The decision therefore does not live in the
 * parser; [com.redurbabat.feedback.ui.FeedbackController] accepts an origin only when it is
 * character-identical to the one the app was built with or to the one it is already registered
 * with. The parser's job is to make sure that whatever reaches that rule is a well-formed origin
 * and not something that two different parsers would read two different ways.
 *
 * Hence exactly one parser: [java.net.URI]. Adding `android.net.Uri` alongside it would create a
 * parser differential - two readings of one string, with the check running on one of them and the
 * use on the other.
 */
object SetupLink {
    /** The only path a setup link may carry. */
    const val PATH = "/pair"

    private const val PATH_WITH_TRAILING_SLASH = "$PATH/"

    /**
     * Strict parser. Returns the origin the link points at, or null for everything else.
     * Deny-by-default: no query, no fragment, no credentials, no other path, no address literal.
     */
    fun parseServerOrNull(value: String): ServerEndpoint? {
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return null
        }
        // An opaque URI ("https:pair") has no authority to compare and no path to check.
        if (uri.isOpaque) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return null
        }
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            return null
        }
        // The raw path, never the decoded one: "/%70air" decodes to "/pair" and must still be
        // refused, because it is not the path the server publishes.
        val path = uri.rawPath
        if (path != PATH && path != PATH_WITH_TRAILING_SLASH) {
            return null
        }
        val host = uri.host ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        // Rebuilt from the parsed parts rather than trimmed off the input, and handed to the one
        // place that knows what a usable server host is.
        return ServerEndpoint.parseOrNull("https://$host$port")
    }
}
