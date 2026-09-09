package com.affinaloyalty.rtlsdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.affinaloyalty.rtlsdk.RTLLogArea.CORE
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

data class RTLExperienceResult internal constructor(
    val success: Boolean,
    val errorCode: String? = null
)

private enum class RTLExperienceError(val code: String) {
    TOKEN_UNAVAILABLE("token_unavailable"),
    WEBVIEW_NOT_CREATED("webview_not_created"),
    INVALID_TOKEN_FORWARD_URL("invalid_token_forward_url"),
    AUTHENTICATION_FAILED("authentication_failed"),
    LOGIN_TIMEOUT("login_timeout"),
    REQUEST_CANCELLED("request_cancelled")
}

internal sealed interface RTLDeepLinkRoute {
    data class Open(
        val rtlEventId: String?,
        val rtlRedirectUrl: String?
    ) : RTLDeepLinkRoute

    data object Callback : RTLDeepLinkRoute
}

internal fun isValidAppScheme(value: String): Boolean =
    APP_SCHEME_PATTERN.matches(value) &&
        RESERVED_APP_SCHEMES.none { value.equals(it, ignoreCase = true) }

internal fun parseRTLDeepLink(
    scheme: String?,
    host: String?,
    path: String?,
    configuredScheme: String?,
    rtlEventId: String? = null,
    rtlRedirectUrl: String? = null
): RTLDeepLinkRoute? {
    if (configuredScheme == null ||
        !scheme.equals(configuredScheme, ignoreCase = true) ||
        !host.equals("rtl-sdk", ignoreCase = true)
    ) {
        return null
    }

    val redirect = rtlRedirectUrl?.takeIf { it.isNotBlank() }
    val eventId = rtlEventId?.takeIf { it.isNotBlank() }

    return when (path) {
        "/open" -> RTLDeepLinkRoute.Open(
            rtlEventId = eventId.takeIf { redirect == null },
            rtlRedirectUrl = redirect
        )
        "/callback" -> RTLDeepLinkRoute.Callback
        else -> null
    }
}

