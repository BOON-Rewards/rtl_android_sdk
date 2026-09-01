package com.affina.rtlsdk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.webkit.*
import android.widget.FrameLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.URI

/**
 * Embeddable webview for RTL experience
 */
@SuppressLint("SetJavaScriptEnabled")
class RTLWebView @JvmOverloads constructor(
    context: Context,
    private val sdk: RTLSdk? = null,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val webView: WebView
    private val refreshLayout: SwipeRefreshLayout
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hapticEngine = RTLHapticEngine(context)
    private val allowedOriginRules = sdk?.currentBaseUrl
        ?.let(::webMessageOriginRule)
        ?.let(::setOf)
        .orEmpty()

    init {
        webView = WebView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowContentAccess = true
                allowFileAccess = true

                // Enable mixed content for development
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                // Enable zoom
                builtInZoomControls = false
                displayZoomControls = false
            }

            // Enable debugging in debug builds (check at runtime to avoid BuildConfig dependency)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                val isDebuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                WebView.setWebContentsDebuggingEnabled(isDebuggable)
            }

            webViewClient = RTLWebViewClient()
            webChromeClient = WebChromeClient()
        }

        installJavaScriptBridge()

        refreshLayout = SwipeRefreshLayout(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            setColorSchemeColors(Color.rgb(238, 238, 238))
            setProgressBackgroundColorSchemeColor(Color.TRANSPARENT)
            setOnChildScrollUpCallback { _, _ ->
                webView.canScrollVertically(-1)
            }
            setOnRefreshListener {
                val currentUrl = webView.url
                if (currentUrl.isNullOrBlank() || currentUrl == "about:blank") {
                    isRefreshing = false
                } else {
                    webView.reload()
                }
            }
        }

        if (allowedOriginRules.isNotEmpty() &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                nativeCapabilitiesScript(),
                allowedOriginRules
            )
        }

        refreshLayout.addView(
            webView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        addView(refreshLayout)
    }

    /**
     * Load a URL in the webview
     */
    fun loadUrl(url: String) {
        webView.loadUrl(url)
    }

    /**
     * Evaluate JavaScript in the webview
     */
    fun evaluateJavascript(script: String, callback: ValueCallback<String>? = null) {
        webView.evaluateJavascript(script, callback)
    }

    /**
     * Reload the current page
     */
    fun reload() {
        webView.reload()
    }

    /**
     * Go back in history
     */
    fun goBack() {
        webView.goBack()
    }

    /**
     * Go forward in history
     */
    fun goForward() {
        webView.goForward()
    }

    /**
     * Check if can go back
     */
    fun canGoBack(): Boolean = webView.canGoBack()

    /**
     * Check if can go forward
     */
    fun canGoForward(): Boolean = webView.canGoForward()

    override fun onDetachedFromWindow() {
        hapticEngine.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) hapticEngine.cancel()
    }

    /**
     * Post a message to the web content via window.postMessage
     *
     * @param message Map of data to send as JSON
     */
    fun postMessage(message: Map<String, Any>) {
        try {
            val json = JSONObject(message).toString()
            val script = "window.postMessage($json, '*')"
            evaluateJavascript(script)
        } catch (e: Exception) {
            println("[RTLSdk] Failed to serialize message to JSON: ${e.message}")
        }
    }

    /**
     * WebViewClient for handling navigation
     */
    private inner class RTLWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val url = request?.url?.toString() ?: return false

            // Allow about:blank
            if (url == "about:blank") {
                return false
            }

            return if (sdk?.isAllowedWebUrl(request.url) == true) {
                false // Allow loading
            } else {
                // External URL - notify listener
                sdk?.handleOpenUrl(url, forceExternal = true)
                true // Cancel loading
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            finishPullToRefresh()
            injectNativeCapabilities(view)
            println("[RTLSdk] WebView finished loading: $url")
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame != false) finishPullToRefresh()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                println("[RTLSdk] WebView error: ${error?.description}")
            }
        }

        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?
        ): Boolean {
            finishPullToRefresh()
            return super.onRenderProcessGone(view, detail)
        }
    }

    private fun finishPullToRefresh() {
        refreshLayout.isRefreshing = false
    }

    private fun installJavaScriptBridge() {
        if (allowedOriginRules.isEmpty()) {
            println("[RTLSdk] JavaScript bridge disabled: no trusted web origin configured")
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            println("[RTLSdk] JavaScript bridge disabled: the installed WebView is too old")
            return
        }

        WebViewCompat.addWebMessageListener(
            webView,
            "inappwebview",
            allowedOriginRules,
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy
                ) {
                    if (!isMainFrame || sdk?.isAllowedWebUrl(sourceOrigin) != true) {
                        println("[RTLSdk] Ignoring bridge message from an untrusted frame")
                        return
                    }
                    message.data?.let(::handleBridgeMessage)
                }
            }
        )
    }

    private fun handleBridgeMessage(message: String) {
        println("[RTLSdk] Received message: $message")

        try {
            val json = JSONObject(message)
            val type = json.optString("type", "")
            val hapticPattern = if (type == "haptic.play") {
                RTLHapticBridgeMessage.parse(message)
            } else {
                null
            }

            println("[RTLSdk] Message type: $type")

            mainHandler.post {
                when (type) {
                    "openExternalUrl" -> handleOpenExternalUrl(json)
                    "userAuth" -> handleUserAuth(json)
                    "userLogout" -> sdk?.handleUserLogoutReceived()
                    "appReady" -> sdk?.handleAppReady()
                    "locationPermissionRequest" -> {
                        val activity = context as? Activity
                        sdk?.handleLocationPermissionRequest(activity)
                    }
                    "haptic.play" -> hapticPattern?.let(hapticEngine::play)
                    "locationPermissionStatus",
                    "locationUpdate" -> {
                        println("[RTLSdk] Unexpected incoming location message: $type")
                    }
                    else -> println("[RTLSdk] Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            println("[RTLSdk] Error parsing message: ${e.message}")
        }
    }

    private fun handleOpenExternalUrl(json: JSONObject) {
        val url = json.optString("URL", "")
        if (url.isEmpty()) {
            println("[RTLSdk] Invalid URL in openExternalUrl message")
            return
        }

        val forceExternal = when (val value = json.opt("forceExternalBrowser")) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> false
        }

        sdk?.handleOpenUrl(url, forceExternal)
    }

    private fun handleUserAuth(json: JSONObject) {
        var accessToken = json.optString("accessToken", "")
        if (accessToken.isEmpty()) {
            accessToken = json.optString("token", "")
        }
        val refreshToken = json.optString("refreshToken", "")

        if (accessToken.isEmpty() || refreshToken.isEmpty()) {
            println("[RTLSdk] Missing tokens in userAuth message")
            return
        }

        sdk?.handleUserAuthReceived(accessToken, refreshToken)
    }

    private fun injectNativeCapabilities(target: WebView?) {
        target?.evaluateJavascript(nativeCapabilitiesScript(), null)
    }

    private fun nativeCapabilitiesScript(): String {
        return nativeCapabilitiesScript(hapticEngine.capabilities())
    }
}

internal fun nativeCapabilitiesScript(haptics: Map<String, Any>): String {
    val capabilities = JSONObject(mapOf("haptics" to haptics)).toString()
    return """
        (function() {
            var capabilities = $capabilities;
            Object.freeze(capabilities.haptics);
            Object.freeze(capabilities);
            window.NativeAppCapabilities = capabilities;
            window.rtlNativeCapabilities = capabilities;
            window.dispatchEvent(new CustomEvent('NativeAppCapabilitiesReady', { detail: capabilities }));
            window.dispatchEvent(new CustomEvent('rtlNativeCapabilitiesReady', { detail: capabilities }));
        })();
    """.trimIndent()
}

internal fun webMessageOriginRule(urlString: String): String? {
    val url = try {
        URI(urlString)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val scheme = url.scheme?.lowercase() ?: return null
    val host = url.host ?: return null
    if (scheme != "https" && scheme != "http") return null

    val formattedHost = when {
        host.startsWith("[") -> host
        ':' in host -> "[$host]"
        else -> host
    }
    val port = if (url.port == -1) "" else ":${url.port}"
    return "$scheme://$formattedHost$port"
}
