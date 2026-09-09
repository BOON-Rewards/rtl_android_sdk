package com.affinaloyalty.rtlsdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RTLBridgeTest {
    @Test
    fun `parses every supported web message`() {
        val open = RTLWebMessage.parse(
            """{"type":"openExternalUrl","URL":"https://example.com","forceExternalBrowser":false,"surface":"overlay","merchantName":"Shop"}"""
        )
        assertTrue(open is RTLWebMessage.OpenExternalUrl)
        open as RTLWebMessage.OpenExternalUrl
        assertEquals("https://example.com", open.url)
        assertEquals(RTLSurface.OVERLAY, open.surface)

        assertTrue(RTLWebMessage.parse("""{"type":"authFailed"}""") === RTLWebMessage.AuthFailed)
        assertTrue(RTLWebMessage.parse("""{"type":"userLogout"}""") === RTLWebMessage.UserLogout)
        assertTrue(RTLWebMessage.parse("""{"type":"appReady"}""") === RTLWebMessage.AppReady)
        assertTrue(
            RTLWebMessage.parse("""{"type":"sessionExpired"}""") ===
                RTLWebMessage.SessionExpired
        )
        assertTrue(
            RTLWebMessage.parse("""{"type":"requestLocationPermission"}""") ===
                RTLWebMessage.RequestLocationPermission
        )
        assertTrue(
            RTLWebMessage.parse("""{"type":"requestNativeCapabilities"}""") ===
                RTLWebMessage.RequestNativeCapabilities
        )

        val haptic = RTLWebMessage.parse(
            """{"type":"hapticPlay","payload":{"version":1,"events":[{"at":0,"type":"impact"}]}}"""
        )
        assertTrue(haptic is RTLWebMessage.HapticPlay)
        assertEquals(RTLHapticPattern.Event.Type.IMPACT, (haptic as RTLWebMessage.HapticPlay).pattern.events.single().type)
    }

    @Test
    fun `ignores unknown messages`() {
        assertNull(RTLWebMessage.parse("""{"type":"somethingElse"}"""))
    }

    @Test
    fun `foreground location requires a correlation id`() {
        assertEquals(
            RTLWebMessage.RequestLocation("location-1"),
            RTLWebMessage.parse("""{"type":"requestLocation","requestId":"location-1"}""")
        )
        listOf(null, "", "a".repeat(129), 42).forEach { value ->
            val message = JSONObject(mapOf("type" to "requestLocation", "requestId" to value))
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                RTLWebMessage.parse(message.toString())
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an open message without a surface`() {
        RTLWebMessage.parse(
            """{"type":"openExternalUrl","URL":"https://example.com","forceExternalBrowser":false}"""
        )
    }

    @Test
    fun `parses every URL surface without using the legacy flag for routing`() {
        RTLSurface.entries.forEach { surface ->
            val parsed = RTLWebMessage.parse(
                """{"type":"openExternalUrl","URL":"https://example.com","forceExternalBrowser":true,"surface":"${surface.wireValue}"}"""
            )
            assertTrue(parsed is RTLWebMessage.OpenExternalUrl)
            assertEquals(surface, (parsed as RTLWebMessage.OpenExternalUrl).surface)
        }
    }

    @Test(expected = RTLHapticPattern.ValidationException::class)
    fun `rejects an invalid haptic message`() {
        RTLWebMessage.parse(
            """{"type":"hapticPlay","payload":{"version":1,"events":[{"at":-1,"type":"impact"}]}}"""
        )
    }

    @Test
    fun `forwards future completion data inside the fixed envelope`() {
        val raw = serializeNativeMessage(
            RTLNativeMessageType.OVERLAY_COMPLETED,
            mapOf(
                "completionType" to "futureFlow",
                "data" to mapOf("futureField" to "a&b", "type" to "logoutRequested")
            )
        )
        val message = JSONObject(raw)
        assertEquals("overlayCompleted", message.getString("type"))
        assertEquals("futureFlow", message.getString("completionType"))
        assertEquals("a&b", message.getJSONObject("data").getString("futureField"))
        assertEquals("logoutRequested", message.getJSONObject("data").getString("type"))
    }

    @Test
    fun `serializes every supported native message with a flat type`() {
        RTLNativeMessageType.entries.forEach { type ->
            val serialized = serializeNativeMessage(type, mapOf("value" to "kept"))
            val message = JSONObject(serialized)
            assertEquals(type.wireName, message.getString("type"))
            assertEquals("kept", message.getString("value"))
        }
    }
}
