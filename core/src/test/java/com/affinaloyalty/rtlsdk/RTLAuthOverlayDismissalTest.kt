package com.affinaloyalty.rtlsdk

import android.app.Activity
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RTLAuthOverlayDismissalTest {
    @Test
    fun `closing the tab reports dismissal once after returning to its host`() {
        val app = RecordingApplication()
        val host = Activity()
        val unrelated = Activity()
        var dismissals = 0
        val observer = RTLAuthOverlayDismissal(app, host) { dismissals++ }

        observer.onActivityResumed(host)
        assertEquals(0, dismissals)
        observer.onActivityPaused(host)
        observer.onActivityResumed(unrelated)
        assertEquals(0, dismissals)
        observer.onActivityResumed(host)
        observer.onActivityResumed(host)
        assertEquals(1, dismissals)
        assertNull(app.observer)
    }

    @Test
    fun `completion or logout cancels dismissal before the activity resumes`() {
        val app = RecordingApplication()
        val host = Activity()
        var dismissals = 0
        val observer = RTLAuthOverlayDismissal(app, host) { dismissals++ }

        observer.onActivityPaused(host)
        observer.stop()
        observer.onActivityResumed(host)
        observer.dismiss()
        assertEquals(0, dismissals)
        assertNull(app.observer)
    }

    @Test
    fun `failed launch releases the observer and reports dismissal immediately`() {
        val app = RecordingApplication()
        var dismissals = 0
        val observer = RTLAuthOverlayDismissal(app, Activity()) { dismissals++ }
        observer.dismiss()
        observer.dismiss()
        assertEquals(1, dismissals)
        assertNull(app.observer)
    }

    @Test
    fun `destroying the host does not notify a replacement web document`() {
        val app = RecordingApplication()
        val host = Activity()
        var dismissals = 0
        val observer = RTLAuthOverlayDismissal(app, host) { dismissals++ }
        observer.onActivityPaused(host)
        observer.onActivityDestroyed(host)
        observer.onActivityResumed(Activity())
        assertEquals(0, dismissals)
        assertNull(app.observer)
    }

    private class RecordingApplication : Application() {
        var observer: ActivityLifecycleCallbacks? = null
        override fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
            observer = callback
        }
        override fun unregisterActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
            if (observer === callback) observer = null
        }
    }
}