private val APP_SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")
private val RESERVED_APP_SCHEMES = setOf("http", "https", "javascript", "data")

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

        // androidx.browser CustomTabsIntent.EXTRA_ENABLE_EPHEMERAL_BROWSING,
        // inlined so the SDK stays on stable androidx.browser (the builder
        // method that sets it landed only in a 1.9 alpha).
        private const val EXTRA_ENABLE_EPHEMERAL_BROWSING =
            "androidx.browser.customtabs.extra.ENABLE_EPHEMERAL_BROWSING"
        private const val LOGIN_TIMEOUT_MS = 30_000L
    }

    // Configuration
    private var baseUrl: Uri? = null

    /**
     * When the current open began loading. Cleared when reported, so a later
     * navigation inside an already-open session is not counted as a new open.
     */
    private var openStartedAt: Long? = null
    private var urlScheme: String? = null
    private var externalChapterId: String? = null
    internal var application: Application? = null
        private set
    private var isInitialized = false

    // State
    // Held weakly. The host keeps the view alive by adding it to its own
    // hierarchy; the SDK must not outlive that. A strong reference here leaked
    // the host Activity (the WebView is built from its context) on every
    // createWebView. iOS holds it weakly for the same reason.
    private var webViewRef: WeakReference<RTLWebView>? = null
    internal var webView: RTLWebView?
        get() = webViewRef?.get()
        private set(value) { webViewRef = value?.let { WeakReference(it) } }
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
    private class AuthenticationAttempt {
        val result = CompletableDeferred<RTLExperienceResult>()
        var tokenJob: Job? = null
        var timeoutJob: Job? = null
        var awaitingWeb = false
    }

    private var activeAttempt: AuthenticationAttempt? = null
    private var isExperienceLoading = false

    // Persist the WebView session cookie before the process can be killed.
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            // The WebView holds cookies in memory and writes them to disk on
            // its own schedule. The session is a cookie now, so a process
            // killed before that write loses it and the user is signed out on
            // next launch with nothing to explain why. Backgrounding is the
            // last reliable moment to force it.
            runCatching { CookieManager.getInstance().flush() }
                .onFailure { RTLLog.w(CORE, it) { "Could not flush cookies" } }
        }
    }

    private var listener: RTLSdkListener? = null

    fun initialize(
        baseUrl: String,
        urlScheme: String,
        context: Activity,
        listener: RTLSdkListener,
        externalChapterId: String? = null
    ) {
        RTLLog.configureFor(context)
        val parsedBaseUrl = Uri.parse(baseUrl)
        val isValidBaseUrl =
            (parsedBaseUrl.scheme.equals("https", ignoreCase = true) ||
                parsedBaseUrl.scheme.equals("http", ignoreCase = true)) &&
                !parsedBaseUrl.host.isNullOrBlank()
        if (!isValidBaseUrl) {
            // Soft-fail rather than throw: a released SDK must not crash a host
            // on misconfiguration. The SDK stays uninitialized, so createWebView
            // and presentExperience fail cleanly. iOS behaves the same way.
            RTLLog.e(CORE) { "initialize() ignored: baseUrl must be a complete HTTP(S) URL" }
            return
        }
        if (!isValidAppScheme(urlScheme)) {
            RTLLog.e(CORE) {
                "initialize() ignored: urlScheme must be an app scheme without ':'"
            }
            return
        }

        cancelAuthentication()
        this.baseUrl = parsedBaseUrl
        this.urlScheme = urlScheme
        this.listener = listener
        this.externalChapterId = externalChapterId
        this.application = context.application
        this.isInitialized = true
        this.currentActivityRef = WeakReference(context)
        webviewIsReady = false
        webView?.visibility = View.INVISIBLE
        setupLifecycleObserver()
    }

    private fun setupLifecycleObserver() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
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
        webviewIsReady = false
        val webView = RTLWebView(context, this)
        webView.visibility = View.INVISIBLE
        this.webView = webView
        return webView
    }

    /**
     * Request token from listener and perform login.
     * Called for initial sign-in and explicit reauthentication.
     *
     * @return Result containing success state or a snake_case error code
     */
    suspend fun presentExperience(
        rtlEventId: String? = null,
        rtlRedirectUrl: String? = null
    ): RTLExperienceResult {
        return withContext(Dispatchers.Main.immediate) {
            val attempt = AuthenticationAttempt()
            startAuthentication(attempt, rtlEventId, rtlRedirectUrl)
            try {
                attempt.result.await()
            } finally {
                finishAuthentication(attempt, rtlExperienceFailure(RTLExperienceError.REQUEST_CANCELLED))
            }
        }
    }

    private fun startAuthentication(
        attempt: AuthenticationAttempt,
        rtlEventId: String? = null,
        rtlRedirectUrl: String? = null
    ) {
        activeAttempt?.let {
            finishAuthentication(it, rtlExperienceFailure(RTLExperienceError.REQUEST_CANCELLED), keepLoading = true)
        }
        activeAttempt = attempt
        webviewIsReady = false
        updateExperienceLoading(true)
        val tokenProvider = listener
        attempt.tokenJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val token = tokenProvider?.provideAuthToken()
                // Cancellation is cooperative: even a provider that ignores it
                // must not navigate after logout or a newer presentation.
                if (activeAttempt !== attempt) return@launch
                if (token == null) {
                    finishAuthentication(attempt, rtlExperienceFailure(RTLExperienceError.TOKEN_UNAVAILABLE))
                    return@launch
                }
                val view = webView
                if (view == null) {
                    finishAuthentication(attempt, rtlExperienceFailure(RTLExperienceError.WEBVIEW_NOT_CREATED))
                    return@launch
                }
                val url = buildTokenForwardUrl(token, rtlEventId, rtlRedirectUrl)
                if (url == null) {
                    finishAuthentication(attempt, rtlExperienceFailure(RTLExperienceError.INVALID_TOKEN_FORWARD_URL))
                    return@launch
                }
                attempt.awaitingWeb = true
                attempt.timeoutJob = CoroutineScope(Dispatchers.Main.immediate).launch {
                    delay(LOGIN_TIMEOUT_MS)
                    finishAuthentication(attempt, rtlExperienceFailure(RTLExperienceError.LOGIN_TIMEOUT))
                }
                view.loadUrl(url)
            } catch (error: CancellationException) {
                finishAuthentication(attempt, rtlExperienceFailure(RTLExperienceError.REQUEST_CANCELLED))
            } catch (error: Exception) {
                RTLLog.e(CORE, error) { "Authentication token request failed" }
                finishAuthentication(attempt, rtlExperienceFailure(RTLExperienceError.TOKEN_UNAVAILABLE))
            }
        }
    }

    /**
     * Triggers logout in the webview
     */
    fun logout() {
        cancelAuthentication()
        webView?.sendToWeb(RTLNativeMessageType.LOGOUT_REQUESTED)
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
            RTLLog.e(CORE) { "Cannot enable location features: the SDK is not initialized" }
            return
        }

        val extension = locationExtension
        if (extension == null) {
            RTLLog.w(CORE) { "Location module not installed. Add the rtl-sdk-location dependency and call RTLLocationModule.install()" }
            return
        }

        if (extension.isEnabled) {
            RTLLog.w(CORE) { "Location features already enabled" }
            return
        }

        RTLLog.i(CORE) { "Enabling location features" }
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
        RTLLog.i(CORE) { "Disabling location features" }
        locationExtension?.disable()
    }

    /**
     * Check if location features are enabled
     */
    internal val isLocationFeaturesEnabled: Boolean
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
        val message = mutableMapOf<String, Any>("granted" to granted)

        location?.let {
            message["lat"] = it.latitude
            message["long"] = it.longitude
        }

        RTLLog.i(CORE) { "Sending location permission status" }
        webView?.sendToWeb(RTLNativeMessageType.LOCATION_PERMISSION_STATUS, message)
    }

    /**
     * Geocode location and send locationUpdate message with full address data
     */
    internal fun geocodeAndSendLocationUpdate(location: Location) {
        CoroutineScope(Dispatchers.IO).launch {
            val message = mutableMapOf<String, Any>(
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
                RTLLog.e(CORE, e) { "Reverse geocoding failed" }
            }

            withContext(Dispatchers.Main) {
                RTLLog.i(CORE) { "Sending location update" }
                webView?.sendToWeb(RTLNativeMessageType.LOCATION_UPDATE, message)
            }
        }
    }

    /**
     * Handle location permission request from webview
     */
    internal fun handleLocationPermissionRequest(activity: Activity?) {
        val extension = locationExtension
        RTLLog.d(CORE) { "handleLocationPermissionRequest - locationExtension: ${extension != null}, isEnabled: ${extension?.isEnabled}" }

        if (extension == null) {
            sendLocationPermissionStatusImmediate(false, null)
            RTLLog.w(CORE) { "Location permission requested but the location module is not installed" }
            return
        }
        if (!extension.isEnabled) {
            sendLocationPermissionStatusImmediate(false, null)
            RTLLog.d(CORE) { "Ignoring web permission request because the host has not enabled location features" }
            return
        }

        // ALWAYS send immediate response with current status
        val hasPermission = extension.hasPermission
        val currentLocation = extension.currentLocation

        RTLLog.d(CORE) { "Current permission status: $hasPermission, has location: ${currentLocation != null}" }

        // Send immediate response
        sendLocationPermissionStatusImmediate(hasPermission, currentLocation)

        // If we have permission and location, also send geocoded update
        if (hasPermission && currentLocation != null) {
            geocodeAndSendLocationUpdate(currentLocation)
        }

        // Notify the webview when a host-authorized permission request completes.
        webviewAwaitingPermissionResponse = !hasPermission
        extension.handleLocationPermissionRequest(activity)
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

    internal fun handleAuthFailure() {
        if (activeAttempt?.awaitingWeb == false) return
        RTLLog.e(CORE) { "Authentication handoff failed" }
        webviewIsReady = false
        webView?.visibility = View.INVISIBLE
        completeLogin(rtlExperienceFailure(RTLExperienceError.AUTHENTICATION_FAILED))
    }

    internal fun handleUserLogoutReceived() {
        cancelAuthentication()
        webviewIsReady = false
        webView?.visibility = View.INVISIBLE
    }

    /**
     * Replaces an expired web session through the host's existing token
     * provider. Recovery deliberately opens the default signed-in page.
     */
    internal fun handleSessionExpired() {
        if (!webviewIsReady) {
            RTLLog.d(CORE) { "Ignoring sessionExpired before the web app is ready" }
            return
        }
        if (activeAttempt != null) return
        startAuthentication(AuthenticationAttempt())
    }

    /**
     * Route an RTL deep link received by the host app.
     *
     * `<scheme>://rtl-sdk/open` presents the experience. The optional
     * `rtlRedirectUrl` takes precedence over `rtlEventId` after authentication.
     * `<scheme>://rtl-sdk/callback` returns from an SDK-owned browser overlay.
     *
     * Returns true when the URL belongs to the SDK. Presentation completes
     * asynchronously through the normal loading and readiness callbacks.
     */
    fun handleDeepLink(url: Uri): Boolean {
        if (!url.isHierarchical) return false
        val route = parseRTLDeepLink(
            scheme = url.scheme,
            host = url.host,
            path = url.path,
            configuredScheme = urlScheme,
            rtlEventId = url.getQueryParameter("rtlEventId"),
            rtlRedirectUrl = url.getQueryParameter("rtlRedirectUrl")
        )
        return when (route) {
            is RTLDeepLinkRoute.Open -> {
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    val result = presentExperience(route.rtlEventId, route.rtlRedirectUrl)
                    if (!result.success && result.errorCode != RTLExperienceError.REQUEST_CANCELLED.code) {
                        RTLLog.w(CORE) { "Deep-link presentation failed: ${result.errorCode ?: "unknown_error"}" }
                    }
                }
                true
            }
            RTLDeepLinkRoute.Callback -> {
                handleOverlayCallback(url)
                true
            }
            null -> false
        }
    }

    private fun handleOverlayCallback(url: Uri) {
        val target = url.getQueryParameter("redirectUrl")
        val destination = target?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (destination == null) {
            RTLLog.e(CORE) { "Overlay returned without a usable redirectUrl" }
            return
        }
        if (!isAllowedWebUrl(destination)) {
            RTLLog.e(CORE) { "Refused to resume at ${destination.host}: not the configured host" }
            return
        }

        // Preserve the callback data when falling back to a page reload.
        val carried = url.queryParameterNames.filter {
            it != "redirectUrl" && it != "resume" && it != "completionType"
        }

        val resumed = destination.buildUpon().clearQuery().apply {
            destination.queryParameterNames.filterNot(carried::contains).forEach { name ->
                destination.getQueryParameters(name).forEach { value ->
                    appendQueryParameter(name, value)
                }
            }
            carried.forEach { name ->
                url.getQueryParameters(name).forEach { value ->
                    appendQueryParameter(name, value)
                }
            }
        }.build()

        val resumeMode = url.getQueryParameter("resume")
        val completionType = url.getQueryParameter("completionType")
        if (resumeMode != null && resumeMode != "message") {
            RTLLog.e(CORE) { "Refused an overlay callback with an unknown resume mode" }
            return
        }
        if (resumeMode == "message" && completionType.isNullOrBlank()) {
            RTLLog.e(CORE) { "Refused an overlay callback without a completion type" }
            return
        }

        // Consumer owns completion types and data validation. Forward new flow
        // types without an SDK upgrade, always inside the fixed envelope.
        // Cold starts use the URL fallback until the page listener is ready.
        if (webviewIsReady && resumeMode == "message") {
            val data = resumed.queryParameterNames.associateWith {
                resumed.getQueryParameter(it).orEmpty()
            }
            webView?.sendToWeb(
                RTLNativeMessageType.OVERLAY_COMPLETED,
                mapOf("completionType" to completionType.orEmpty(), "data" to data)
            )
            return
        }

        RTLLog.i(CORE) { "Overlay returned; reloading the web app at ${resumed.path} (resume=${resumeMode ?: "absent"})" }
        webView?.loadUrl(resumed.toString())
    }

    /** The web view has started loading the experience. */
    internal fun noteOpenStarted() {
        openStartedAt = System.currentTimeMillis()
    }

    /**
     * How long this open took, from the web view starting to load until the web
     * app said it was ready.
     *
     * One number, logged once per open. Everything the speed work claims is
     * measured against it, so it is taken here rather than in a host app, where
     * each would time it slightly differently.
     */
    private fun reportOpenDuration() {
        val startedAt = openStartedAt ?: return
        openStartedAt = null
        RTLLog.i(CORE) { "Open took ${System.currentTimeMillis() - startedAt}ms from loadStart to appReady" }
    }

    internal fun handleAppReady() {
        if (activeAttempt?.awaitingWeb == false) return
        reportOpenDuration()
        RTLLog.d(CORE) { "Received appReady from the webview" }
        webviewIsReady = true
        webView?.visibility = View.VISIBLE
        completeLogin(result = rtlExperienceSuccess())
        listener?.onReady()

        // Send current location permission status to webview now that it's ready
        val extension = locationExtension
        if (extension != null && extension.isEnabled) {
            val hasPermission = extension.hasPermission
            sendLocationPermissionStatus(hasPermission)
        }
    }

    internal fun handleOpenUrl(
        url: String,
        surface: RTLSurface
    ) {
        val presentation = when (surface) {
            RTLSurface.OVERLAY -> "Chrome Custom Tab"
            RTLSurface.AUTH, RTLSurface.PROVIDER_AUTH -> "ephemeral Chrome Custom Tab"
            RTLSurface.EXTERNAL -> "system browser"
        }
        RTLLog.i(CORE) {
            "openExternalUrl: surface=${surface.wireValue}, presentation=$presentation, " +
                "destination=${RTLLog.url(url)}"
        }

        when (surface) {
            RTLSurface.OVERLAY -> {
                openInAppBrowser(url)
                return
            }
            RTLSurface.AUTH -> {
                presentAuthOverlay(url, requireConfiguredHost = true)
                return
            }
            RTLSurface.PROVIDER_AUTH -> {
                // The provider's own host by definition, so the check that
                // guards AUTH cannot apply. What it keeps is the rest: an
                // overlay this app cannot read into, and a callback that comes
                // back through the app scheme.
                presentAuthOverlay(url, requireConfiguredHost = false)
                return
            }
            RTLSurface.EXTERNAL -> {
                openExternalBrowser(url)
                return
            }
        }
    }

    /**
     * An overlay for a flow that returns a result - card linking above all.
     *
     * A Custom Tab, which Chrome owns and this app cannot read into. Android
     * renders it the same way as [RTLSurface.OVERLAY]; what differs is that
     * this one is restricted to our own host, and will carry the callback
     * handling when that lands.
     *
     * The web decides *that* this surface is needed; the SDK decides *where* it
     * may point, and that is our own host only. A request naming anywhere else
     * is refused rather than downgraded: falling back to the system browser
     * would send the user out of the app against the caller's intent, and the
     * WebView would put the page in host-visible code.
     */
    private fun presentAuthOverlay(url: String, requireConfiguredHost: Boolean) {
        val destination = runCatching { Uri.parse(url) }.getOrNull()
        if (destination == null) {
            RTLLog.e(CORE) { "Refused an auth overlay: unusable URL" }
            return
        }
        if (!isWebUrl(destination)) {
            RTLLog.e(CORE) { "Refused a non-web authentication URL" }
            return
        }
        if (requireConfiguredHost && !isAllowedWebUrl(destination)) {
            RTLLog.e(CORE) { "Refused an auth overlay for ${destination.host}: this surface is for the configured host only" }
            return
        }

        val activity = currentActivityRef?.get()
        if (activity == null) {
            RTLLog.w(CORE) { "Cannot present the overlay: no activity reference" }
            return
        }

        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            // Non-persistent, isolated session - the counterpart to iOS's
            // ephemeral ASWebAuthenticationSession. The auth/provider flows
            // authenticate from a one-time token, not a carried-over cookie, so
            // they neither need nor should touch Chrome's shared profile. It also
            // stops Chrome restoring a backgrounded tab and re-running the
            // now-consumed one-time token, which surfaced as an auth-error page on
            // resume. This is the extra androidx.browser's setEphemeralBrowsingEnabled
            // sets; set directly so the SDK need not depend on the 1.9 alpha. A
            // browser without ephemeral support ignores it and opens a normal tab.
            customTabsIntent.intent.putExtra(EXTRA_ENABLE_EPHEMERAL_BROWSING, true)
            customTabsIntent.launchUrl(activity, destination)
            RTLLog.i(CORE) { "Opened an isolated auth overlay for ${destination.host ?: "-"}" }
        } catch (e: Exception) {
            RTLLog.e(CORE, e) { "Failed to open the Custom Tab" }
            return
        }

    }

    /**
     * Open a URL in the system's default browser
     */
    private fun openExternalBrowser(url: String) {
        val context = currentActivityRef?.get() ?: application?.applicationContext
        if (context == null) {
            RTLLog.w(CORE) { "Cannot open the external browser: no context" }
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            RTLLog.i(CORE) { "Opened ${Uri.parse(url).host ?: "-"} in the system browser, leaving the app" }
        } catch (e: Exception) {
            RTLLog.e(CORE, e) { "Failed to open the external browser" }
        }
    }

    /**
     * Open a URL in Chrome Custom Tabs (in-app browser)
     */
    // Returns true when an in-app browser was actually presented. Fails closed:
    // never drops to the system browser, because that is the app-switch the
    // overlay surface exists to prevent, and the caller must not report success
    // when the user was in fact sent out of the app. iOS refuses the same way.
    private fun openInAppBrowser(url: String): Boolean {
        val destination = runCatching { Uri.parse(url) }.getOrNull()
        if (destination == null || !isWebUrl(destination)) {
            RTLLog.e(CORE) {
                "Refused a non-web overlay URL; use the external surface for deliberate app switching"
            }
            return false
        }
        val activity = currentActivityRef?.get()
        if (activity == null) {
            RTLLog.e(CORE) { "Cannot open the in-app browser: no activity reference" }
            return false
        }

        return try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(activity, destination)
            RTLLog.i(CORE) { "Opened an in-app browser overlay for ${destination.host ?: "-"}" }
            true
        } catch (e: Exception) {
            RTLLog.e(CORE, e) { "Failed to open the Chrome Custom Tab" }
            false
        }
    }

    // Private methods

    /** Emits only loading transitions; overlapping attempts keep the loader visible. */
    private fun updateExperienceLoading(isLoading: Boolean) {
        val update = {
            if (isExperienceLoading != isLoading) {
                isExperienceLoading = isLoading
                listener?.onLoadingStateChanged(isLoading)
            }
            if (isLoading) webView?.visibility = View.INVISIBLE
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            update()
        } else {
            Handler(Looper.getMainLooper()).post(update)
        }
    }

    private fun cancelAuthentication() {
        activeAttempt?.let {
            finishAuthentication(it, rtlExperienceFailure(RTLExperienceError.REQUEST_CANCELLED))
        }
    }

    private fun completeLogin(result: RTLExperienceResult) {
        val attempt = activeAttempt
        if (attempt != null) {
            finishAuthentication(attempt, result)
        } else {
            // Bundled examples can authenticate directly in the WebView.
            updateExperienceLoading(false)
        }
    }

    private fun finishAuthentication(
        attempt: AuthenticationAttempt,
        result: RTLExperienceResult,
        keepLoading: Boolean = false
    ) {
        if (activeAttempt !== attempt) return
        activeAttempt = null
        attempt.tokenJob?.cancel()
        attempt.timeoutJob?.cancel()
        if (!keepLoading) updateExperienceLoading(false)
        attempt.result.complete(result)
    }

    private fun buildTokenForwardUrl(
        token: String,
        rtlEventId: String?,
        rtlRedirectUrl: String?
    ): String? {
        val baseUrl = this.baseUrl ?: return null
        val urlScheme = this.urlScheme ?: return null

        val fragment = Uri.Builder()
            .appendQueryParameter("token", token)
            .appendQueryParameter("appScheme", urlScheme)
            .apply {
                if (!rtlEventId.isNullOrEmpty()) {
                    appendQueryParameter("rtlEventId", rtlEventId)
                }
                if (!rtlRedirectUrl.isNullOrEmpty()) {
                    appendQueryParameter("rtlRedirectUrl", rtlRedirectUrl)
                }
            }
            .build()
            .encodedQuery ?: return null

        return baseUrl.buildUpon()
            .path("/auth/token-forward/handoff")
            .clearQuery()
            .encodedFragment(fragment)
            .build()
            .toString()
    }

    internal fun isAllowedWebUrl(url: Uri): Boolean {
        val configuredUrl = baseUrl ?: return false
        return configuredUrl.scheme.equals(url.scheme, ignoreCase = true) &&
            configuredUrl.host.equals(url.host, ignoreCase = true) &&
            effectivePort(configuredUrl) == effectivePort(url)
    }

    private fun isWebUrl(url: Uri): Boolean =
        url.scheme.equals("http", ignoreCase = true) ||
            url.scheme.equals("https", ignoreCase = true)

    private fun effectivePort(url: Uri): Int {
        if (url.port != -1) {
            return url.port
        }
        return when (url.scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
    }

    /**
     * Current configured base URL.
     *
     * @suppress This is an internal API for use by rtl-sdk-location module only.
     */
    val currentBaseUrl: String? get() = baseUrl?.toString()

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
