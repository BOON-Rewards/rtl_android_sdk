package com.affinaloyalty.rtlsdk

import android.app.Activity
import android.location.Location
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RTLSdkLocationAuthorizationTest {
    private val sdk = RTLSdk.getInstance()

    @After
    fun tearDown() {
        sdk.locationExtension = null
    }

    @Test
    fun `web permission request does not activate a disabled extension`() {
        val extension = FakeLocationExtension(isEnabled = false)
        sdk.locationExtension = extension

        sdk.handleLocationPermissionRequest(activity = null)

        assertFalse(extension.permissionRequested)
    }

    @Test
    fun `web permission request reaches an extension enabled by the host`() {
        val extension = FakeLocationExtension(isEnabled = true)
        sdk.locationExtension = extension

        sdk.handleLocationPermissionRequest(activity = null)

        assertTrue(extension.permissionRequested)
    }

    private class FakeLocationExtension(
        override val isEnabled: Boolean
    ) : RTLLocationExtension {
        var permissionRequested = false

        override fun enable(sdk: RTLSdk, activity: Activity) = Unit
        override fun disable() = Unit

        override fun handlePermissionResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
        ) = false

        override fun handleGeofenceEnter(storeId: String) = Unit

        override fun handleLocationPermissionRequest(activity: Activity?) {
            permissionRequested = true
        }

        override fun sendLocationPermissionStatus(granted: Boolean) = Unit

        override val hasPermission = false
        override val currentLocation: Location? = null
        override var onPermissionChange: ((granted: Boolean) -> Unit)? = null
        override var onLocationUpdate: ((location: Location) -> Unit)? = null
        override var onGeofenceEnter: ((store: RTLStore) -> Unit)? = null
    }
}
