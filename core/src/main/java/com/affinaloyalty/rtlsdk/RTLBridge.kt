package com.affinaloyalty.rtlsdk

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.affinaloyalty.rtlsdk.RTLLogArea.BRIDGE
import org.json.JSONObject
import java.net.URI

internal class RTLBridge(
    private val context: Context,
    private val sdk: RTLSdk?,
    private val hapticEngine: RTLHapticEngine,
    private val allowedOriginRules: Set<String>
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var isActive = true
    private var foregroundLocation: RTLForegroundLocation? = null

    fun invalidate() {
        isActive = false
        foregroundLocation?.cancel()
        foregroundLocation = null
    }

    fun handlePermissionResult(requestCode: Int): Boolean =
        foregroundLocation?.handlePermissionResult(requestCode) ?: false

    fun install(webView: WebView) {
        this.webView = webView
        if (allowedOriginRules.isEmpty()) {
            RTLLog.w(BRIDGE) { "JavaScript bridge disabled: no trusted web origin configured" }
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            RTLLog.w(BRIDGE) { "JavaScript bridge disabled: the installed WebView is too old" }
            return
        }

        WebViewCompat.addWebMessageListener(
            webView,
            NAME,
            allowedOriginRules,
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy
                ) {
                    if (!isActive) return
                    if (!isMainFrame || sdk?.isAllowedWebUrl(sourceOrigin) != true) {
                        RTLLog.w(BRIDGE) { "Ignoring a bridge message from an untrusted frame" }
                        return
                    }
                    message.data?.let(::handleWebMessage)
                }
            }
        )
    }

    fun sendToWeb(type: RTLNativeMessageType, fields: Map<String, Any> = emptyMap()) {
        try {
            val payload = serializeNativeMessage(type, fields)
            RTLLog.d(BRIDGE) { "Sending message to web app: ${type.wireName}" }
            mainHandler.post {
                if (!isActive) return@post
                if (type == RTLNativeMessageType.LOCATION_RESULT &&
                    webView?.url?.let { sdk?.isAllowedWebUrl(Uri.parse(it)) } != true
                ) return@post
                webView?.evaluateJavascript(
                    "window.postMessage($payload, window.location.origin)",
                    null
                )
            }
        } catch (error: Exception) {
            RTLLog.e(BRIDGE, error) { "Failed to serialize a bridge message" }
        }
    }

    private fun handleWebMessage(rawMessage: String) {
        val message = try {
            RTLWebMessage.parse(rawMessage)
        } catch (error: Exception) {
            RTLLog.e(BRIDGE, error) { "Rejected an invalid bridge message" }
            return
        }
        if (message == null) {
            RTLLog.w(BRIDGE) { "Ignoring an unknown bridge message" }
            return
        }

        RTLLog.i(BRIDGE) { "Received message from web app: ${message.wireName}" }
        mainHandler.post {
            // The document may have been retired while this message was queued.
            if (!isActive) return@post
            when (message) {
                is RTLWebMessage.OpenExternalUrl -> sdk?.handleOpenUrl(
                    message.url,
                    message.surface
                )
                RTLWebMessage.AuthFailed -> sdk?.handleAuthFailure()
                RTLWebMessage.UserLogout -> sdk?.handleUserLogoutReceived()
                RTLWebMessage.AppReady -> sdk?.handleAppReady()
                RTLWebMessage.SessionExpired -> sdk?.handleSessionExpired()
                RTLWebMessage.RequestLocationPermission -> {
                    sdk?.handleLocationPermissionRequest(context as? Activity)
                }
                is RTLWebMessage.RequestLocation -> {
                    if (foregroundLocation == null) {
                        foregroundLocation = RTLForegroundLocation(
                            { foregroundLocationActivity(webView?.context) },
                            { host, permissions, code -> sdk?.requestPermissions(host, permissions, code) }
                        )
                    }
                    foregroundLocation?.request { fields ->
                        sendToWeb(RTLNativeMessageType.LOCATION_RESULT, fields + ("requestId" to message.requestId))
                    }
                }
                is RTLWebMessage.HapticPlay -> hapticEngine.play(message.pattern)
                RTLWebMessage.RequestNativeCapabilities -> sendCapabilities()
            }
        }
    }

    private fun sendCapabilities() {
        sendToWeb(
            RTLNativeMessageType.NATIVE_CAPABILITIES,
            mapOf(
                "capabilities" to mapOf(
                    "haptics" to hapticEngine.capabilities(),
                    "unifiedDeepLinks" to true
                )
            )
        )
    }

    companion object {
        const val NAME = "inappwebview"
    }
}

