package com.affinaloyalty.rtlsdk.example

import android.app.Activity
import com.affinaloyalty.rtlsdk.RTLSdk
import com.affinaloyalty.rtlsdk.location.RTLLocationModule

/** Adds hyperlocal offers, geofencing, and nearby-offer notifications. */
object HyperlocalOffersExample {
    fun install(sdk: RTLSdk) {
        RTLLocationModule.install(sdk)
    }

    fun enable(sdk: RTLSdk, activity: Activity) {
        sdk.enableLocationFeatures(activity)
    }

    fun disable(sdk: RTLSdk) {
        sdk.disableLocationFeatures()
    }
}
