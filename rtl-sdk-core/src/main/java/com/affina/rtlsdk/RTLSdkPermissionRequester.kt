package com.affina.rtlsdk

import android.app.Activity

/**
 * Allows wrappers to route permission requests through their host framework.
 *
 * React Native, for example, needs permission requests to go through
 * PermissionAwareActivity so results are delivered back to native modules.
 */
fun interface RTLSdkPermissionRequester {
    fun requestPermissions(
        activity: Activity,
        permissions: Array<String>,
        requestCode: Int
    )
}
