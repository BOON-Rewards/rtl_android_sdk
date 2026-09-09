package com.affinaloyalty.rtlsdk.location

import android.net.Uri
import android.webkit.CookieManager
import com.affinaloyalty.rtlsdk.RTLStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import com.affinaloyalty.rtlsdk.RTLLog
import com.affinaloyalty.rtlsdk.RTLLogArea.STORE

/**
 * Fetches locations with hyperlocal offers from the RTL API.
 */
internal class RTLHyperlocalOffersService(
    baseUrl: String,
    private val externalChapterId: String?
) {
    private val baseUrl = Uri.parse(baseUrl)

    /**
     * Fetch locations with offers near the supplied coordinates.
     *
     * @param latitude The latitude coordinate
     * @param longitude The longitude coordinate
     * @return List of locations carrying hyperlocal offers
     */
    suspend fun fetchHyperlocalOffers(latitude: Double, longitude: Double): List<RTLStore> {
        return withContext(Dispatchers.IO) {
            try {
                val urlString = baseUrl.buildUpon()
                    .path("/api/rest/hyperlocal-offers")
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
                RTLLog.d(STORE) { "Fetching hyperlocal offers from ${RTLLog.url(urlString)}" }

                val cookieHeader = CookieManager.getInstance().getCookie(urlString)
                if (cookieHeader.isNullOrBlank()) {
                    RTLLog.w(STORE) { "Cannot fetch hyperlocal offers without a signed-in session" }
                    return@withContext emptyList()
                }

                val connection = url.openConnection() as HttpURLConnection

                try {
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Cookie", cookieHeader)
                    connection.setRequestProperty("Accept", "application/json")
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000

                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonArray = JSONArray(response)
                        val offers = RTLStore.fromJsonArray(jsonArray)
                        RTLLog.i(STORE) { "Fetched ${offers.size} hyperlocal offers" }
                        offers
                    } else {
                        RTLLog.e(STORE) { "Failed to fetch hyperlocal offers: HTTP $responseCode" }
                        emptyList()
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                RTLLog.e(STORE, e) { "Error fetching hyperlocal offers" }
                emptyList()
            }
        }
    }
}
