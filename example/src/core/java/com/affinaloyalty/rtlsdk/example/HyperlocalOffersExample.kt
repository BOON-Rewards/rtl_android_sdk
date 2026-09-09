package com.affinaloyalty.rtlsdk.example

import android.app.Activity
import com.affinaloyalty.rtlsdk.RTLSdk

/** Core-only example: no location artifact is present on this classpath. */
@Suppress("UNUSED_PARAMETER")
object HyperlocalOffersExample {
    fun install(sdk: RTLSdk) = Unit

    fun enable(sdk: RTLSdk, activity: Activity) = Unit

    fun disable(sdk: RTLSdk) = Unit

    fun handlePermissionResult(
        sdk: RTLSdk,
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) = Unit
}
