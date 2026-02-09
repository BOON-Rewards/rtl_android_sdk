# RTL SDK for Android

Native Android SDK for integrating the RTL (Rewards, Transactions, Loyalty) experience into your Android application.

## Requirements

- Android API 23+ (Android 6.0 Marshmallow)
- Kotlin 1.8+
- Android Studio Hedgehog (2023.1.1) or later

## Installation

### Gradle (GitHub Packages)

Add the GitHub Packages repository to your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/BOON-Rewards/rtl_android_sdk")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_USERNAME")
                password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

Add the dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.affina:rtl-sdk:1.0.0")
}
```

> **Note:** GitHub Packages requires authentication. Add your GitHub username and a personal access token (with `read:packages` scope) to your `~/.gradle/gradle.properties`:
> ```properties
> gpr.user=YOUR_GITHUB_USERNAME
> gpr.token=YOUR_GITHUB_TOKEN
> ```

## Quick Start

```kotlin
import com.affina.rtlsdk.*

class MainActivity : AppCompatActivity(), RTLSdkListener {

    private var rtlWebView: RTLWebView? = null
    private lateinit var webViewContainer: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webViewContainer = findViewById(R.id.webViewContainer)

        // 1. Initialize SDK
        RTLSdk.getInstance().initialize(
            program = "your-program-id",
            environment = RTLEnvironment.STAGING,
            urlScheme = "your-app-scheme",
            context = this
        )

        // 2. Set listener BEFORE creating webview
        RTLSdk.getInstance().listener = this

        // 3. Create and add webview
        val webView = RTLSdk.getInstance().createWebView(this)
        webViewContainer.addView(webView)
        rtlWebView = webView
    }

    fun onLoginButtonClicked() {
        lifecycleScope.launch {
            // 4. Request token and login
            val success = RTLSdk.getInstance().requestTokenAndLogin()
            if (success) {
                // Show webview, hide login UI
                webViewContainer.visibility = View.VISIBLE
            }
        }
    }

    // RTLSdkListener implementation

    override suspend fun onNeedsToken(): String? {
        // Return JWT token from your auth system
        return MyAuthService.getToken()
    }

    override fun onAuthenticated(accessToken: String, refreshToken: String) {
        Log.d("RTL", "Authenticated!")
    }

    override fun onLogout() {}
    override fun onOpenUrl(url: String, forceExternal: Boolean) {}
    override fun onReady() {}
}
```

## Integration Guide

### Step 1: Initialize the SDK

Initialize the SDK early in your app's lifecycle, typically in `onCreate()`:

```kotlin
RTLSdk.getInstance().initialize(
    program = "your-program-id",    // Your RTL program identifier
    environment = RTLEnvironment.STAGING,  // STAGING or PRODUCTION
    urlScheme = "your-app-scheme",  // Your app's URL scheme for deep linking
    context = this                   // Activity context
)
```

### Step 2: Set the Listener

Set the listener **before** creating the webview. This is important because the SDK may immediately request a token.

```kotlin
RTLSdk.getInstance().listener = this
```

### Step 3: Create the WebView

Create the RTL webview and add it to your view hierarchy:

```kotlin
val rtlWebView = RTLSdk.getInstance().createWebView(this)
webViewContainer.addView(rtlWebView)
```

Layout XML example:
```xml
<FrameLayout
    android:id="@+id/webViewContainer"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fitsSystemWindows="true" />
```

### Step 4: Implement Token Provider

Implement `onNeedsToken()` to provide tokens when the SDK needs them:

```kotlin
override suspend fun onNeedsToken(): String? {
    // Fetch token from your authentication service
    return try {
        MyAuthService.fetchJWTToken()
    } catch (e: Exception) {
        Log.e("RTL", "Failed to get token: ${e.message}")
        null
    }
}
```

This method is called:
- When you call `requestTokenAndLogin()`
- Automatically when the app returns to foreground after 20+ hours (token refresh)

### Step 5: Trigger Login

When the user is ready to access the RTL experience:

```kotlin
lifecycleScope.launch {
    val success = RTLSdk.getInstance().requestTokenAndLogin()
    if (success) {
        // Show the webview, hide login UI
        webViewContainer.visibility = View.VISIBLE
    } else {
        // Handle login failure
        Toast.makeText(this@MainActivity, "Login failed", Toast.LENGTH_SHORT).show()
    }
}
```

## API Reference

### RTLSdk

The main SDK singleton.

#### Properties

| Property | Type | Description |
|----------|------|-------------|
| `listener` | `RTLSdkListener?` | Listener for receiving SDK events |

#### Methods

##### `getInstance()`
Returns the singleton instance of RTLSdk.

```kotlin
fun getInstance(): RTLSdk
```

##### `initialize(program, environment, urlScheme, context)`
Initialize the SDK with configuration. Must be called before any other SDK methods.

```kotlin
fun initialize(
    program: String,
    environment: RTLEnvironment,
    urlScheme: String,
    context: Activity
)
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `program` | `String` | Your RTL program identifier (e.g., "crowdplay") |
| `environment` | `RTLEnvironment` | `STAGING` or `PRODUCTION` |
| `urlScheme` | `String` | Your app's URL scheme for deep linking |
| `context` | `Activity` | Activity context for initialization |

##### `createWebView(context)`
Creates and returns an RTL webview to embed in your view hierarchy.

```kotlin
fun createWebView(context: Context): RTLWebView
```

##### `requestTokenAndLogin()`
Requests a token from the listener and performs login. This is the recommended way to initiate login.

