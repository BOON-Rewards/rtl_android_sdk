package com.affina.rtlsdk.example

import android.content.Intent
import android.net.Uri
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
import com.affina.rtlsdk.RTLEnvironment
import com.affina.rtlsdk.RTLSdk
import com.affina.rtlsdk.RTLSdkListener
import com.affina.rtlsdk.RTLWebView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), RTLSdkListener {

    private lateinit var statusText: TextView
    private lateinit var loginButton: Button
    private lateinit var webViewContainer: FrameLayout
    private var rtlWebView: RTLWebView? = null

    // Test token - in a real app, this would come from your authentication system
    private val testToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyIjp7ImlkIjoiMmIwMzBhMzYtYWQyMS0xMjIyLTEyMzItYzViZjg5OGQxN2IxIiwiZ2VuZGVyIjoiRmVtYWxlIiwiZmlyc3ROYW1lIjoiRXJpY2thIiwibGFzdE5hbWUiOiJOIiwiZW1haWwiOiJsZXZvbmFsdkBnZXRib29uLmNvbSJ9LCJvcmdJZCI6ImNrcDluM2Q4eTAwNjNrc3V2Y2hjNndmZ3QiLCJjaGFwdGVySWQiOiJjMzIwNDdiNC01ZDk5LTQ1MDUtYjczMy03MWYxZmRlNGU1NzAiLCJwb2ludHNQZXJEb2xsYXIiOjIwMCwiaWF0IjoxNzU0MzA3MDg0LCJleHAiOjE4NDkwMzMwMDJ9.3yTQC0bEeiogdHd4qM_Wh8bRnY_aQ9F9ngk5QUF_CF8"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        loginButton = findViewById(R.id.loginButton)
        webViewContainer = findViewById(R.id.webViewContainer)

        // Hide webview initially
        webViewContainer.visibility = View.GONE

        loginButton.setOnClickListener { onLoginClicked() }

        // Handle window insets to avoid status bar overlap
        ViewCompat.setOnApplyWindowInsetsListener(webViewContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        initializeSDK()
    }

    private fun initializeSDK() {
        // Initialize the SDK
        RTLSdk.getInstance().initialize(
            program = "crowdplay",
            environment = RTLEnvironment.STAGING,
            urlScheme = "rtlsdkexample",
            context = this
        )

        // Set listener BEFORE creating webview
        RTLSdk.getInstance().listener = this

        // Create webview (hidden until login)
        val webView = RTLSdk.getInstance().createWebView(this)
        webViewContainer.addView(webView)
        rtlWebView = webView

        statusText.text = "Tap Login to continue"
    }

    private fun onLoginClicked() {
        statusText.text = "Logging in..."
        loginButton.isEnabled = false

        lifecycleScope.launch {
            RTLSdk.getInstance().requestTokenAndLogin()

            runOnUiThread {
                // Show full screen webview
                showFullScreenWebView()
            }
        }
    }

    private fun showFullScreenWebView() {
        // Hide login UI
        statusText.visibility = View.GONE
        loginButton.visibility = View.GONE

        // Show webview full screen
        webViewContainer.visibility = View.VISIBLE

        // Hide action bar for true full screen
        supportActionBar?.hide()
    }

    // RTLSdkListener implementation

    override fun onAuthenticated(accessToken: String, refreshToken: String) {
        Log.d("RTLExample", "Authenticated! Access token: ${accessToken.take(20)}...")
    }

    override fun onLogout() {
        Log.d("RTLExample", "User logged out")
        runOnUiThread {
            // Show login UI again
            webViewContainer.visibility = View.GONE
            statusText.visibility = View.VISIBLE
            statusText.text = "Session ended. Tap Login to continue"
            loginButton.visibility = View.VISIBLE
            loginButton.isEnabled = true
            supportActionBar?.show()
        }
    }

    override fun onOpenUrl(url: String, forceExternal: Boolean) {
        Log.d("RTLExample", "Open URL requested: $url, forceExternal: $forceExternal")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    override fun onReady() {
        Log.d("RTLExample", "RTL app is ready")
    }

    override suspend fun onNeedsToken(): String? {
        Log.d("RTLExample", "SDK requesting token...")
        // In a real app, call your auth service here
        return testToken
    }
}
