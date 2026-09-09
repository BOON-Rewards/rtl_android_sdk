package com.affinaloyalty.rtlsdk.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.affinaloyalty.rtlsdk.RTLSdk
import com.google.android.gms.location.*
import com.affinaloyalty.rtlsdk.RTLLog
import com.affinaloyalty.rtlsdk.RTLLogArea.LOCATION

/**
 * Manages location services for the RTL SDK
 */
internal class RTLLocationManager(
    private val context: Context,
    private val sdk: RTLSdk
) {
    companion object {
        const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        const val BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE = 1002
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1003
        private const val LOCATION_UPDATE_INTERVAL_MS = 3600000L // 1 hour
        private const val LOCATION_MIN_UPDATE_INTERVAL_MS = 300000L // 5 minutes
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    var onLocationUpdate: ((Location) -> Unit)? = null
    var onPermissionChange: ((Boolean) -> Unit)? = null

    var currentLocation: Location? = null
        private set

    // Track if we need to request background permission after foreground is granted
    private var pendingBackgroundRequest = false
    private var pendingActivity: Activity? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                currentLocation = location
                onLocationUpdate?.invoke(location)
            }
        }
    }

    /**
     * Check if the app has background location permission
     */
    val hasBackgroundPermission: Boolean
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }
        }

    /**
     * Check if the app has fine location permission
     */
    private val hasFineLocationPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Check if the app has notification permission (Android 13+)
     */
    private val hasNotificationPermission: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed pre-Android 13
        }

    /**
     * Request location permissions from the user
     *
     * @param activity The activity to request permissions from
     */
    fun requestPermission(activity: Activity) {
        pendingActivity = activity

        if (!hasFineLocationPermission) {
            // Step 1: Request foreground location first
            RTLLog.d(LOCATION) { "Requesting foreground location permission" }
            pendingBackgroundRequest = true

            sdk.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else if (!hasBackgroundPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Step 2: Already have foreground, request background
            requestBackgroundPermission(activity)
        } else {
            // Already have all location permissions
            RTLLog.d(LOCATION) { "Already have location permissions" }
            onPermissionChange?.invoke(hasBackgroundPermission)
            if (hasBackgroundPermission) {
                startMonitoring()
                // Request notification permission for Android 13+ if not granted
                if (!hasNotificationPermission) {
                    requestNotificationPermission(activity)
                }
            }
        }
    }

    /**
     * Request background location permission (Android 10+)
     */
    private fun requestBackgroundPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RTLLog.d(LOCATION) { "Requesting background location permission" }
            sdk.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    /**
     * Handle permission result from the activity
     *
     * @param requestCode The request code
     * @param permissions The requested permissions
     * @param grantResults The grant results
     */
    fun handlePermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                val foregroundGranted = grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED

                RTLLog.d(LOCATION) { "Foreground location permission: $foregroundGranted" }

                if (foregroundGranted && pendingBackgroundRequest) {
                    pendingBackgroundRequest = false

                    // On Android 10+, now request background permission separately
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundPermission) {
                        pendingActivity?.let { requestBackgroundPermission(it) }
                    } else {
                        // Android 9 or below, foreground permission is enough
                        onPermissionChange?.invoke(true)
                        sdk.sendLocationPermissionStatus(true)
                        startMonitoring()
                    }
                } else if (!foregroundGranted) {
                    pendingBackgroundRequest = false
                    onPermissionChange?.invoke(false)
                    sdk.sendLocationPermissionStatus(false)
                }
            }

            BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE -> {
                val backgroundGranted = hasBackgroundPermission
                RTLLog.d(LOCATION) { "Background location permission: $backgroundGranted" }

                onPermissionChange?.invoke(backgroundGranted)
                sdk.sendLocationPermissionStatus(backgroundGranted)

                if (backgroundGranted) {
                    startMonitoring()
                    // Request notification permission for Android 13+
                    if (!hasNotificationPermission) {
                        pendingActivity?.let { requestNotificationPermission(it) }
                    }
                }
            }

            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                val notificationGranted = hasNotificationPermission
                RTLLog.d(LOCATION) { "Notification permission: $notificationGranted" }
            }
        }
    }

    /**
     * Request notification permission (Android 13+)
     */
    private fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RTLLog.d(LOCATION) { "Requesting notification permission" }
            sdk.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    /**
     * Start monitoring location changes
     */
    @SuppressLint("MissingPermission")
    fun startMonitoring() {
        if (!hasFineLocationPermission) {
            RTLLog.e(LOCATION) { "Cannot start monitoring: missing fine location permission" }
            return
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            LOCATION_UPDATE_INTERVAL_MS
        ).apply {
            setMinUpdateIntervalMillis(LOCATION_MIN_UPDATE_INTERVAL_MS)
            setWaitForAccurateLocation(false)
        }.build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        // Get last known location immediately
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                currentLocation = it
                onLocationUpdate?.invoke(it)
            }
        }

        RTLLog.i(LOCATION) { "Started location monitoring" }
    }

    /**
     * Stop monitoring location changes
     */
    fun stopMonitoring() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        RTLLog.i(LOCATION) { "Stopped location monitoring" }
    }
}
