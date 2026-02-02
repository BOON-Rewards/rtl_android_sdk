package com.affina.rtlsdk.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        loginButton = findViewById(R.id.loginButton)
        webViewContainer = findViewById(R.id.webViewContainer)

        loginButton.setOnClickListener { onLoginClicked() }

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

        // Set listener
        RTLSdk.getInstance().listener = this

        // Create and embed the webview
        val webView = RTLSdk.getInstance().createWebView(this)
        webViewContainer.addView(webView)
        rtlWebView = webView

        updateStatus()
    }

    private fun onLoginClicked() {
        // In a real app, you would get this token from your authentication system
        val testToken = "your-jwt-token-here"

        statusText.text = "Logging in..."
        loginButton.isEnabled = false

        lifecycleScope.launch {
            val success = RTLSdk.getInstance().login(testToken)

            runOnUiThread {
                if (success) {
                    statusText.text = "Login successful!"
                } else {
                    statusText.text = "Login failed or timed out"
                }
                loginButton.isEnabled = true
                updateStatus()
            }
        }
    }

    private fun updateStatus() {
        val isLoggedIn = RTLSdk.getInstance().isLoggedIn()
        val statusStr = when (isLoggedIn) {
            true -> "Logged In"
            false -> "Not Logged In"
            null -> "Not Initialized"
        }
        statusText.text = "SDK Status: $statusStr"
    }

    // RTLSdkListener implementation

    override fun onAuthenticated(accessToken: String, refreshToken: String) {
        println("Authenticated! Access token: ${accessToken.take(20)}...")
        runOnUiThread { updateStatus() }
    }

    override fun onLogout() {
        println("User logged out")
        runOnUiThread { updateStatus() }
    }

    override fun onOpenUrl(url: String, forceExternal: Boolean) {
        println("Open URL requested: $url, forceExternal: $forceExternal")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    override fun onReady() {
        println("RTL app is ready")
    }
}
