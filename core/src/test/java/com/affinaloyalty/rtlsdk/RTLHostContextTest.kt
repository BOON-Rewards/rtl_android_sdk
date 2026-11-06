package com.affinaloyalty.rtlsdk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RTLHostContextTest {
    private val sdk = RTLSdk.getInstance()

    @After fun tearDown() { sdk.setHostContext(emptyMap()) }

    @Test fun `copies nested JSON values and replaces previous context`() {
        val nested = mutableMapOf<String, Any?>("label" to "before")
        val items = mutableListOf<Any?>(nested, 3, null)
        sdk.setHostContext(mapOf("handlesBottomInset" to true, "items" to items))
        nested["label"] = "after"
        items.clear()
        assertEquals(mapOf("handlesBottomInset" to true, "items" to listOf(mapOf("label" to "before"), 3, null)), sdk.hostContext)
        sdk.setHostContext(mapOf("other" to false))
        assertEquals(mapOf("other" to false), sdk.hostContext)
        sdk.setHostContext(emptyMap())
        assertTrue(sdk.hostContext.isEmpty())
    }

    @Test fun `rejects non-JSON values and cycles without replacing valid context`() {
        sdk.setHostContext(mapOf("handlesBottomInset" to true))
        val cycle = mutableListOf<Any?>()
        cycle.add(cycle)
        for (value in listOf(Any(), Double.NaN, Double.POSITIVE_INFINITY, mapOf(1 to true), cycle)) {
            assertThrows(IllegalArgumentException::class.java) { sdk.setHostContext(mapOf("invalid" to value)) }
            assertEquals(mapOf("handlesBottomInset" to true), sdk.hostContext)
        }
    }
}
