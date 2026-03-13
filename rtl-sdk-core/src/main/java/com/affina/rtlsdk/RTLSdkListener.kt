package com.affina.rtlsdk

/**
 * Listener interface for receiving RTL SDK events
 */
interface RTLSdkListener {

    /**
     * Called when user authentication succeeds
     *
     * @param accessToken The access token from authentication
     * @param refreshToken The refresh token from authentication
     */
    fun onAuthenticated(accessToken: String, refreshToken: String) {}

    /**
     * Called when user logs out
     */
    fun onLogout() {}

    /**
     * Called when the RTL web app requests opening a URL
     *
     * @param url The URL to open
     * @param forceExternal If true, should open in external browser; otherwise can use in-app browser
     */
    fun onOpenUrl(url: String, forceExternal: Boolean) {}

    /**
     * Called when the RTL web app has finished loading and is ready
     */
    fun onReady() {}

    /**
     * Called when SDK needs a fresh token from the host app.
     * This is called on initial login and when token expires after 20 hours.
     *
     * @return JWT token string, or null if unavailable
     */
    suspend fun onNeedsToken(): String?

    // MARK: - Location Callbacks (Optional)

    /**
     * Optional callback for location permission changes.
     * Set this property to receive notifications when location permission status changes.
     */
    val onLocationPermissionChange: ((granted: Boolean) -> Unit)?
        get() = null

    /**
     * Optional callback for geofence entry.
     * Set this property to receive notifications when user enters a store geofence.
     */
    val onGeofenceEnter: ((store: RTLStore) -> Unit)?
        get() = null
}

/**
 * Adapter class with default implementations for RTLSdkListener
 */
open class RTLSdkListenerAdapter : RTLSdkListener {
    override suspend fun onNeedsToken(): String? = null

    // Location callbacks - override to provide custom implementations
    override val onLocationPermissionChange: ((granted: Boolean) -> Unit)? = null
    override val onGeofenceEnter: ((store: RTLStore) -> Unit)? = null
}
