package com.redurbabat.feedback.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupLinkTest {

    @Test
    fun `reads the origin out of the documented link`() {
        assertEquals(
            "https://feedback.example.com",
            SetupLink.parseServerOrNull("https://feedback.example.com/pair")?.baseUrl,
        )
    }

    @Test
    fun `accepts the trailing slash form a web server may redirect to`() {
        assertEquals(
            "https://feedback.example.com",
            SetupLink.parseServerOrNull("https://feedback.example.com/pair/")?.baseUrl,
        )
    }

    @Test
    fun `keeps a port, because the origin is scheme and host and port`() {
        assertEquals(
            "https://feedback.example.com:8443",
            SetupLink.parseServerOrNull("https://feedback.example.com:8443/pair")?.baseUrl,
        )
        assertEquals(
            "https://feedback.example.com:8443",
            SetupLink.parseServerOrNull("https://feedback.example.com:8443/pair/")?.baseUrl,
        )
    }

    /**
     * Scheme and host are case insensitive by definition, so normalising them is not leniency. The
     * comparison that decides whether the origin may be used is character equality, which only
     * means something once both sides are normalised the same way.
     */
    @Test
    fun `normalises the parts that are case insensitive`() {
        assertEquals(
            "https://feedback.example.com",
            SetupLink.parseServerOrNull("HTTPS://Feedback.Example.COM/pair")?.baseUrl,
        )
    }

    @Test
    fun `refuses cleartext and every other scheme`() {
        val refused = listOf(
            "http://feedback.example.com/pair",
            "ftp://feedback.example.com/pair",
            "feedback://pair",
            "https:pair",
            "//feedback.example.com/pair",
            "/pair",
        )
        for (value in refused) {
            assertNull("must refuse: $value", SetupLink.parseServerOrNull(value))
        }
    }

    /**
     * The path is the whole grammar, so anything that is not exactly it is refused - including the
     * percent-encoded spelling, which decodes to `/pair` but is not the path the server publishes.
     * A prefix match would let `/pairing` and `/pair-xyz` in.
     */
    @Test
    fun `refuses every path that is not exactly the pair path`() {
        val refused = listOf(
            "https://feedback.example.com",
            "https://feedback.example.com/",
            "https://feedback.example.com/pairing",
            "https://feedback.example.com/pair-xyz",
            "https://feedback.example.com/pair/extra",
            "https://feedback.example.com/pair//",
            "https://feedback.example.com/Pair",
            "https://feedback.example.com/%70air",
        )
        for (value in refused) {
            assertNull("must refuse: $value", SetupLink.parseServerOrNull(value))
        }
    }

    @Test
    fun `refuses a query, a fragment or credentials`() {
        val refused = listOf(
            "https://feedback.example.com/pair?next=x",
            "https://feedback.example.com/pair?",
            "https://feedback.example.com/pair#top",
            "https://feedback.example.com/pair#",
            "https://owner:secret@feedback.example.com/pair",
            // Reads as the real host to a human and as evil.example to a parser.
            "https://feedback.example.com@evil.example/pair",
        )
        for (value in refused) {
            assertNull("must refuse: $value", SetupLink.parseServerOrNull(value))
        }
    }

    /** Delegated to ServerEndpoint, asserted here so the delegation cannot quietly disappear. */
    @Test
    fun `refuses address literals and hosts that cannot be registered`() {
        val refused = listOf(
            "https://192.168.1.10/pair",
            "https://93.184.216.34/pair",
            "https://[2001:db8::1]:8443/pair",
            "https://localhost/pair",
            "https://feedback.example.com./pair",
            "https://feedback.example.com:0/pair",
            "https://feedback.example.com:99999/pair",
            "https://-bad.example.com/pair",
            "https://bad-.example.com/pair",
            "https://a..b.example/pair",
        )
        for (value in refused) {
            assertNull("must refuse: $value", SetupLink.parseServerOrNull(value))
        }
    }

    @Test
    fun `refuses empty input, padded input and outright rubbish`() {
        val refused = listOf(
            "",
            "   ",
            " https://feedback.example.com/pair",
            "https://feedback.example.com/pair ",
            "not a url at all",
            "https://feedback.example.com‮/pair",
        )
        for (value in refused) {
            assertNull("must refuse: $value", SetupLink.parseServerOrNull(value))
        }
    }
}
