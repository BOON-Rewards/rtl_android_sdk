package com.affinaloyalty.rtlsdk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/** Observes the launching activity returning from its Custom Tab. */
internal class RTLAuthOverlayDismissal(
    private val application: Application,
    activity: Activity,
    private val onDismiss: () -> Unit
) : Application.ActivityLifecycleCallbacks {
    private val activityRef = WeakReference(activity)
    private var hasPaused = false
    private var active = true

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun stop() {
        if (!active) return
        active = false
        application.unregisterActivityLifecycleCallbacks(this)
        activityRef.clear()
    }

    fun dismiss() {
        if (!active) return
        stop()
        onDismiss()
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity === activityRef.get()) hasPaused = true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity === activityRef.get() && hasPaused) dismiss()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity === activityRef.get()) stop()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
