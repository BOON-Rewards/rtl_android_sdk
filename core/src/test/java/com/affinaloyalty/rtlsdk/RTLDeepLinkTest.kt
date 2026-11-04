package com.affinaloyalty.rtlsdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RTLDeepLinkTest {
    @Test
    fun `validates app schemes without rewriting them`() {
        assertTrue(isValidAppScheme("client-app"))
        assertTrue(isValidAppScheme("Client.App+Debug"))

        listOf(
            "",
            " client-app",
            "client-app ",
            "client-app:",
            "1client-app",
            "client_app",
            "http",
            "HTTPS",
            "javascript",
            "data"
        ).forEach { scheme ->
            assertFalse("Expected '$scheme' to be rejected", isValidAppScheme(scheme))
        }
    }

    @Test
    fun `parses the open route and gives redirect precedence`() {
        val route = parseRTLDeepLink(
            scheme = "example",
            host = "rtl-callback",
            path = "/open",
            configuredScheme = "example",
            rtlEventId = "event-1",
            rtlRedirectUrl = "https://example.com/offers"
        )

        assertTrue(route is RTLDeepLinkRoute.Open)
        route as RTLDeepLinkRoute.Open
        assertNull(route.rtlEventId)
        assertEquals("https://example.com/offers", route.rtlRedirectUrl)
    }

    @Test
    fun `parses default event and callback routes`() {
        assertEquals(
            RTLDeepLinkRoute.Open(null, null),
            parseRTLDeepLink("example", "rtl-callback", "/open", "example")
        )
        assertEquals(
            RTLDeepLinkRoute.Open("event-1", null),
            parseRTLDeepLink(
                "example",
                "rtl-callback",
                "/open",
                "example",
                rtlEventId = "event-1"
            )
        )
        assertEquals(
            RTLDeepLinkRoute.Open("event-1", null),
            parseRTLDeepLink(
                "example",
                "rtl-callback",
                "/open",
                "example",
                rtlEventId = "event-1",
                rtlRedirectUrl = " "
            )
        )
        assertTrue(
            parseRTLDeepLink("example", "rtl-callback", "/callback", "example") ===
                RTLDeepLinkRoute.Callback
        )
        assertTrue(
            parseRTLDeepLink("EXAMPLE", "rtl-callback", "/callback", "example") ===
                RTLDeepLinkRoute.Callback
        )
    }

    @Test
    fun `accepts pathless callback aliases`() {
        listOf(null, "").forEach { path ->
            assertEquals(
                RTLDeepLinkRoute.Callback,
                parseRTLDeepLink("EXAMPLE", "RTL-CALLBACK", path, "example")
            )
        }
    }

    @Test
    fun `rejects unrelated routes`() {
        assertNull(parseRTLDeepLink("example", "rtl-callback", "/unknown", "example"))
        assertNull(parseRTLDeepLink("other", "rtl-callback", "/open", "example"))
        assertNull(parseRTLDeepLink("example", "rtl-callback", "/", "example"))
        assertNull(parseRTLDeepLink("example", "rtl-callback", "/open/extra", "example"))
        assertNull(parseRTLDeepLink("example", "rtl-callback.evil.example", "/open", "example"))
        assertNull(parseRTLDeepLink("example", "rtl-callback", "/open", null))
    }
}
