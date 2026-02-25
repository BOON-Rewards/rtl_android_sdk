package com.affina.rtlsdk.location

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.affina.rtlsdk.RTLStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/**
 * Manages local notifications for store geofence entries
 */
internal class RTLNotificationManager(private val context: Context) {

    companion object {
        private const val TAG = "RTLNotificationManager"
        private const val CHANNEL_ID = "rtl_store_notifications"
        private const val CHANNEL_NAME = "Store Notifications"
        private const val PREFS_NAME = "rtl_notification_history"
        private const val HISTORY_KEY = "notification_records"

        // Rate limiting rules
        private const val DAILY_LIMIT = 2
        private const val WEEKLY_LIMIT = 7
        private const val MONTHLY_LIMIT = 20
        private const val MERCHANT_COOLDOWN_HOURS = 24
        private const val ALLOWED_HOUR_START = 10 // 10:00 AM
        private const val ALLOWED_HOUR_END = 20   // 8:00 PM

        // History pruning
        private const val HISTORY_MAX_AGE_DAYS = 30
    }

    private val notificationManager = NotificationManagerCompat.from(context)
    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var notificationHistory = mutableListOf<NotificationRecord>()

    init {
        createNotificationChannel()
        loadHistory()
    }

    /**
     * Notification history record
     */
    private data class NotificationRecord(
        val storeId: String,
        val merchantId: String,
        val timestamp: Long
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("storeId", storeId)
                put("merchantId", merchantId)
                put("timestamp", timestamp)
            }
        }

        companion object {
            fun fromJson(json: JSONObject): NotificationRecord {
                return NotificationRecord(
                    storeId = json.optString("storeId", ""),
                    merchantId = json.optString("merchantId", ""),
                    timestamp = json.optLong("timestamp", 0)
                )
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for nearby store offers"
            }

            val systemNotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            systemNotificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Check if a notification can be shown based on rate limiting rules
     *
     * @param store The store to show notification for
     * @return true if notification can be shown
     */
    fun canShowNotification(store: RTLStore): Boolean {
        println("[$TAG] 🔍 Checking rate limits for store: ${store.name}")

        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()

        // Check time window (10:00 - 20:00)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        println("[$TAG]    Current hour: $hour (allowed: $ALLOWED_HOUR_START-$ALLOWED_HOUR_END)")
        if (hour < ALLOWED_HOUR_START || hour >= ALLOWED_HOUR_END) {
            println("[$TAG] ⛔ BLOCKED: Outside allowed hours ($hour not in $ALLOWED_HOUR_START-$ALLOWED_HOUR_END)")
            return false
        }

        // Check daily limit
        val dayStart = calendar.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val todayNotifications = notificationHistory.count { it.timestamp >= dayStart }
        println("[$TAG]    Today's notifications: $todayNotifications (limit: $DAILY_LIMIT)")
        if (todayNotifications >= DAILY_LIMIT) {
            println("[$TAG] ⛔ BLOCKED: Daily limit reached ($todayNotifications >= $DAILY_LIMIT)")
            return false
        }

        // Check weekly limit
        val weekStart = now - (7 * 24 * 60 * 60 * 1000L)
        val weekNotifications = notificationHistory.count { it.timestamp >= weekStart }
        println("[$TAG]    Week's notifications: $weekNotifications (limit: $WEEKLY_LIMIT)")
        if (weekNotifications >= WEEKLY_LIMIT) {
            println("[$TAG] ⛔ BLOCKED: Weekly limit reached ($weekNotifications >= $WEEKLY_LIMIT)")
            return false
        }

        // Check monthly limit
        val monthStart = now - (30 * 24 * 60 * 60 * 1000L)
        val monthNotifications = notificationHistory.count { it.timestamp >= monthStart }
        println("[$TAG]    Month's notifications: $monthNotifications (limit: $MONTHLY_LIMIT)")
        if (monthNotifications >= MONTHLY_LIMIT) {
            println("[$TAG] ⛔ BLOCKED: Monthly limit reached ($monthNotifications >= $MONTHLY_LIMIT)")
            return false
        }

        // Check merchant cooldown
        val merchantNotifications = notificationHistory.filter { it.merchantId == store.merchantId }
        if (merchantNotifications.isNotEmpty()) {
            val lastMerchant = merchantNotifications.maxByOrNull { it.timestamp }!!
            val hoursSince = (now - lastMerchant.timestamp) / (60 * 60 * 1000L)
            println("[$TAG]    Hours since last notification for this merchant: $hoursSince (cooldown: $MERCHANT_COOLDOWN_HOURS)")
            if (hoursSince < MERCHANT_COOLDOWN_HOURS) {
                println("[$TAG] ⛔ BLOCKED: Merchant cooldown active ($hoursSince hrs < $MERCHANT_COOLDOWN_HOURS hrs)")
                return false
            }
        } else {
            println("[$TAG]    No previous notifications for this merchant")
        }

        println("[$TAG] ✅ Rate limit check PASSED - notification allowed")
        return true
    }

    /**
     * Show a notification for a store
     *
     * @param store The store to show notification for
     */
    fun showNotification(store: RTLStore) {
        println("[$TAG] 🔔 showNotification called for store: ${store.name}")

        if (!canShowNotification(store)) {
            println("[$TAG] ⛔ Notification BLOCKED by rate limiting")
            return
        }

        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                println("[$TAG] ⛔ Notification permission NOT granted (Android 13+)")
                return
            }
            println("[$TAG] ✅ Notification permission granted")
        }

        val title = store.offerTitle ?: "Special Offer Nearby!"
        val body = store.offerDescription ?: "Check out ${store.name} for exclusive deals"

        println("[$TAG] 📝 Notification content:")
        println("[$TAG]    Title: $title")
        println("[$TAG]    Body: $body")

        // Create intent to open the app
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = launchIntent?.let {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            PendingIntent.getActivity(context, 0, it, flags)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply {
                pendingIntent?.let { setContentIntent(it) }
            }
            .build()

        val notificationId = store.id.hashCode()
        println("[$TAG] 🚀 SENDING NOTIFICATION (id=$notificationId)")
        notificationManager.notify(notificationId, notification)

        // Record notification
        val record = NotificationRecord(
            storeId = store.id,
            merchantId = store.merchantId,
            timestamp = System.currentTimeMillis()
        )
        notificationHistory.add(record)
        saveHistory()

        println("[$TAG] ✅ NOTIFICATION SENT for store: ${store.name}")
    }

    private fun loadHistory() {
        try {
            val jsonString = sharedPrefs.getString(HISTORY_KEY, null) ?: return
            val jsonArray = JSONArray(jsonString)

            val cutoff = System.currentTimeMillis() - (HISTORY_MAX_AGE_DAYS * 24 * 60 * 60 * 1000L)
            notificationHistory.clear()

            for (i in 0 until jsonArray.length()) {
                val record = NotificationRecord.fromJson(jsonArray.getJSONObject(i))
                // Only keep records within max age
                if (record.timestamp >= cutoff) {
                    notificationHistory.add(record)
                }
            }

            println("[$TAG] Loaded ${notificationHistory.size} notification records")
        } catch (e: Exception) {
            println("[$TAG] Error loading history: ${e.message}")
            notificationHistory.clear()
        }
    }

    private fun saveHistory() {
        try {
            val jsonArray = JSONArray()
            notificationHistory.forEach { record ->
                jsonArray.put(record.toJson())
            }
            sharedPrefs.edit().putString(HISTORY_KEY, jsonArray.toString()).apply()
        } catch (e: Exception) {
            println("[$TAG] Error saving history: ${e.message}")
        }
    }
}
