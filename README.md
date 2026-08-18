# RTL SDK for Android

See the public RTL developer documentation:

https://crowdplay.gitbook.io/rtl-developer-documentation

## Configuration

Initialize the SDK with the complete base URL provided for the client. The SDK
does not select an environment or construct a host name.

```kotlin
RTLSdk.getInstance().initialize(
    baseUrl = "https://client-provided-url.example",
    urlScheme = "your-app-scheme",
    context = activity,
    listener = listener
)
```
