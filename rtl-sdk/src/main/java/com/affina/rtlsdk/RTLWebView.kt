package com.affina.rtlsdk

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.webkit.*
import android.widget.FrameLayout
import org.json.JSONObject

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

            // Enable debugging in debug builds
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            }

            webViewClient = RTLWebViewClient()
            webChromeClient = WebChromeClient()

            // Add JavaScript interface for message passing
            addJavascriptInterface(RTLJavaScriptInterface(), "inappwebview")
        }

        addView(webView)
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

            // Check for allowed domains
            val host = request.url?.host ?: ""
            val isAllowedDomain = host.contains("getboon.com") ||
                    host.contains("affinaloyalty.com")

            return if (isAllowedDomain) {
                false // Allow loading
            } else {
                // External URL - notify listener
                sdk?.handleOpenUrl(url, forceExternal = true)
                true // Cancel loading
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            println("[RTLSdk] WebView finished loading: $url")
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                println("[RTLSdk] WebView error: ${error?.description}")
            }
        }
    }

    /**
     * JavaScript interface for receiving messages from the web app
     */
    private inner class RTLJavaScriptInterface {

        @JavascriptInterface
        fun postMessage(message: String) {
            println("[RTLSdk] Received message: $message")

            try {
                val json = JSONObject(message)
                val type = json.optString("type", "")

                println("[RTLSdk] Message type: $type")

                when (type) {
                    "openExternalUrl" -> handleOpenExternalUrl(json)
                    "userAuth" -> handleUserAuth(json)
                    "userLogout" -> sdk?.handleUserLogoutReceived()
                    "appReady" -> sdk?.handleAppReady()
                    "locationPermissionRequest",
                    "locationPermissionStatus",
                    "locationUpdate" -> {
                        println("[RTLSdk] Location message type not handled: $type")
                    }
                    else -> {
                        println("[RTLSdk] Unknown message type: $type")
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
            val accessToken = json.optString("accessToken", "")
            val refreshToken = json.optString("refreshToken", "")

            if (accessToken.isEmpty() || refreshToken.isEmpty()) {
                println("[RTLSdk] Missing tokens in userAuth message")
                return
            }

            sdk?.handleUserAuthReceived(accessToken, refreshToken)
        }
    }
}
