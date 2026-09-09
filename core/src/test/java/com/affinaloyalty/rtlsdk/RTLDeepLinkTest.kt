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
            host = "rtl-sdk",
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
            parseRTLDeepLink("example", "rtl-sdk", "/open", "example")
        )
        assertEquals(
            RTLDeepLinkRoute.Open("event-1", null),
            parseRTLDeepLink(
                "example",
                "rtl-sdk",
                "/open",
                "example",
                rtlEventId = "event-1"
            )
        )
        assertEquals(
            RTLDeepLinkRoute.Open("event-1", null),
            parseRTLDeepLink(
                "example",
                "rtl-sdk",
                "/open",
                "example",
                rtlEventId = "event-1",
                rtlRedirectUrl = " "
            )
        )
        assertTrue(
            parseRTLDeepLink("example", "rtl-sdk", "/callback", "example") ===
                RTLDeepLinkRoute.Callback
        )
        assertTrue(
            parseRTLDeepLink("EXAMPLE", "rtl-sdk", "/callback", "example") ===
                RTLDeepLinkRoute.Callback
        )
    }

    @Test
    fun `rejects legacy and unrelated routes`() {
        assertNull(parseRTLDeepLink("example", "rtlSdk", null, "example"))
        assertNull(parseRTLDeepLink("example", "rtl-callback", null, "example"))
        assertNull(parseRTLDeepLink("example", "rtl-sdk", "/unknown", "example"))
        assertNull(parseRTLDeepLink("other", "rtl-sdk", "/open", "example"))
    }
}
