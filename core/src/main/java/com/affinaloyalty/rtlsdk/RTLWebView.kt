package com.affinaloyalty.rtlsdk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.webkit.*
import android.widget.FrameLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.affinaloyalty.rtlsdk.RTLLogArea.WEB_VIEW

/**
 * Embeddable webview for RTL experience
 */
@SuppressLint("SetJavaScriptEnabled")
class RTLWebView internal constructor(
    context: Context,
    private val sdk: RTLSdk? = null,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var webView: WebView
    private val refreshLayout: SwipeRefreshLayout
    // SwipeRefreshLayout caches its content child. Keep that child stable when
    // authentication replaces the WebView, so the new view is measured and laid out.
    private val contentContainer = FrameLayout(context)
    private val hapticEngine = RTLHapticEngine(context)
    private val allowedOriginRules = sdk?.currentBaseUrl
        ?.let(::webMessageOriginRule)
        ?.let(::setOf)
        .orEmpty()

    private var bridge = RTLBridge(context, sdk, hapticEngine, allowedOriginRules)

    internal fun handleLocationPermissionResult(requestCode: Int): Boolean =
        bridge.handlePermissionResult(requestCode)

    init {
        webView = createInnerWebView()

        bridge.install(webView)

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

        contentContainer.addView(webView)
        refreshLayout.addView(
            contentContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        addView(refreshLayout)
    }

    private fun createInnerWebView(): WebView = WebView(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = false
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

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

    internal fun invalidateDocument() {
        bridge.invalidate()
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.visibility = INVISIBLE
        hapticEngine.cancel()
    }

    internal fun prepareAuthenticationDocument() {
        invalidateDocument()
        refreshLayout.isRefreshing = false
        contentContainer.removeView(webView)
        webView.destroy()
        bridge = RTLBridge(context, sdk, hapticEngine, allowedOriginRules)
        webView = createInnerWebView()
        bridge.install(webView)
        contentContainer.addView(webView)
    }

    /**
     * Load a URL in the webview
     */
    internal fun loadUrl(url: String) {
        webView.loadUrl(url)
    }

    /**
     * Loads the unauthenticated sign-in page used by the bundled SDK examples.
     * Host integrations should use [RTLSdk.presentExperience].
     *
     * @suppress
     */
    fun loadLoginForExample(url: String) {
        sdk?.beginExampleLogin(this)
        prepareAuthenticationDocument()
        webView.loadUrl(url)
    }

    /**
     * Reload the current page
     */
    internal fun reload() {
        webView.reload()
    }

    /**
     * Go back in history
     */
    internal fun goBack() {
        webView.goBack()
    }

    /**
     * Go forward in history
     */
    internal fun goForward() {
        webView.goForward()
    }

    /**
     * Check if can go back
     */
    internal fun canGoBack(): Boolean = webView.canGoBack()

    /**
     * Check if can go forward
     */
    internal fun canGoForward(): Boolean = webView.canGoForward()

    override fun onDetachedFromWindow() {
        hapticEngine.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) hapticEngine.cancel()
    }

    internal fun sendToWeb(
        type: RTLNativeMessageType,
        fields: Map<String, Any> = emptyMap()
    ) = bridge.sendToWeb(type, fields)

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
                sdk?.handleOpenUrl(url, surface = RTLSurface.OVERLAY)
                true // Cancel loading
            }
        }

        override fun onPageStarted(
            view: WebView?,
            url: String?,
            favicon: android.graphics.Bitmap?
        ) {
            super.onPageStarted(view, url, favicon)
            // The clock starts here rather than when the load was requested, so
            // it measures what the user waits for.
            if (url != null && url != "about:blank") {
                sdk?.noteOpenStarted()
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            finishPullToRefresh()
            RTLLog.d(WEB_VIEW) { "Finished loading ${RTLLog.url(url)}" }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame != false) finishPullToRefresh()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                RTLLog.e(WEB_VIEW) { "WebView error: ${error?.description}" }
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

}
