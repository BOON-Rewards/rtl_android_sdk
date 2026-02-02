package com.affina.rtlsdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlin.coroutines.resume

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

        private const val LOGIN_TIMEOUT_MS = 30_000L
    }

    // Configuration
    private var program: String? = null
    private var environment: RTLEnvironment? = null
    private var urlScheme: String? = null
    private var application: Application? = null
    private var isInitialized = false

    // State
    private var _isLoggedIn: Boolean? = null
    private var webView: RTLWebView? = null

    // Async login
    private var loginContinuation: CancellableContinuation<Boolean>? = null
    private var loginTimeoutJob: Job? = null

    /**
     * Listener for SDK events
     */
    var listener: RTLSdkListener? = null

    /**
     * Initialize the SDK with configuration
     *
     * @param program The program identifier (e.g., "crowdplay")
     * @param environment The target environment (STAGING or PRODUCTION)
     * @param urlScheme The app's URL scheme for deep linking
     * @param context The Activity context
     */
    fun initialize(
        program: String,
        environment: RTLEnvironment,
        urlScheme: String,
        context: Activity
    ) {
        this.program = program
        this.environment = environment
        this.urlScheme = urlScheme
        this.application = context.application
        this.isInitialized = true
        this._isLoggedIn = false
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
        this.webView = webView
        return webView
    }

    /**
     * Async login that completes when userAuth message is received or times out
     *
     * @param token JWT token from host app's auth system
     * @return true if login succeeded (userAuth received), false if failed/timed out
     */
    suspend fun login(token: String): Boolean {
        val webView = this.webView ?: run {
            println("[RTLSdk] Error: WebView not created. Call createWebView() first.")
            return false
        }

        // Cancel any existing login attempt
        cancelPendingLogin()

        val url = buildTokenForwardUrl(token) ?: run {
            println("[RTLSdk] Error: Failed to build token forward URL")
            return false
        }

        return suspendCancellableCoroutine { continuation ->
            loginContinuation = continuation

            // Set up timeout
            loginTimeoutJob = CoroutineScope(Dispatchers.Main).launch {
                delay(LOGIN_TIMEOUT_MS)
                completeLogin(success = false)
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

    /**
     * Register a push notification token with the RTL backend
     *
     * @param token The device push token
     * @param type The token type (APNS or FCM)
     */
    fun registerPushToken(token: String, type: RTLTokenType) {
        val escapedToken = token.replace("'", "\\'")
        val script = "window.rtlNative?.registerPushToken('$escapedToken', '${type.value}')"
        webView?.evaluateJavascript(script)
    }

    // Internal methods

    internal fun handleUserAuthReceived(accessToken: String, refreshToken: String) {
        _isLoggedIn = true
        listener?.onAuthenticated(accessToken, refreshToken)
        completeLogin(success = true)
    }

    internal fun handleUserLogoutReceived() {
        _isLoggedIn = false
        listener?.onLogout()
    }

    internal fun handleAppReady() {
        listener?.onReady()
    }

    internal fun handleOpenUrl(url: String, forceExternal: Boolean) {
        listener?.onOpenUrl(url, forceExternal)
    }

    // Private methods

    private fun cancelPendingLogin() {
        loginTimeoutJob?.cancel()
        loginTimeoutJob = null
        loginContinuation?.takeIf { it.isActive }?.resume(false)
        loginContinuation = null
    }

    private fun completeLogin(success: Boolean) {
        loginTimeoutJob?.cancel()
        loginTimeoutJob = null
        loginContinuation?.takeIf { it.isActive }?.resume(success)
        loginContinuation = null
    }

    private fun buildTokenForwardUrl(token: String): String? {
        val program = this.program ?: return null
        val environment = this.environment ?: return null
        val urlScheme = this.urlScheme ?: return null

        val domain = when (environment) {
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
        }
    }

    // Internal accessors
    internal val currentProgram: String? get() = program
    internal val currentEnvironment: RTLEnvironment? get() = environment
}
