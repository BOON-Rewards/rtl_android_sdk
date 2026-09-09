package com.affinaloyalty.rtlsdk

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/** Resolve the current WebView host, including themed/mutable context wrappers. */
internal fun foregroundLocationActivity(context: Context?): Activity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is Activity) {
            return current.takeUnless { it.isFinishing || it.isDestroyed }
        }
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return null
}

/** One foreground fix, with no Play Services or hyperlocal module dependency. */
internal class RTLForegroundLocation(
    private val activity: () -> Activity?,
    private val requestPermissions: (Activity, Array<String>, Int) -> Unit
) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val signals = mutableListOf<CancellationSignal>()
    private var completion: ((Map<String, Any>) -> Unit)? = null
    private val timeout = Runnable { finish(mapOf("error" to "timeout")) }

    fun request(completion: (Map<String, Any>) -> Unit) {
        val host = activity()
        if (this.completion != null || host == null || host.isFinishing || host.isDestroyed ||
            !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            completion(mapOf("error" to "unavailable"))
            return
        }
        this.completion = completion
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        handler.postDelayed(timeout, 30_000)
        if (hasPermission(host)) {
            locate(host)
        } else {
            // Core does not add location permissions to a host's manifest.
            val declared = host.packageManager.getPackageInfo(host.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty()
            val permissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                .filter { it in declared }.toTypedArray()
            if (permissions.isEmpty()) finish(mapOf("error" to "unavailable"))
            else requestPermissions(host, permissions, REQUEST_CODE)
        }
    }

    fun handlePermissionResult(requestCode: Int): Boolean {
        if (requestCode != REQUEST_CODE) return false
        if (completion == null) return true
        val host = activity()
        if (host != null && hasPermission(host)) locate(host)
        else finish(mapOf("error" to "permissionDenied"))
        return true
    }

    private fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun locate(host: Activity) {
        if (completion == null || signals.isNotEmpty()) return
        val manager = host.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null || !LocationManagerCompat.isLocationEnabled(manager)) {
            finish(mapOf("error" to "unavailable"))
            return
        }
        try {
            val fine = ContextCompat.checkSelfPermission(host, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val providers = manager.getProviders(true).filter {
                it == LocationManager.NETWORK_PROVIDER || (fine && it == LocationManager.GPS_PROVIDER)
            }
            if (providers.isEmpty()) {
                finish(mapOf("error" to "unavailable"))
                return
            }
            // Race available providers and cancel the others after the first fix.
            var remaining = providers.size
            for (provider in providers) {
                if (completion == null) break
                val signal = CancellationSignal()
                signals.add(signal)
                LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(host)) { location ->
                    if (completion != null) {
                        remaining -= 1
                        if (location != null) {
                            finish(mapOf("lat" to location.latitude, "long" to location.longitude))
                        } else if (remaining == 0) finish(mapOf("error" to "unavailable"))
                    }
                }
            }
        } catch (_: SecurityException) {
            finish(mapOf("error" to "permissionDenied"))
        } catch (_: IllegalArgumentException) {
            finish(mapOf("error" to "unavailable"))
        }
    }

    override fun onStop(owner: LifecycleOwner) { finish(mapOf("error" to "unavailable")) }

    fun cancel() {
        completion = null
        handler.removeCallbacks(timeout)
        val pendingSignals = signals.toList()
        signals.clear()
        pendingSignals.forEach { it.cancel() }
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
    }

    private fun finish(fields: Map<String, Any>) {
        val callback = completion
        cancel()
        callback?.invoke(fields)
    }

    companion object { const val REQUEST_CODE = 7241 }
}