internal sealed interface RTLWebMessage {
    val wireName: String

    data class OpenExternalUrl(
        val url: String,
        val surface: RTLSurface
    ) : RTLWebMessage {
        override val wireName = "openExternalUrl"
    }

    object AuthFailed : RTLWebMessage { override val wireName = "authFailed" }
    object UserLogout : RTLWebMessage { override val wireName = "userLogout" }
    object AppReady : RTLWebMessage { override val wireName = "appReady" }
    object SessionExpired : RTLWebMessage { override val wireName = "sessionExpired" }
    object RequestLocationPermission : RTLWebMessage {
        override val wireName = "requestLocationPermission"
    }
    data class RequestLocation(val requestId: String) : RTLWebMessage {
        override val wireName = "requestLocation"
    }
    data class HapticPlay(val pattern: RTLHapticPattern) : RTLWebMessage {
        override val wireName = "hapticPlay"
    }
    object RequestNativeCapabilities : RTLWebMessage {
        override val wireName = "requestNativeCapabilities"
    }

    companion object {
        fun parse(rawMessage: String): RTLWebMessage? {
            val message = JSONObject(rawMessage)
            return when (message.optString("type", "")) {
                "openExternalUrl" -> {
                    val url = (message.opt("URL") as? String)?.takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException("URL must be a non-empty string")
                    // Keep validating the released CrowdPlay wire contract, but
                    // current SDK routing is controlled only by surface.
                    if (message.opt("forceExternalBrowser") !is Boolean) {
                        throw IllegalArgumentException("forceExternalBrowser must be a boolean")
                    }
                    val surface = RTLSurface.from(message.opt("surface") as? String)
                        ?: throw IllegalArgumentException("surface must be a known value")
                    if (message.has("merchantName") && message.opt("merchantName") !is String) {
                        throw IllegalArgumentException("merchantName must be a string")
                    }
                    OpenExternalUrl(url, surface)
                }
                "authFailed" -> AuthFailed
                "userLogout" -> UserLogout
                "appReady" -> AppReady
                "sessionExpired" -> SessionExpired
                "requestLocationPermission" -> RequestLocationPermission
                "requestLocation" -> {
                    val requestId = message.opt("requestId") as? String
                    require(!requestId.isNullOrEmpty() && requestId.length <= 128) { "requestId must be 1 to 128 characters" }
                    RequestLocation(requestId)
                }
                "hapticPlay" -> HapticPlay(
                    RTLHapticPattern.parse(message.opt("payload") as? JSONObject)
                )
                "requestNativeCapabilities" -> RequestNativeCapabilities
                else -> null
            }
        }
    }
}

internal enum class RTLNativeMessageType(val wireName: String) {
    NATIVE_CAPABILITIES("nativeCapabilities"),
    LOGOUT_REQUESTED("logoutRequested"),
    LOCATION_PERMISSION_STATUS("locationPermissionStatus"),
    LOCATION_UPDATE("locationUpdate"),
    LOCATION_RESULT("locationResult"),
    OVERLAY_COMPLETED("overlayCompleted");
}

internal fun serializeNativeMessage(
    type: RTLNativeMessageType,
    fields: Map<String, Any> = emptyMap()
): String = JSONObject(fields + ("type" to type.wireName)).toString()

internal fun webMessageOriginRule(urlString: String): String? {
    val url = try {
        URI(urlString)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val scheme = url.scheme?.lowercase() ?: return null
    val host = url.host ?: return null
    if (scheme != "https" && scheme != "http") return null

    val formattedHost = when {
        host.startsWith("[") -> host
        ':' in host -> "[$host]"
        else -> host
    }
    val port = if (url.port == -1) "" else ":${url.port}"
    return "$scheme://$formattedHost$port"
}
