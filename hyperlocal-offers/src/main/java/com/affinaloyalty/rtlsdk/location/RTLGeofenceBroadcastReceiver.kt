package com.affinaloyalty.rtlsdk.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.affinaloyalty.rtlsdk.RTLSdk
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.affinaloyalty.rtlsdk.RTLLog
import com.affinaloyalty.rtlsdk.RTLLogArea.GEOFENCE

/**
 * Broadcast receiver for geofence transition events
 */
class RTLGeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        RTLLog.i(GEOFENCE) { "Broadcast received: geofence event" }

        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            RTLLog.e(GEOFENCE) { "Received a null geofencing event" }
            return
        }

        if (event.hasError()) {
            RTLLog.e(GEOFENCE) { "Geofencing error code: ${event.errorCode}" }
            return
        }

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                RTLLog.i(GEOFENCE) { "Geofence enter transition" }
                val geofences = event.triggeringGeofences
                RTLLog.d(GEOFENCE) { "Triggered ${geofences?.size ?: 0} geofence(s)" }
                geofences?.forEach { geofence ->
                    RTLLog.d(GEOFENCE) { "Processing geofence ${geofence.requestId}" }
                    RTLSdk.getInstance().handleGeofenceEnter(geofence.requestId)
                }
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                RTLLog.d(GEOFENCE) { "Geofence exit, not handled" }
            }
            else -> {
                RTLLog.w(GEOFENCE) { "Unknown geofence transition: ${event.geofenceTransition}" }
            }
        }
    }
}
