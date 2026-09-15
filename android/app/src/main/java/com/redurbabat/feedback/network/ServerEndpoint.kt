package com.redurbabat.feedback.network

import java.net.URI

/**
 * Validated server base URL. Production/device traffic is HTTPS-only; this prevents a caller from
 * accidentally sending pairing material or a device token over cleartext HTTP.
 *
 * The host has to be a registrable name, not merely "something java.net.URI accepted". THREAT_MODEL
 * 4.4 promises exactly that - "erzwingt https:// mit echtem Hostnamen - keine IP" - and for a long
 * time the code did not keep the promise: `https://192.168.1.10`, `https://[2001:db8::1]:8443`,
 * `https://example.com.` and `https://example.com:0` all passed. That mattered little while the
 * owner had to type the address by hand. It matters now that a setup link can offer one.
 */
data class ServerEndpoint private constructor(
    val baseUrl: String,
) {
    /** The `host[:port]` part, for showing the owner what they are about to trust. */
    val authority: String
        get() = baseUrl.removePrefix("https://")

    fun api(path: String): String {
        require(path.startsWith('/')) { "API path must start with /" }
        return "$baseUrl$path"
    }

    fun webSocket(path: String): String {
        val https = api(path)
        return "wss://${https.removePrefix("https://")}"
    }

    companion object {
        /** Longest host name DNS carries, in its textual form. */
        const val MAX_HOST_LENGTH = 253

        /** Longest single label between two dots. */
        const val MAX_LABEL_LENGTH = 63

        fun parse(value: String): ServerEndpoint {
            val trimmed = value.trim().removeSuffix("/")
            require(trimmed.isNotEmpty()) { "Server URL must not be empty" }
            val uri = URI(trimmed)
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "Feedback server must use HTTPS"
            }
            require(!uri.host.isNullOrBlank()) { "Feedback server host is missing" }
            require(uri.userInfo == null) { "Server URL must not contain credentials" }
            require(uri.query == null && uri.fragment == null) {
                "Server URL must not contain query or fragment"
            }
            require(uri.path.isNullOrEmpty() || uri.path == "/") {
                "Server URL must not contain a path"
            }
            val host = uri.host.lowercase()
            require(isRegistrableName(host)) {
                "Feedback server needs a real host name, not an address literal"
            }
            require(uri.port == -1 || uri.port in 1..65_535) { "Server port is out of range" }
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return ServerEndpoint("https://$host$port")
        }

        /** Same rules, but for input that is allowed to be wrong without it being an error. */
        fun parseOrNull(value: String): ServerEndpoint? = try {
            parse(value)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.net.URISyntaxException) {
            null
        }

        /**
         * A name that can actually be registered and can actually get a certificate: at least two
         * labels, no trailing dot, letters/digits/hyphen only.
         *
         * An IPv4 literal falls out of the last rule rather than needing one of its own - `1.2.3.4`
         * ends in an all-digit label, and no top level domain does. IPv6 literals never get here:
         * `[` is not an allowed character. Punycode (`xn--...`) passes deliberately - it is a real
         * name - and is never decoded for display, so a homograph stays visible as `xn--`.
         */
        private fun isRegistrableName(host: String): Boolean {
            if (host.length > MAX_HOST_LENGTH || host.endsWith('.')) {
                return false
            }
            val labels = host.split('.')
            if (labels.size < 2) {
                return false
            }
            if (labels.last().all(Char::isDigit)) {
                return false
            }
            return labels.all(::isLabel)
        }

        private fun isLabel(label: String): Boolean {
            if (label.isEmpty() || label.length > MAX_LABEL_LENGTH) {
                return false
            }
            if (label.first() == '-' || label.last() == '-') {
                return false
            }
            return label.all { character ->
                character in 'a'..'z' || character in '0'..'9' || character == '-'
            }
        }
    }
}
