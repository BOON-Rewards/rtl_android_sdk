package com.affinaloyalty.rtlsdk

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RTLForegroundLocationActivityTest {
    @Test
    fun `resolves the displaying activity through nested wrappers`() {
        val displayingActivity = HostActivity()
        val webViewContext = WrappedContext(WrappedContext(displayingActivity))

        assertSame(displayingActivity, foregroundLocationActivity(webViewContext))
    }

    @Test
    fun `resolves the replacement activity when the context changes`() {
        val originalActivity = HostActivity()
        val webViewContext = WrappedContext(originalActivity)
        assertSame(originalActivity, foregroundLocationActivity(webViewContext))

        originalActivity.destroyed = true
        val replacementActivity = HostActivity()
        webViewContext.base = WrappedContext(replacementActivity)

        assertSame(replacementActivity, foregroundLocationActivity(webViewContext))
    }

    @Test
    fun `does not use a finishing or destroyed host`() {
        assertNull(foregroundLocationActivity(WrappedContext(HostActivity(finishing = true))))
        assertNull(foregroundLocationActivity(WrappedContext(HostActivity(destroyed = true))))
    }

    @Test
    fun `context without an activity does not fall back to a saved host`() {
        assertNull(foregroundLocationActivity(null))
        assertNull(foregroundLocationActivity(WrappedContext(null)))
        val selfWrappingContext = WrappedContext(null)
        selfWrappingContext.base = selfWrappingContext
        assertNull(foregroundLocationActivity(selfWrappingContext))
    }

    // Android's local-test stubs do not retain ContextWrapper's constructor
    // argument, so provide its public context behavior explicitly.
    private class WrappedContext(var base: Context?) : ContextWrapper(null) {
        override fun getBaseContext(): Context? = base
    }

    private class HostActivity(
        var finishing: Boolean = false,
        var destroyed: Boolean = false
    ) : Activity() {
        override fun isFinishing(): Boolean = finishing
        override fun isDestroyed(): Boolean = destroyed
    }
}
