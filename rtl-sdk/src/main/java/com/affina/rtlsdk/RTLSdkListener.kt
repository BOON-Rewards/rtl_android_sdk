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
}

/**
 * Adapter class with default implementations for RTLSdkListener
 */
open class RTLSdkListenerAdapter : RTLSdkListener {
    override fun onAuthenticated(accessToken: String, refreshToken: String) {}
    override fun onLogout() {}
    override fun onOpenUrl(url: String, forceExternal: Boolean) {}
    override fun onReady() {}
}
