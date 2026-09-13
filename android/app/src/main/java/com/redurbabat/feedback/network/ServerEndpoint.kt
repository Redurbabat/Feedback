package com.redurbabat.feedback.network

import java.net.URI

/**
 * Validated server base URL. Production/device traffic is HTTPS-only; this prevents a caller from
 * accidentally sending pairing material or a device token over cleartext HTTP.
 */
data class ServerEndpoint private constructor(
    val baseUrl: String,
) {
    fun api(path: String): String {
        require(path.startsWith('/')) { "API path must start with /" }
        return "$baseUrl$path"
    }

    fun webSocket(path: String): String {
        val https = api(path)
        return "wss://${https.removePrefix("https://")}" 
    }

    companion object {
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
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val normalized = "https://${uri.host.lowercase()}$port"
            return ServerEndpoint(normalized)
        }
    }
}
