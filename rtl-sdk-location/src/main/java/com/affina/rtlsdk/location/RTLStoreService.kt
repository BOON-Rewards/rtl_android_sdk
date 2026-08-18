package com.affina.rtlsdk.location

import android.net.Uri
import com.affina.rtlsdk.RTLStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service for fetching nearby stores from the RTL API
 */
internal class RTLStoreService(
    baseUrl: String,
    private val externalChapterId: String?
) {
    private val baseUrl = Uri.parse(baseUrl)

    companion object {
        private const val API_KEY = "2F7ZqPuvDr0LBtjqJQpNJKWA8FqkKAbJ"
        private const val TAG = "RTLStoreService"
    }

    /**
     * Fetch nearby stores based on location
     *
     * @param latitude The latitude coordinate
     * @param longitude The longitude coordinate
     * @return List of nearby stores
     */
    suspend fun fetchNearbyStores(latitude: Double, longitude: Double): List<RTLStore> {
        return withContext(Dispatchers.IO) {
            try {
                val urlString = baseUrl.buildUpon()
                    .path("/api/rest/cp/stores/nearby")
                    .clearQuery()
                    .fragment(null)
                    .appendQueryParameter("lat", latitude.toString())
                    .appendQueryParameter("long", longitude.toString())
                    .apply {
                        externalChapterId?.let {
                            appendQueryParameter("externalChapterId", it)
                        }
                    }
                    .build()
                    .toString()

                val url = URL(urlString)
                println("[$TAG] Fetching nearby stores from: $urlString")

                val connection = url.openConnection() as HttpURLConnection

                try {
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("x-affina-secret-key", API_KEY)
                    connection.setRequestProperty("Accept", "application/json")
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000

                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonArray = JSONArray(response)
                        val stores = RTLStore.fromJsonArray(jsonArray)
                        println("[$TAG] Fetched ${stores.size} nearby stores")
                        stores
                    } else {
                        println("[$TAG] Failed to fetch stores: HTTP $responseCode")
                        emptyList()
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                println("[$TAG] Error fetching stores: ${e.message}")
                emptyList()
            }
        }
    }
}
