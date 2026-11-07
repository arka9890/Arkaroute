package com.omniroute.app.data

import java.net.HttpURLConnection
import java.net.URL

/** Source of gateway health bodies. Returns null when the gateway is unreachable. */
interface HealthSource {
    fun fetch(baseUrl: String): String?
}

/** Fetches `GET {baseUrl}/health` with 1500ms connect/read timeouts; null on any failure. */
class HttpHealthSource : HealthSource {
    override fun fetch(baseUrl: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("$baseUrl/health").openConnection() as HttpURLConnection
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.requestMethod = "GET"
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }
}
