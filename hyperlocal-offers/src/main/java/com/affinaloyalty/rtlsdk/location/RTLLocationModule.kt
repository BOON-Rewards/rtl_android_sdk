package com.affinaloyalty.rtlsdk.location

import android.app.Activity
import android.content.Context
import android.location.Location
import com.affinaloyalty.rtlsdk.RTLLocationExtension
import com.affinaloyalty.rtlsdk.RTLSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.affinaloyalty.rtlsdk.RTLLog
import com.affinaloyalty.rtlsdk.RTLLogArea.LOCATION

/**
 * Location module that provides geofencing and location-based notification features.
 *
 * This module must be installed into RTLSdk before location features can be used:
 * ```
 * RTLLocationModule.install(RTLSdk.getInstance())
 * ```
 *
 * After installation, call `RTLSdk.getInstance().enableLocationFeatures(activity)` to
 * start location tracking and geofencing.
 */
object RTLLocationModule : RTLLocationExtension {

    private const val GEOFENCE_FETCH_DEBOUNCE_MS = 5000L

    private var sdk: RTLSdk? = null
    private var locationManager: RTLLocationManager? = null
    private var geofenceManager: RTLGeofenceManager? = null
    private var hyperlocalOffersService: RTLHyperlocalOffersService? = null
    private var notificationManager: RTLNotificationManager? = null
    private var context: Context? = null

    private var lastGeofenceFetchTime: Long = 0L

    override var onPermissionChange: ((granted: Boolean) -> Unit)? = null
    override var onLocationUpdate: ((location: Location) -> Unit)? = null

    /**
     * Install the location module into RTLSdk.
     * Must be called before `enableLocationFeatures()`.
     *
     * Usage:
     * ```
     * // After SDK initialization
     * RTLSdk.getInstance().initialize(baseUrl, urlScheme, activity)
     *
     * // Install location module
     * RTLLocationModule.install(RTLSdk.getInstance())
     *
     * // Now location features are available
     * RTLSdk.getInstance().enableLocationFeatures(activity)
     * ```
     *
     * @param sdk The RTLSdk instance to install into
     */
    @JvmStatic
    fun install(sdk: RTLSdk) {
        this.sdk = sdk
        sdk.locationExtension = this
        RTLLog.i(LOCATION) { "Location module installed" }
    }

    override fun enable(sdk: RTLSdk, activity: Activity) {
        this.sdk = sdk
        this.context = activity.applicationContext

        val baseUrl = sdk.currentBaseUrl

        if (baseUrl == null) {
            RTLLog.e(LOCATION) { "Cannot enable: the SDK is not initialized" }
            return
        }

        if (isEnabled) {
            RTLLog.w(LOCATION) { "Already enabled" }
            return
        }

        RTLLog.i(LOCATION) { "Enabling location features" }

        // Initialize managers
        hyperlocalOffersService = RTLHyperlocalOffersService(baseUrl, sdk.currentExternalChapterId)
        notificationManager = RTLNotificationManager(activity.applicationContext)
        geofenceManager = RTLGeofenceManager(activity.applicationContext)
        locationManager = RTLLocationManager(activity.applicationContext, sdk)

        // Set up location update handler
        locationManager?.onLocationUpdate = { location ->
            onLocationUpdate?.invoke(location)
            handleLocationUpdate(location)
        }

        // Set up permission change handler
        locationManager?.onPermissionChange = { granted ->
            onPermissionChange?.invoke(granted)
        }

        // Set up geofence enter handler
        geofenceManager?.onGeofenceEnter = { store ->
            notificationManager?.showNotification(store)
        }

        // Request permissions
        locationManager?.requestPermission(activity)

        // If webview is already ready, send current permission status
        if (sdk.webviewIsReady) {
            val permission = locationManager?.hasBackgroundPermission ?: false
            sdk.sendLocationPermissionStatus(permission)
        }
    }

    override fun disable() {
        RTLLog.i(LOCATION) { "Disabling location features" }
        locationManager?.stopMonitoring()
        geofenceManager?.stopMonitoring()
        locationManager = null
        geofenceManager = null
        hyperlocalOffersService = null
        notificationManager = null
    }

    override fun handlePermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        return when (requestCode) {
            RTLLocationManager.LOCATION_PERMISSION_REQUEST_CODE,
            RTLLocationManager.BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE,
            RTLLocationManager.NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                locationManager?.handlePermissionResult(requestCode, permissions, grantResults)
                true
            }
            else -> false
        }
    }

    override fun handleGeofenceEnter(storeId: String) {
        geofenceManager?.handleGeofenceEnter(storeId)
    }

    override fun handleLocationPermissionRequest(activity: Activity?) {
        val manager = locationManager
        if (manager == null) {
            RTLLog.d(LOCATION) { "Ignoring web permission request because the host has not enabled location features" }
            return
        }

        activity?.let { manager.requestPermission(it) }
    }

    override fun sendLocationPermissionStatus(granted: Boolean) {
        sdk?.sendLocationPermissionStatus(granted)
    }

    override val isEnabled: Boolean
        get() = locationManager != null

    override val hasPermission: Boolean
        get() = locationManager?.hasBackgroundPermission ?: false

    override val currentLocation: Location?
        get() = locationManager?.currentLocation

    private fun handleLocationUpdate(location: Location) {
        RTLLog.d(LOCATION) { "Location update received" }

        // Debounce to prevent duplicate API calls
        val now = System.currentTimeMillis()
        if (now - lastGeofenceFetchTime < GEOFENCE_FETCH_DEBOUNCE_MS) {
            RTLLog.d(LOCATION) { "Skipping a duplicate location update (debounced)" }
            return
        }
        lastGeofenceFetchTime = now

        CoroutineScope(Dispatchers.Main).launch {
            fetchAndUpdateGeofences(location)
        }
    }

    private suspend fun fetchAndUpdateGeofences(location: Location) {
        val service = hyperlocalOffersService ?: return

        RTLLog.d(LOCATION) { "Fetching hyperlocal offers for the current location" }

        try {
            val stores = service.fetchHyperlocalOffers(
                latitude = location.latitude,
                longitude = location.longitude
            )

            RTLLog.d(LOCATION) { "Fetched ${stores.size} hyperlocal offers" }

            withContext(Dispatchers.Main) {
                geofenceManager?.updateGeofences(stores)
            }
        } catch (e: Exception) {
            RTLLog.e(LOCATION, e) { "Failed to fetch hyperlocal offers" }
        }
    }
}
