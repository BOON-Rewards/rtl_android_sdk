package com.affinaloyalty.rtlsdk.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.affinaloyalty.rtlsdk.RTLStore
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.affinaloyalty.rtlsdk.RTLLog
import com.affinaloyalty.rtlsdk.RTLLogArea.GEOFENCE

/**
 * Manages geofences around stores
 */
internal class RTLGeofenceManager(private val context: Context) {

    companion object {
        private const val GEOFENCE_RADIUS_METERS = 100f
        private const val MAX_GEOFENCES = 100 // Android allows 100 per app
    }

    private val geofencingClient: GeofencingClient =
        LocationServices.getGeofencingClient(context)

    private val monitoredStores = mutableMapOf<String, RTLStore>()

    var onGeofenceEnter: ((RTLStore) -> Unit)? = null

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, RTLGeofenceBroadcastReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /**
     * Update geofences for the given stores
     *
     * @param stores The list of stores to monitor
     */
    @SuppressLint("MissingPermission")
    fun updateGeofences(stores: List<RTLStore>) {
        RTLLog.i(GEOFENCE) { "Setting up geofences for ${stores.size} stores" }

        // Remove existing geofences
        val existingIds = monitoredStores.keys.toList()
        if (existingIds.isNotEmpty()) {
            RTLLog.d(GEOFENCE) { "Removing ${existingIds.size} existing geofences" }
            geofencingClient.removeGeofences(existingIds)
                .addOnSuccessListener {
                    RTLLog.d(GEOFENCE) { "Removed ${existingIds.size} existing geofences" }
                }
                .addOnFailureListener { e ->
                    RTLLog.e(GEOFENCE, e) { "Failed to remove geofences" }
                }
        }
        monitoredStores.clear()

        // Add new geofences (max 100 on Android)
        val storesToMonitor = stores.take(MAX_GEOFENCES)

        if (storesToMonitor.isEmpty()) {
            RTLLog.w(GEOFENCE) { "No stores to monitor" }
            return
        }

        RTLLog.i(GEOFENCE) { "Creating ${storesToMonitor.size} geofences (radius=${GEOFENCE_RADIUS_METERS}m)" }
        val geofences = storesToMonitor.map { store ->
            monitoredStores[store.id] = store
            Geofence.Builder()
                .setRequestId(store.id)
                .setCircularRegion(
                    store.latitude,
                    store.longitude,
                    GEOFENCE_RADIUS_METERS
                )
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        geofencingClient.addGeofences(request, geofencePendingIntent)
            .addOnSuccessListener {
                RTLLog.i(GEOFENCE) { "Geofence monitoring started for ${storesToMonitor.size} stores" }
            }
            .addOnFailureListener { e ->
                RTLLog.e(GEOFENCE, e) { "Failed to add geofences" }
            }
    }

    /**
     * Stop all geofence monitoring
     */
    fun stopMonitoring() {
        val storeIds = monitoredStores.keys.toList()
        if (storeIds.isNotEmpty()) {
            geofencingClient.removeGeofences(storeIds)
        }
        monitoredStores.clear()
        RTLLog.i(GEOFENCE) { "Stopped all geofence monitoring" }
    }

    /**
     * Get a store by its ID
     *
     * @param id The store ID
     * @return The store if found, null otherwise
     */
    fun getStore(id: String): RTLStore? = monitoredStores[id]

    /**
     * Handle geofence entry event
     *
     * @param storeId The ID of the store whose geofence was entered
     */
    fun handleGeofenceEnter(storeId: String) {
        RTLLog.i(GEOFENCE) { "Geofence entered: storeId=$storeId" }
        val store = monitoredStores[storeId]
        if (store == null) {
            RTLLog.w(GEOFENCE) { "Store not found in monitored stores for id: $storeId" }
            return
        }
        RTLLog.d(GEOFENCE) { "Matched the entered geofence to a hyperlocal offer" }
        onGeofenceEnter?.invoke(store)
    }
}
