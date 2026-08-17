package com.affina.rtlsdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

data class RTLExperienceResult(
    val success: Boolean,
    val errorCode: String? = null
)

private enum class RTLExperienceError(val code: String) {
    TOKEN_UNAVAILABLE("token_unavailable"),
    WEBVIEW_NOT_CREATED("webview_not_created"),
    INVALID_TOKEN_FORWARD_URL("invalid_token_forward_url"),
    LOGIN_TIMEOUT("login_timeout"),
    REQUEST_CANCELLED("request_cancelled")
}

/**
 * Main SDK singleton for RTL webview integration
 */
class RTLSdk private constructor() {

    companion object {
        @Volatile
        private var instance: RTLSdk? = null

        /**
         * Get the shared singleton instance
         */
        fun getInstance(): RTLSdk {
            return instance ?: synchronized(this) {
                instance ?: RTLSdk().also { instance = it }
            }
        }

        private const val TAG = "RTLSdk"
        private const val LOGIN_TIMEOUT_MS = 30_000L
        private const val PREFS_NAME = "RTLSdkPrefs"
        private const val KEY_TOKEN_TIMESTAMP = "lastTokenTimestamp"
        private const val TOKEN_EXPIRY_MS = 20 * 60 * 60 * 1000L // 20 hours
    }

    // Configuration
    private var program: String? = null
    private var environment: RTLEnvironment? = null
    private var urlScheme: String? = null
    private var externalChapterId: String? = null
    internal var application: Application? = null
        private set
    private var isInitialized = false

    // State
    private var _isLoggedIn: Boolean? = null
    internal var webView: RTLWebView? = null
        private set
    private var currentActivityRef: WeakReference<Activity>? = null

    // Location Extension (provided by rtl-sdk-location module)
    /**
     * Location extension instance. Set by the location module when installed.
     * Use RTLLocationModule.install(RTLSdk.getInstance()) to register the location module.
     *
     * @suppress This is an internal API for use by rtl-sdk-location module only.
     */
    var locationExtension: RTLLocationExtension? = null

    /**
     * Optional hook for wrapper SDKs that need to own Android permission requests.
     */
    var permissionRequester: RTLSdkPermissionRequester? = null

    // Webview state for location features
    private var webviewAwaitingPermissionResponse = false

    /**
     * Whether the webview is ready to receive messages.
     *
     * @suppress This is an internal API for use by rtl-sdk-location module only.
     */
    var webviewIsReady = false
        private set

    // Async login
    private var loginContinuation: CancellableContinuation<RTLExperienceResult>? = null
    private var loginTimeoutJob: Job? = null

    // Token management
    private var sharedPrefs: SharedPreferences? = null

    private var lastTokenTimestamp: Long
        get() = sharedPrefs?.getLong(KEY_TOKEN_TIMESTAMP, 0L) ?: 0L
        set(value) {
            sharedPrefs?.edit()?.putLong(KEY_TOKEN_TIMESTAMP, value)?.apply()
        }

    private val isTokenExpired: Boolean
        get() {
            val lastTime = lastTokenTimestamp
            if (lastTime == 0L) return true
            return System.currentTimeMillis() - lastTime >= TOKEN_EXPIRY_MS
        }

