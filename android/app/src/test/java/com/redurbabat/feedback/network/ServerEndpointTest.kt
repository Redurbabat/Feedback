package com.redurbabat.feedback.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerEndpointTest {
    @Test
    fun `normalizes a https endpoint and builds api and websocket URLs`() {
        val endpoint = ServerEndpoint.parse("  https://Example.COM:8443/ ")
        assertEquals("https://example.com:8443", endpoint.baseUrl)
        assertEquals("https://example.com:8443/api/v1/pairing/start", endpoint.api("/api/v1/pairing/start"))
        assertEquals("wss://example.com:8443/api/v1/agent/ws", endpoint.webSocket("/api/v1/agent/ws"))
    }

    @Test
    fun `rejects cleartext credentials and path-bearing URLs`() {
        assertThrows(IllegalArgumentException::class.java) { ServerEndpoint.parse("http://example.test") }
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpoint.parse("https://user:pass@example.test")
        }
        assertThrows(IllegalArgumentException::class.java) { ServerEndpoint.parse("https://example.test/base") }
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpoint.parse("https://example.test?token=x")
        }
    }
}
