package com.affina.rtlsdk.location

import android.app.Activity
import android.content.Context
import android.location.Location
import android.util.Log
import com.affina.rtlsdk.RTLLocationExtension
import com.affina.rtlsdk.RTLSdk
import com.affina.rtlsdk.RTLStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private const val TAG = "RTLLocationModule"
    private const val GEOFENCE_FETCH_DEBOUNCE_MS = 5000L

    private var sdk: RTLSdk? = null
    private var locationManager: RTLLocationManager? = null
    private var geofenceManager: RTLGeofenceManager? = null
    private var storeService: RTLStoreService? = null
    private var notificationManager: RTLNotificationManager? = null
    private var context: Context? = null

    private var lastGeofenceFetchTime: Long = 0L

    override var onPermissionChange: ((granted: Boolean) -> Unit)? = null
    override var onLocationUpdate: ((location: Location) -> Unit)? = null
    override var onGeofenceEnter: ((store: RTLStore) -> Unit)? = null

    /**
     * Install the location module into RTLSdk.
     * Must be called before `enableLocationFeatures()`.
     *
     * Usage:
     * ```
     * // After SDK initialization
     * RTLSdk.getInstance().initialize(program, environment, urlScheme, activity)
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
        Log.d(TAG, "Location module installed")
    }

    override fun enable(sdk: RTLSdk, activity: Activity) {
        this.sdk = sdk
        this.context = activity.applicationContext

        val program = sdk.currentProgram
        val environment = sdk.currentEnvironment

        if (program == null || environment == null) {
            Log.w(TAG, "Cannot enable: SDK not initialized")
            return
        }

        if (isEnabled) {
            Log.d(TAG, "Already enabled")
            return
        }

        Log.d(TAG, "Enabling location features...")

        // Initialize managers
        storeService = RTLStoreService(program, environment, sdk.currentExternalChapterId)
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
            onGeofenceEnter?.invoke(store)
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
        Log.d(TAG, "Disabling location features...")
        locationManager?.stopMonitoring()
        geofenceManager?.stopMonitoring()
        locationManager = null
        geofenceManager = null
        storeService = null
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
        val extension = locationManager
        if (extension == null) {
            // Not enabled yet - enable on first permission request
            activity?.let { enable(sdk ?: return, it) }
            return
        }

        activity?.let { extension.requestPermission(it) }
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
        Log.d(TAG, "📍 Location update received: lat=${location.latitude}, lng=${location.longitude}")

        // Debounce to prevent duplicate API calls
        val now = System.currentTimeMillis()
        if (now - lastGeofenceFetchTime < GEOFENCE_FETCH_DEBOUNCE_MS) {
            Log.d(TAG, "⏳ Skipping duplicate location update (debounced)")
            return
        }
        lastGeofenceFetchTime = now

        CoroutineScope(Dispatchers.Main).launch {
            fetchAndUpdateGeofences(location)
        }
    }

    private suspend fun fetchAndUpdateGeofences(location: Location) {
        val service = storeService ?: return

        Log.d(TAG, "🔍 Fetching nearby stores for location: ${location.latitude}, ${location.longitude}")

        try {
            val stores = service.fetchNearbyStores(
                latitude = location.latitude,
                longitude = location.longitude
            )

            Log.d(TAG, "🏪 Fetched ${stores.size} nearby stores:")
            stores.forEachIndexed { index, store ->
                Log.d(TAG, "   ${index + 1}. ${store.name} @ (${store.latitude}, ${store.longitude})")
            }

            withContext(Dispatchers.Main) {
                geofenceManager?.updateGeofences(stores)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to fetch nearby stores: ${e.message}")
        }
    }
}
