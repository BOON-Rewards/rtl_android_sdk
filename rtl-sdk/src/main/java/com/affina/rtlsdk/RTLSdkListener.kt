package com.affina.rtlsdk

/**
 * Listener interface for receiving RTL SDK events
 */
interface RTLSdkListener {

    /**
     * Called when user authentication succeeds
     *
     * @param accessToken The access token from authentication
     * @param refreshToken The refresh token from authentication
     */
    fun onAuthenticated(accessToken: String, refreshToken: String)

    /**
     * Called when user logs out
     */
    fun onLogout()

    /**
     * Called when the RTL web app requests opening a URL
     *
     * @param url The URL to open
     * @param forceExternal If true, should open in external browser; otherwise can use in-app browser
     */
    fun onOpenUrl(url: String, forceExternal: Boolean)

    /**
     * Called when the RTL web app has finished loading and is ready
     */
    fun onReady()

    /**
     * Called when SDK needs a fresh token from the host app.
     * This is called on initial login and when token expires after 20 hours.
     *
     * @return JWT token string, or null if unavailable
     */
    suspend fun onNeedsToken(): String?
}

/**
 * Adapter class with default implementations for RTLSdkListener
 */
open class RTLSdkListenerAdapter : RTLSdkListener {
    override fun onAuthenticated(accessToken: String, refreshToken: String) {}
    override fun onLogout() {}
    override fun onOpenUrl(url: String, forceExternal: Boolean) {}
    override fun onReady() {}
    override suspend fun onNeedsToken(): String? = null
}