```kotlin
suspend fun requestTokenAndLogin(): Boolean
```

**Returns:** `true` if login succeeded, `false` if failed or no token provided.

##### `login(token)`
Performs login with a provided JWT token. Consider using `requestTokenAndLogin()` instead.

```kotlin
suspend fun login(token: String): Boolean
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `token` | `String` | JWT token from your authentication system |

**Returns:** `true` if login succeeded (userAuth received), `false` if failed/timed out (30 seconds).

##### `logout()`
Triggers logout in the webview.

```kotlin
fun logout()
```

##### `registerPushToken(token, type)`
Registers a push notification token with the RTL backend.

```kotlin
fun registerPushToken(token: String, type: RTLTokenType)
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `token` | `String` | The device push token |
| `type` | `RTLTokenType` | `APNS` or `FCM` |

##### `isLoggedIn()`
Returns the current login state.

```kotlin
fun isLoggedIn(): Boolean?
```

**Returns:** `true` if logged in, `false` if not logged in, `null` if SDK not initialized.

### RTLSdkListener

Interface for receiving SDK events.

```kotlin
interface RTLSdkListener {

    /** Called when SDK needs a token (initial login or refresh) */
    suspend fun onNeedsToken(): String?

    /** Called when authentication succeeds */
    fun onAuthenticated(accessToken: String, refreshToken: String)

    /** Called when user logs out */
    fun onLogout()

    /** Called when RTL requests to open a URL */
    fun onOpenUrl(url: String, forceExternal: Boolean)

    /** Called when RTL web app is ready */
    fun onReady()
}
```

#### Listener Methods

| Method | Description |
|--------|-------------|
| `onNeedsToken()` | **Required for login.** Return a JWT token or `null` if unavailable. |
| `onAuthenticated(accessToken, refreshToken)` | Called when user successfully authenticates. |
| `onLogout()` | Called when user logs out. |
| `onOpenUrl(url, forceExternal)` | Called when RTL requests to open a URL. Open in browser if `forceExternal` is true. |
| `onReady()` | Called when the RTL web app has finished loading. |

### RTLSdkListenerAdapter

Adapter class with default empty implementations for all listener methods. Extend this if you only need to implement some methods:

```kotlin
class MyListener : RTLSdkListenerAdapter() {
    override suspend fun onNeedsToken(): String? {
        return myAuthService.getToken()
    }

    override fun onAuthenticated(accessToken: String, refreshToken: String) {
        Log.d("RTL", "Authenticated!")
    }
}
```

### RTLEnvironment

Environment configuration enum.

| Case | Domain Pattern |
|------|----------------|
| `STAGING` | `{program}.staging.getboon.com` |
| `PRODUCTION` | `{program}.prod.getboon.com` |

### RTLTokenType

Push notification token type enum.

| Case | Value | Description |
|------|-------|-------------|
| `APNS` | `"apns"` | Apple Push Notification Service |
| `FCM` | `"fcm"` | Firebase Cloud Messaging |

### RTLWebView

Embeddable webview for the RTL experience.

#### Methods

| Method | Description |
|--------|-------------|
| `reload()` | Reload the current page |
| `goBack()` | Navigate back in history |
| `goForward()` | Navigate forward in history |
| `canGoBack()` | Check if can go back |
| `canGoForward()` | Check if can go forward |

## Token Management

The SDK automatically manages token expiration:

- Tokens are considered valid for **20 hours**
- When the app returns to foreground after 20+ hours, the SDK automatically calls `onNeedsToken()` to get a fresh token
- The webview is automatically reloaded with the new token

This ensures users always have a valid session without manual intervention.

## Handling External URLs

When the RTL web app needs to open an external URL, implement `onOpenUrl`:

```kotlin
override fun onOpenUrl(url: String, forceExternal: Boolean) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    startActivity(intent)
}
```

For more control, you can use `CustomTabsIntent` for in-app browser:

```kotlin
override fun onOpenUrl(url: String, forceExternal: Boolean) {
    if (forceExternal) {
        // Must open in external browser
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    } else {
        // Can use Chrome Custom Tabs for in-app experience
        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.launchUrl(this, Uri.parse(url))
    }
}
```

## Example Implementation

See the `example/` directory for a complete example showing:
- SDK initialization
- Login flow with token callback
- Full-screen webview presentation
- Listener implementation

To run the example:
1. Open the project in Android Studio
2. Select the `example` module
3. Run on device or emulator

## Troubleshooting

### WebView shows blank screen
- Ensure you've called `initialize()` before `createWebView()`
- Verify `onNeedsToken()` returns a valid token
- Check Logcat for `[RTLSdk]` logs

### Login times out
- Login has a 30-second timeout
- Ensure the JWT token is valid and not expired
- Check network connectivity

### Token refresh not working
- Ensure the listener is set and `onNeedsToken()` is implemented
- Token refresh only triggers after 20+ hours in background

### Status bar overlapping WebView
Add `android:fitsSystemWindows="true"` to your webview container, or handle window insets:

```kotlin
ViewCompat.setOnApplyWindowInsetsListener(webViewContainer) { view, windowInsets ->
    val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
    view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
    WindowInsetsCompat.CONSUMED
}
```

## License

Copyright (c) 2024 Affina Loyalty. All rights reserved.

This SDK is provided under a proprietary license. Use of this SDK requires a valid business agreement with Affina Loyalty. Unauthorized copying, modification, distribution, or use of this software is strictly prohibited.