    // Lifecycle observer for foreground detection
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // App came to foreground
            CoroutineScope(Dispatchers.Main).launch {
                checkAndRefreshTokenIfNeeded()
            }
        }
    }

    /**
     * Listener for SDK events
     */
    var listener: RTLSdkListener? = null

    fun initialize(
        program: String,
        environment: RTLEnvironment,
        urlScheme: String,
        context: Activity,
        listener: RTLSdkListener?,
        externalChapterId: String? = null
    ) {
        this.program = program
        this.environment = environment
        this.urlScheme = urlScheme
        this.listener = listener
        this.externalChapterId = externalChapterId
        this.application = context.application
        this.isInitialized = true
        this._isLoggedIn = false
        this.sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        this.currentActivityRef = WeakReference(context)
        setupLifecycleObserver()
    }

    private fun setupLifecycleObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    private suspend fun checkAndRefreshTokenIfNeeded() {
        if (!isTokenExpired) {
            Log.d(TAG, "Token still valid, no refresh needed")
            return
        }
        if (webView == null) {
            Log.d(TAG, "WebView not created, skipping token refresh")
            return
        }
        Log.d(TAG, "Token expired, requesting fresh token...")
        presentExperience()
    }

    /**
     * Returns the current login state
     *
     * @return true if logged in, false if not logged in, null if SDK not initialized
     */
    fun isLoggedIn(): Boolean? {
        if (!isInitialized) return null
        return _isLoggedIn
    }

    /**
     * Creates an embeddable webview for the RTL experience
     *
     * @param context The context to create the view in
     * @return RTLWebView instance that can be added to your view hierarchy
     */
    fun createWebView(context: Context): RTLWebView {
        if (!isInitialized) {
            throw IllegalStateException("RTLSdk not initialized. Call initialize() first.")
        }
        val webView = RTLWebView(context, this)
        webView.visibility = View.INVISIBLE
        this.webView = webView
        return webView
    }

    /**
     * Request token from listener and perform login.
     * Called on initial webview show and when token expires.
     *
     * @return Result containing success state or a snake_case error code
     */
    suspend fun presentExperience(
        rtlEventId: String? = null,
        rtlRedirectUrl: String? = null
    ): RTLExperienceResult {
        val token = listener?.onNeedsToken()
        if (token == null) {
            Log.d(TAG, "Token requested but listener returned null")
            return rtlExperienceFailure(RTLExperienceError.TOKEN_UNAVAILABLE)
        }
        return login(
            token = token,
            rtlEventId = rtlEventId,
            rtlRedirectUrl = rtlRedirectUrl
        )
    }

    /**
     * Async login that completes when the RTL app is ready or times out.
     *
     * @param token JWT token from host app's auth system
     * @param rtlEventId Optional RTL event identifier supplied by the host app
     * @param rtlRedirectUrl Optional redirect URL supplied by the host app
     * @return Result containing success state or a snake_case error code
     */
    suspend fun login(
        token: String,
        rtlEventId: String? = null,
        rtlRedirectUrl: String? = null
    ): RTLExperienceResult {
        val webView = this.webView ?: run {
            println("[RTLSdk] Error: WebView not created. Call createWebView() first.")
            return rtlExperienceFailure(RTLExperienceError.WEBVIEW_NOT_CREATED)
        }

        // Cancel any existing login attempt
        cancelPendingLogin()

        val url = buildTokenForwardUrl(
            token = token,
            rtlEventId = rtlEventId,
            rtlRedirectUrl = rtlRedirectUrl
        ) ?: run {
            println("[RTLSdk] Error: Failed to build token forward URL")
            return rtlExperienceFailure(RTLExperienceError.INVALID_TOKEN_FORWARD_URL)
        }

        return suspendCancellableCoroutine { continuation ->
            loginContinuation = continuation

            // Set up timeout
            loginTimeoutJob = CoroutineScope(Dispatchers.Main).launch {
                delay(LOGIN_TIMEOUT_MS)
                completeLogin(result = rtlExperienceFailure(RTLExperienceError.LOGIN_TIMEOUT))
            }

            // Load the URL on main thread
            Handler(Looper.getMainLooper()).post {
                webView.loadUrl(url)
            }

            continuation.invokeOnCancellation {
                loginTimeoutJob?.cancel()
                loginContinuation = null
            }
        }
    }

    /**
     * Triggers logout in the webview
     */
    fun logout() {
        val script = "window.rtlNative?.logout()"
        webView?.evaluateJavascript(script)
    }

    // MARK: - Location Features

    /**
     * Enable location-based notifications.
     * Requests location and notification permissions, sets up geofencing.
     *
     * Requires the rtl-sdk-location module to be installed. If the location module
     * is not installed, this method logs a warning and returns without effect.
     *
     * @param activity The activity to request permissions from
     */
    fun enableLocationFeatures(activity: Activity) {
        if (!isInitialized) {
            Log.w(TAG, "Cannot enable location features: SDK not initialized")
            return
        }

        val extension = locationExtension
        if (extension == null) {
            Log.w(TAG, "Location module not installed. Add rtl-sdk-location dependency and call RTLLocationModule.install()")
            return
        }

        if (extension.isEnabled) {
            Log.d(TAG, "Location features already enabled")
            return
        }

        Log.d(TAG, "Enabling location features...")
        currentActivityRef = WeakReference(activity)
        extension.enable(this, activity)

        // Set up callbacks
        extension.onPermissionChange = { granted ->
            listener?.onLocationPermissionChange?.invoke(granted)

            // Send to webview if it's waiting for a permission response
            if (webviewAwaitingPermissionResponse) {
                webviewAwaitingPermissionResponse = false
                sendLocationPermissionStatus(granted)
            }
        }

        extension.onLocationUpdate = { location ->
            // Send location update to webview if it's ready
            if (webviewIsReady) {
                geocodeAndSendLocationUpdate(location)
            }
        }

        extension.onGeofenceEnter = { store ->
            listener?.onGeofenceEnter?.invoke(store)
        }

        // If webview is already ready, send current permission status
        if (webviewIsReady) {
            val hasPermission = extension.hasPermission
            sendLocationPermissionStatus(hasPermission)
        }
    }

    /**
     * Disable location-based notifications
     */
    fun disableLocationFeatures() {
        Log.d(TAG, "Disabling location features...")
        locationExtension?.disable()
    }

    /**
     * Check if location features are enabled
     */
    val isLocationFeaturesEnabled: Boolean
        get() = locationExtension?.isEnabled ?: false

    /**
     * Check if background location permission is granted
     */
    val hasLocationPermission: Boolean
        get() = locationExtension?.hasPermission ?: false

    /**
     * Handle permission result from the activity.
     * Call this from your Activity's onRequestPermissionsResult.
     *
     * @param requestCode The request code
     * @param permissions The requested permissions
     * @param grantResults The grant results
     * @return true if the SDK handled this request code, false otherwise
     */
    fun handlePermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        return locationExtension?.handlePermissionResult(requestCode, permissions, grantResults) ?: false
    }

    /**
     * Request Android runtime permissions.
     *
     * Wrappers can provide [permissionRequester] to route this through their own
     * activity/delegate permission APIs. Native Android apps fall back to
     * ActivityCompat and should forward permission results to [handlePermissionResult].
     */
    fun requestPermissions(
        activity: Activity,
        permissions: Array<String>,
        requestCode: Int
    ) {
        permissionRequester?.requestPermissions(activity, permissions, requestCode)
            ?: ActivityCompat.requestPermissions(activity, permissions, requestCode)
    }

    // MARK: - Location Messaging (for location extension)

    /**
     * Send location permission status to webview (called when permission changes).
     *
     * @suppress This is an internal API for use by rtl-sdk-location module only.
     */
    fun sendLocationPermissionStatus(granted: Boolean) {
        val location = locationExtension?.currentLocation
        // Send immediate response first, then geocode
        sendLocationPermissionStatusImmediate(granted, location)

        // If we have permission and location, also send geocoded update
        if (granted && location != null) {
            geocodeAndSendLocationUpdate(location)
        }
    }

    /**
     * Send location permission status immediately without waiting for geocoding
     */
    private fun sendLocationPermissionStatusImmediate(granted: Boolean, location: Location?) {
        val message = mutableMapOf<String, Any>(
            "type" to "locationPermissionStatus",
            "granted" to granted
        )

        location?.let {
            message["lat"] = it.latitude
            message["long"] = it.longitude
        }

        Log.d(TAG, "Sending immediate location permission status: $message")
        webView?.postMessage(message)
    }

    /**
     * Geocode location and send locationUpdate message with full address data
     */
    internal fun geocodeAndSendLocationUpdate(location: Location) {
        CoroutineScope(Dispatchers.IO).launch {
            val message = mutableMapOf<String, Any>(
                "type" to "locationUpdate",
                "lat" to location.latitude,
                "long" to location.longitude
            )

            try {
                val context = application?.applicationContext
                if (context != null) {
                    val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)

                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        address.postalCode?.let { message["zipCode"] = it }
                        address.locality?.let { message["browsingCity"] = it }
                        address.adminArea?.let { message["browsingRegion"] = it }
                        address.countryName?.let { message["browsingCountry"] = it }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reverse geocoding error: ${e.message}")
            }

            withContext(Dispatchers.Main) {
                Log.d(TAG, "Sending location update: $message")
                webView?.postMessage(message)
            }
        }
    }

    /**
     * Handle location permission request from webview
     */
    internal fun handleLocationPermissionRequest(activity: Activity?) {
        val extension = locationExtension
        Log.d(TAG, "handleLocationPermissionRequest - locationExtension: ${extension != null}, isEnabled: ${extension?.isEnabled}")

        // ALWAYS send immediate response with current status
        val hasPermission = extension?.hasPermission ?: false
        val currentLocation = extension?.currentLocation

        Log.d(TAG, "Current permission status: $hasPermission, has location: ${currentLocation != null}")

        // Send immediate response
        sendLocationPermissionStatusImmediate(hasPermission, currentLocation)

        // If we have permission and location, also send geocoded update
        if (hasPermission && currentLocation != null) {
            geocodeAndSendLocationUpdate(currentLocation)
        }

        // If permission not granted, mark that webview is waiting for response
        // so we can notify it when permission changes
        if (!hasPermission) {
            webviewAwaitingPermissionResponse = true
        }

        // Now handle requesting permission if needed
        if (extension != null) {
            extension.handleLocationPermissionRequest(activity)
        } else if (activity != null) {
            // Location module not installed - log warning
            Log.w(TAG, "Location permission requested but location module not installed")
        }
    }

    /**
     * Handle geofence enter event from broadcast receiver.
     *
     * @suppress This is an internal API for use by rtl-sdk-location module only.
     */
    fun handleGeofenceEnter(storeId: String) {
        locationExtension?.handleGeofenceEnter(storeId)
    }

    // Internal methods

    internal fun handleUserAuthReceived(accessToken: String, refreshToken: String) {
        _isLoggedIn = true
        lastTokenTimestamp = System.currentTimeMillis()
        webView?.visibility = View.VISIBLE
        listener?.onAuthenticated(accessToken, refreshToken)
        completeLogin(result = rtlExperienceSuccess())
    }

    internal fun handleUserLogoutReceived() {
        _isLoggedIn = false
        webView?.visibility = View.INVISIBLE
        listener?.onLogout()
    }

    internal fun handleAppReady() {
        Log.d(TAG, "Received appReady from webview")
        webviewIsReady = true
        webView?.visibility = View.VISIBLE
        listener?.onReady()
        completeLogin(result = rtlExperienceSuccess())

        // Send current location permission status to webview now that it's ready
        val extension = locationExtension
        if (extension != null && extension.isEnabled) {
            val hasPermission = extension.hasPermission
            sendLocationPermissionStatus(hasPermission)
        }
    }

    internal fun handleOpenUrl(url: String, forceExternal: Boolean) {
        if (forceExternal) {
            // Open in external browser
            openExternalBrowser(url)
        } else {
            // Open in-app browser (Chrome Custom Tab)
            openInAppBrowser(url)
        }
        // Notify listener (informational - no action required)
        listener?.onOpenUrl(url, forceExternal)
    }

    /**
     * Open a URL in the system's default browser
     */
    private fun openExternalBrowser(url: String) {
        val context = currentActivityRef?.get() ?: application?.applicationContext
        if (context == null) {
            Log.w(TAG, "Cannot open external browser: no context")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open external browser: ${e.message}")
        }
    }

    /**
     * Open a URL in Chrome Custom Tabs (in-app browser)
     */
    private fun openInAppBrowser(url: String) {
        val activity = currentActivityRef?.get()
        if (activity == null) {
            Log.w(TAG, "Cannot open in-app browser: no activity reference, falling back to external browser")
            // Fallback to external browser
            openExternalBrowser(url)
            return
        }

        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(activity, Uri.parse(url))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Chrome Custom Tab: ${e.message}, falling back to external browser")
            // Fallback to external browser
            openExternalBrowser(url)
        }
    }

    // Private methods

    private fun cancelPendingLogin() {
        loginTimeoutJob?.cancel()
        loginTimeoutJob = null
        loginContinuation?.takeIf { it.isActive }?.resume(
            rtlExperienceFailure(RTLExperienceError.REQUEST_CANCELLED)
        )
        loginContinuation = null
    }

    private fun completeLogin(result: RTLExperienceResult) {
        loginTimeoutJob?.cancel()
        loginTimeoutJob = null
        loginContinuation?.takeIf { it.isActive }?.resume(result)
        loginContinuation = null
    }

    private fun buildTokenForwardUrl(
        token: String,
        rtlEventId: String?,
        rtlRedirectUrl: String?
    ): String? {
        val program = this.program ?: return null
        val environment = this.environment ?: return null
        val urlScheme = this.urlScheme ?: return null

        val domain = when (environment) {
            RTLEnvironment.DEVELOPMENT -> "$program-dev.staging.getboon.com"
            RTLEnvironment.STAGING -> "$program.staging.getboon.com"
            RTLEnvironment.PRODUCTION -> "$program.prod.getboon.com"
        }

        return buildString {
            append("https://")
            append(domain)
            append("/auth/token-forward")
            append("?token=")
            append(java.net.URLEncoder.encode(token, "UTF-8"))
            append("&isWrappedMobileApp=true")
            append("&embeddedProgramId=")
            append(java.net.URLEncoder.encode(program, "UTF-8"))
            append("&appScheme=")
            append(java.net.URLEncoder.encode(urlScheme, "UTF-8"))
            if (!rtlEventId.isNullOrEmpty()) {
                append("&rtlEventId=")
                append(java.net.URLEncoder.encode(rtlEventId, "UTF-8"))
            }
            if (!rtlRedirectUrl.isNullOrEmpty()) {
                append("&rtlRedirectUrl=")
                append(java.net.URLEncoder.encode(rtlRedirectUrl, "UTF-8"))
            }
        }
    }

    /**
     * Current program identifier.
     *
     * @suppress This is an internal API for use by rtl-sdk-location module only.
     */
    val currentProgram: String? get() = program

    /**
     * Current environment.
     *
     * @suppress This is an internal API for use by rtl-sdk-location module only.
     */
    val currentEnvironment: RTLEnvironment? get() = environment

    /**
     * Current external chapter ID.
     *
     * @suppress This is an internal API for use by rtl-sdk-location module only.
     */
    val currentExternalChapterId: String? get() = externalChapterId
}

private fun rtlExperienceSuccess(): RTLExperienceResult {
    return RTLExperienceResult(success = true)
}

private fun rtlExperienceFailure(error: RTLExperienceError): RTLExperienceResult {
    return RTLExperienceResult(success = false, errorCode = error.code)
}
