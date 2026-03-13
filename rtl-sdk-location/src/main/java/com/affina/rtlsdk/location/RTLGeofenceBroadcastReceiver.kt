package com.affina.rtlsdk.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.affina.rtlsdk.RTLSdk
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Broadcast receiver for geofence transition events
 */
class RTLGeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RTLGeofenceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        println("[$TAG] ========================================")
        println("[$TAG] 📡 BROADCAST RECEIVED - Geofence Event!")
        println("[$TAG] ========================================")

        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            println("[$TAG] ❌ Received null geofencing event")
            return
        }

        if (event.hasError()) {
            println("[$TAG] ❌ Geofencing error code: ${event.errorCode}")
            return
        }

        val triggeringLocation = event.triggeringLocation
        println("[$TAG] 📍 Triggering location: lat=${triggeringLocation?.latitude}, lng=${triggeringLocation?.longitude}")

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                println("[$TAG] 🚨 GEOFENCE_TRANSITION_ENTER detected!")
                val geofences = event.triggeringGeofences
                println("[$TAG]    Triggered ${geofences?.size ?: 0} geofence(s)")
                geofences?.forEach { geofence ->
                    println("[$TAG]    → Processing geofence: ${geofence.requestId}")
                    RTLSdk.getInstance().handleGeofenceEnter(geofence.requestId)
                }
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                println("[$TAG] 🚶 Geofence exit (not handled)")
            }
            else -> {
                println("[$TAG] ❓ Unknown geofence transition: ${event.geofenceTransition}")
            }
        }
        println("[$TAG] ========================================")
    }
}
