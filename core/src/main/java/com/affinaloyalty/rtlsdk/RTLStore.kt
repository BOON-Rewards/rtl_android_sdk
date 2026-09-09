package com.affinaloyalty.rtlsdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data model for a store with location and offer information
 */
data class RTLStore(
    val id: String,
    val name: String,
    val merchantId: String,
    val latitude: Double,
    val longitude: Double,
    val offerTitle: String?,
    val offerDescription: String?
) {
    companion object {
        /**
         * Parse a store from JSON
         */
        fun fromJson(json: JSONObject): RTLStore {
            return RTLStore(
                id = json.optString("id", ""),
                name = json.optString("name", ""),
                merchantId = json.optString("merchantId", ""),
                latitude = json.optDouble("latitude", 0.0),
                longitude = json.optDouble("longitude", 0.0),
                offerTitle = json.optString("offerTitle", null),
                offerDescription = json.optString("offerDescription", null)
            )
        }

        /**
         * Parse a list of stores from JSON array
         */
        fun fromJsonArray(jsonArray: JSONArray): List<RTLStore> {
            val stores = mutableListOf<RTLStore>()
            for (i in 0 until jsonArray.length()) {
                try {
                    stores.add(fromJson(jsonArray.getJSONObject(i)))
                } catch (e: Exception) {
                    // Skip invalid entries
                }
            }
            return stores
        }
    }
}
