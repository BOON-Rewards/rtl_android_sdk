package com.affina.rtlsdk

/**
 * Push notification token type
 */
enum class RTLTokenType(val value: String) {
    /**
     * Apple Push Notification Service token
     */
    APNS("apns"),

    /**
     * Firebase Cloud Messaging token
     */
    FCM("fcm")
}
