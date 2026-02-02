# RTL SDK for Android

Native Android SDK for integrating the RTL (Rewards, Transactions, Loyalty) experience into your Android application.

## Requirements

- Android API 23+ (Android 6.0+)
- Kotlin 1.9+
- Android Gradle Plugin 8.2+

## Installation

### Gradle

Add the Maven repository and dependency to your app's `build.gradle.kts`:

```kotlin
repositories {
    maven { url = uri("https://your-maven-repo.com") }
}

dependencies {
    implementation("com.affina:rtl-sdk:1.0.0")
}
```

### Local Development

For local development, add the SDK module to your project:

1. Clone this repository
2. In your `settings.gradle.kts`:
   ```kotlin
   include(":rtl-sdk")
   project(":rtl-sdk").projectDir = file("path/to/rtl_sdk_android/rtl-sdk")
   ```
3. Add the dependency:
   ```kotlin
   implementation(project(":rtl-sdk"))
   ```

## Usage

### Initialization

Initialize the SDK early in your Activity's lifecycle:

```kotlin
import com.affina.rtlsdk.RTLSdk
import com.affina.rtlsdk.RTLEnvironment

// Initialize with your configuration
RTLSdk.getInstance().initialize(
    program = "your-program-id",
    environment = RTLEnvironment.STAGING,  // or PRODUCTION
    urlScheme = "your-app-scheme",
    context = this  // Activity context
)

// Set listener to receive events
RTLSdk.getInstance().listener = this
```

### Embedding the WebView

Create and embed the RTL webview in your layout:

```kotlin
val rtlWebView = RTLSdk.getInstance().createWebView(this)
webViewContainer.addView(rtlWebView)
```

Or programmatically with LayoutParams:

```kotlin
val rtlWebView = RTLSdk.getInstance().createWebView(this)
rtlWebView.layoutParams = FrameLayout.LayoutParams(
    FrameLayout.LayoutParams.MATCH_PARENT,
    FrameLayout.LayoutParams.MATCH_PARENT
)
container.addView(rtlWebView)
```

### Login

Login with a JWT token from your authentication system. The `login` method is a suspend function and returns when authentication completes or times out:

```kotlin
lifecycleScope.launch {
    val success = RTLSdk.getInstance().login(jwtToken)
    if (success) {
        println("Login successful!")
    } else {
        println("Login failed or timed out")
    }
}
```

### Check Login State

```kotlin
val isLoggedIn = RTLSdk.getInstance().isLoggedIn()
// Returns: true (logged in), false (not logged in), or null (not initialized)
```

### Logout

```kotlin
RTLSdk.getInstance().logout()
```

### Register Push Token

Register your device's FCM push notification token:

```kotlin
RTLSdk.getInstance().registerPushToken(fcmToken, RTLTokenType.FCM)
```

### Listener

Implement `RTLSdkListener` to receive SDK events:

```kotlin
class MainActivity : AppCompatActivity(), RTLSdkListener {

    override fun onAuthenticated(accessToken: String, refreshToken: String) {
        // User successfully authenticated
    }

    override fun onLogout() {
        // User logged out
    }

    override fun onOpenUrl(url: String, forceExternal: Boolean) {
        // RTL requests to open a URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    override fun onReady() {
        // RTL web app finished loading
    }
}
```

Alternatively, use `RTLSdkListenerAdapter` to only override the methods you need:

```kotlin
RTLSdk.getInstance().listener = object : RTLSdkListenerAdapter() {
    override fun onAuthenticated(accessToken: String, refreshToken: String) {
        println("Authenticated!")
    }
}
```

## Environment

The SDK supports two environments:

- `RTLEnvironment.STAGING` - Connects to `{program}.staging.getboon.com`
- `RTLEnvironment.PRODUCTION` - Connects to `{program}.prod.getboon.com`

## Permissions

The SDK requires the `INTERNET` permission, which is included in the library's manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## URL Scheme

To handle deep links from RTL, add an intent filter to your Activity:

```xml
<activity android:name=".MainActivity">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="your-app-scheme" />
    </intent-filter>
</activity>
```

## Example App

See the `example` directory for a complete example implementation.

## Publishing

To publish to a Maven repository:

```bash
./gradlew :rtl-sdk:publish
```

Configure your repository credentials in `gradle.properties` or environment variables.

## License

Copyright (c) 2024 Affina Loyalty. All rights reserved.

This SDK is provided under a proprietary license. Use of this SDK requires a valid business agreement with Affina Loyalty. Unauthorized copying, modification, distribution, or use of this software is strictly prohibited.

For licensing inquiries, contact: [your-contact-email]
