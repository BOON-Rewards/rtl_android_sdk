package com.affinaloyalty.rtlsdk

/**
 * Listener interface for receiving RTL SDK events
 */
interface RTLSdkListener {

    /**
     * Called when the RTL experience starts or stops loading.
     *
     * The SDK sends `true` before requesting authentication and `false` when
     * the web app is ready or the attempt ends. Hosts can use this one callback
     * for both the initial presentation and later session recovery. It is
     * always called on the main thread.
     */
    fun onLoadingStateChanged(isLoading: Boolean) {}

    /**
     * Called when the RTL web app has finished loading and is ready
     */
    fun onReady() {}

    /**
     * Provides a freshly signed JWT when the SDK establishes or renews authentication.
     * The host should fetch it from its backend and must not sign it in the app.
     *
     * @return JWT token string, or null if unavailable
     */
    suspend fun provideAuthToken(): String?
}
