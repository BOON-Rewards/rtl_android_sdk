package com.affina.rtlsdk

import android.app.Activity
import android.content.Context
import android.location.Location

/**
 * Extension point for location module to register with RTLSdk.
 * This interface allows the location module to be optional -
 * clients who don't need geofencing can use rtl-sdk-core without location permissions.
 */
interface RTLLocationExtension {
    /**
     * Enable location features.
     * Called when RTLSdk.enableLocationFeatures() is invoked.
     */
    fun enable(sdk: RTLSdk, activity: Activity)

    /**
     * Disable location features.
     * Called when RTLSdk.disableLocationFeatures() is invoked.
     */
    fun disable()

    /**
     * Handle permission result from the activity.
     *
     * @param requestCode The request code
     * @param permissions The requested permissions
     * @param grantResults The grant results
     * @return true if the extension handled this request code, false otherwise
     */
    fun handlePermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean

    /**
     * Handle geofence enter event from broadcast receiver.
     *
     * @param storeId The ID of the store whose geofence was entered
     */
    fun handleGeofenceEnter(storeId: String)

    /**
     * Handle location permission request from webview.
     *
     * @param activity The activity to request permissions from (if needed)
     */
    fun handleLocationPermissionRequest(activity: Activity?)

    /**
     * Send current location permission status to webview.
     *
     * @param granted Whether permission is granted
     */
    fun sendLocationPermissionStatus(granted: Boolean)

    /**
     * Whether location features are currently enabled
     */
    val isEnabled: Boolean

    /**
     * Whether background location permission is granted
     */
    val hasPermission: Boolean

    /**
     * Current location, if available
     */
    val currentLocation: Location?

    /**
     * Set callback for when location permission changes
     */
    var onPermissionChange: ((granted: Boolean) -> Unit)?

    /**
     * Set callback for when location updates are received
     */
    var onLocationUpdate: ((location: Location) -> Unit)?

    /**
     * Set callback for when a geofence is entered
     */
    var onGeofenceEnter: ((store: RTLStore) -> Unit)?
}
