package com.redurbabat.feedback.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerEndpointTest {
    @Test
    fun `normalizes a https endpoint and builds api and websocket URLs`() {
        val endpoint = ServerEndpoint.parse("  https://Example.COM:8443/ ")
        assertEquals("https://example.com:8443", endpoint.baseUrl)
        assertEquals("example.com:8443", endpoint.authority)
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

    /**
     * THREAT_MODEL 4.4 promises "keine IP". Until this test existed the code did not keep that
     * promise: every one of these passed. That was survivable while the owner typed the address;
     * it is not once a setup link can offer one.
     */
    @Test
    fun `rejects address literals and names that cannot be registered`() {
        val refused = listOf(
            "https://93.184.216.34",
            "https://192.168.1.10",
            "https://1.2.3.4",
            "https://[2001:db8::1]:8443",
            "https://example.com.",
            "https://localhost",
            "https://-bad.example.com",
            "https://bad-.example.com",
            "https://a..b.com",
            "https://${"a".repeat(64)}.example.com",
        )
        for (value in refused) {
            assertNull(value, ServerEndpoint.parseOrNull(value))
        }
    }

    @Test
    fun `rejects a port outside the usable range`() {
        assertNull(ServerEndpoint.parseOrNull("https://example.com:0"))
        assertEquals("https://example.com:65535", ServerEndpoint.parse("https://example.com:65535").baseUrl)
    }

    /**
     * Punycode stays allowed - it is a real, registrable name - and is never decoded, so a
     * homograph reaches the owner's eye as `xn--...` rather than as the name it imitates.
     */
    @Test
    fun `keeps punycode in its ascii form`() {
        assertEquals("https://xn--pypal-4ve.com", ServerEndpoint.parse("https://xn--pypal-4ve.com").baseUrl)
    }

    @Test
    fun `parseOrNull answers null instead of throwing`() {
        assertNull(ServerEndpoint.parseOrNull(""))
        assertNull(ServerEndpoint.parseOrNull("nicht einmal eine url"))
        assertEquals("https://feedback.example.com", ServerEndpoint.parseOrNull("https://feedback.example.com")?.baseUrl)
    }
}
