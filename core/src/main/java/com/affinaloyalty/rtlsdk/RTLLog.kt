package com.affinaloyalty.rtlsdk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log

/**
 * Where a log line came from. Becomes the logcat tag, so lines can be filtered
 * structurally rather than by matching message text.
 *
 * logcat has one tag field where Apple's unified log has two, a subsystem and a
 * category, so both are folded into it. Every tag starts `RTLSdk-`, which makes
 * the SDK's whole output one filter for a host that wants it all, and what
 * follows is the area:
 *
 * ```
 * adb logcat --regex 'RTLSdk-'      # everything the SDK writes
 * adb logcat -s RTLSdk-Bridge       # one area
 * ```
 *
 * Tags stay under 23 characters. [Log.isLoggable] throws above that on API 23,
 * which the SDK still supports, so a longer area name would crash the check
 * meant to keep the log quiet.
 */
enum class RTLLogArea(val tag: String) {
    CORE("RTLSdk-Core"),
    WEB_VIEW("RTLSdk-WebView"),
    BRIDGE("RTLSdk-Bridge"),
    LOCATION("RTLSdk-Location"),
    GEOFENCE("RTLSdk-Geofence"),
    NOTIFICATIONS("RTLSdk-Notifications"),
    HAPTICS("RTLSdk-Haptics"),
    STORE("RTLSdk-Store")
}

/**
 * The SDK's logging.
 *
 * Everything the SDK writes goes through here, on [Log] with a tag per area.
 * The alternative the SDK used to do in places, `println`, reaches logcat only
 * because Android reroutes stdout: it arrives tagged `System.out` at INFO, so
 * it cannot be filtered, cannot be levelled, and cannot be turned off. On a
 * device where stdout is not rerouted it goes nowhere at all.
 *
 * Messages are built lazily, inside a lambda, so a line that will not be
 * written costs nothing beyond the check.
 */
object RTLLog {

    @Volatile
    private var includeUrlDetails = false

    /**
     * The lowest priority the SDK writes. [Log.INFO] by default, so debug
     * output stays out of a host's logcat until someone asks for it.
     *
     * Two ways to ask. In code, `RTLLog.level = Log.DEBUG`. Outside it,
     * `adb shell setprop log.tag.RTLSdk-Bridge VERBOSE` turns on one area
     * without a rebuild; that is the platform's own mechanism and is honoured
     * here for that reason. Because the property can only widen what is
     * written, raising this above INFO does not silence what Android already
     * considers loggable.
     */
    @Volatile
    var level: Int = Log.INFO

    fun v(area: RTLLogArea, message: () -> String) =
        emit(Log.VERBOSE, area, null, message)

    fun d(area: RTLLogArea, message: () -> String) =
        emit(Log.DEBUG, area, null, message)

    fun i(area: RTLLogArea, message: () -> String) =
        emit(Log.INFO, area, null, message)

    fun w(area: RTLLogArea, throwable: Throwable? = null, message: () -> String) =
        emit(Log.WARN, area, throwable, message)

    fun e(area: RTLLogArea, throwable: Throwable? = null, message: () -> String) =
        emit(Log.ERROR, area, throwable, message)

    /**
     * Enables complete URL diagnostics for debuggable host apps. Published SDK
     * artifacts are release builds even when a developer installs them into a
     * debug app, so this must follow the host's debuggable flag rather than the
     * SDK library's build variant.
     */
    internal fun configureFor(context: Context) {
        includeUrlDetails =
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    /**
     * Shows a complete URL in a debuggable host and removes its query and
     * fragment in production. Login credentials, coordinates and one-time
     * flow tokens can travel in those components.
     */
    fun url(value: String?): String {
        if (value == null) return "null"
        if (includeUrlDetails) return value

        val uri = Uri.parse(value)
        val scheme = uri.scheme ?: return "<invalid URL>"
        val authority = uri.authority?.substringAfterLast('@') ?: return "<invalid URL>"
        val safeUrl = "$scheme://$authority${uri.encodedPath.orEmpty()}"
        if (uri.query == null && uri.fragment == null && uri.userInfo == null) return safeUrl

        return "$safeUrl (URL details omitted)"
    }

    private fun emit(
        priority: Int,
        area: RTLLogArea,
        throwable: Throwable?,
        message: () -> String
    ) {
        if (priority < level && !Log.isLoggable(area.tag, priority)) return

        val text = message()
        Log.println(
            priority,
            area.tag,
            if (throwable == null) text else "$text\n${Log.getStackTraceString(throwable)}"
        )
    }
}
