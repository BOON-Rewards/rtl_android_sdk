package com.affinaloyalty.rtlsdk.example

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.affinaloyalty.rtlsdk.RTLLog
import com.affinaloyalty.rtlsdk.RTLSdk
import com.affinaloyalty.rtlsdk.RTLSdkListener
import com.affinaloyalty.rtlsdk.RTLStore
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), RTLSdkListener {

    private lateinit var statusText: TextView
    private lateinit var loginButton: Button
    private lateinit var logoutButton: Button
    private lateinit var webViewContainer: FrameLayout
    private lateinit var loadingOverlay: View
    private lateinit var rtlWebView: View
    private val appScheme = BuildConfig.RTL_APP_SCHEME
    private val rtlActionType = "rtlSdk"

    // Staging-only test user. Production hosts fetch a token from their backend.
    private val testToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyIjp7ImlkIjoiMmIwMzBhMzYtYWQyMS0xMjIyLTEyMzItYzViZjg5OGQxN2IxIiwiZ2VuZGVyIjoiRmVtYWxlIiwiZmlyc3ROYW1lIjoiRXJpY2thIiwibGFzdE5hbWUiOiJOIiwiZW1haWwiOiJsZXZvbmFsdkBnZXRib29uLmNvbSJ9LCJvcmdJZCI6ImNrcDluM2Q4eTAwNjNrc3V2Y2hjNndmZ3QiLCJjaGFwdGVySWQiOiJjMzIwNDdiNC01ZDk5LTQ1MDUtYjczMy03MWYxZmRlNGU1NzAiLCJwb2ludHNQZXJEb2xsYXIiOjIwMCwiaWF0IjoxNzU0MzA3MDg0LCJleHAiOjE4NDkwMzMwMDJ9.3yTQC0bEeiogdHd4qM_Wh8bRnY_aQ9F9ngk5QUF_CF8"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        loginButton = findViewById(R.id.loginButton)
        logoutButton = findViewById(R.id.logoutButton)
        webViewContainer = findViewById(R.id.webViewContainer)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        loginButton.setOnClickListener { onLoginClicked() }
        logoutButton.setOnClickListener { logout() }

        // Handle window insets to avoid status bar overlap
        ViewCompat.setOnApplyWindowInsetsListener(webViewContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        initializeSDK()
        if (savedInstanceState == null) {
            handleEntryIntent(intent)
        }
    }

    private fun initializeSDK() {
        // Initialize the SDK
        // The SDK writes at INFO and above by default, so a host app
        // does not get its debug output in production. An example app
        // wants all of it.
        RTLLog.level = Log.DEBUG

        RTLSdk.getInstance().initialize(
            baseUrl = "https://client-provided-url.example",
            urlScheme = appScheme,
            context = this,
            listener = this,
            externalChapterId = "c32047b4-5d99-4505-b733-71f1fde4e570"
        )

        HyperlocalOffersExample.install(RTLSdk.getInstance())

        // Create webview (the SDK manages its visibility)
        val webView = RTLSdk.getInstance().createWebView(this)
        rtlWebView = webView
        webViewContainer.addView(webView)

        statusText.text = "Tap Login to continue"
    }

    private fun onLoginClicked() {
        launchExperience("Logging in...")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEntryIntent(intent)
    }

    private fun handleEntryIntent(intent: Intent?) {
        val deepLink = intent?.data
        if (deepLink != null && RTLSdk.getInstance().handleDeepLink(deepLink)) {
            return
        }

        val rtlPushEventId = parseRTLPushEventId(intent)
        if (rtlPushEventId != null) {
            launchExperience(
                statusMessage = "Opening RTL experience from push...",
                rtlEventId = rtlPushEventId
            )
            return
        }
    }

    private fun launchExperience(
        statusMessage: String,
        rtlEventId: String? = null
    ) {
        statusText.text = statusMessage
        loginButton.isEnabled = false

        lifecycleScope.launch {
            val result = RTLSdk.getInstance().presentExperience(
                rtlEventId = rtlEventId
            )

            runOnUiThread {
                if (!result.success && result.errorCode != "request_cancelled") {
                    statusText.text = "Failed to load RTL experience (${result.errorCode ?: "unknown_error"})"
                    loginButton.isEnabled = true
                }
            }
        }
    }

    private fun logout() {
        RTLSdk.getInstance().logout()
        HyperlocalOffersExample.disable(RTLSdk.getInstance())
        rtlWebView.visibility = View.INVISIBLE
        loadingOverlay.visibility = View.GONE
        logoutButton.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = "Tap Login to continue"
        loginButton.visibility = View.VISIBLE
        loginButton.isEnabled = true
        supportActionBar?.show()
    }

    private fun parseRTLPushEventId(intent: Intent?): String? {
        val extras = intent?.extras ?: return null

        val topLevelActionType = extras.getString("rtlActionType")
        val topLevelEventId = extras.getString("rtlEventId")
        if (topLevelActionType == rtlActionType && !topLevelEventId.isNullOrEmpty()) {
            return topLevelEventId
        }

        val metadata = extras.getBundle("metadata")
        val metadataActionType = metadata?.getString("rtlActionType")
        val metadataEventId = metadata?.getString("rtlEventId")
        if (metadataActionType == rtlActionType && !metadataEventId.isNullOrEmpty()) {
            return metadataEventId
        }

        return null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        HyperlocalOffersExample.handlePermissionResult(
            RTLSdk.getInstance(),
            requestCode,
            permissions,
            grantResults
        )
    }

    // RTLSdkListener implementation

    override fun onReady() {
        Log.d("RTLExample", "RTL app is ready")
        statusText.visibility = View.GONE
        loginButton.visibility = View.GONE
        logoutButton.visibility = View.VISIBLE
        supportActionBar?.hide()
        HyperlocalOffersExample.enable(RTLSdk.getInstance(), this)
    }

    override fun onLoadingStateChanged(isLoading: Boolean) {
        loadingOverlay.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (isLoading) loadingOverlay.bringToFront()
    }

    override suspend fun provideAuthToken(): String? {
        Log.d("RTLExample", "SDK requesting token...")
        // In a real app, call your auth service here.
        return testToken
    }

    // Optional location callbacks
    override val onLocationPermissionChange: ((granted: Boolean) -> Unit)? = { granted ->
        Log.d("RTLExample", "Location permission changed: $granted")
    }

    override val onGeofenceEnter: ((store: RTLStore) -> Unit)? = { store ->
        Log.d("RTLExample", "Entered geofence for store: ${store.name}")
    }
}
