package com.resonar.app.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP mínimo para las fuentes: HttpURLConnection del sistema, SIN OkHttp ni
 * librerías de red extra. Timeout corto de 8 s en connect y read: una fuente
 * lenta NO puede frenar la búsqueda de las demás (el plan lo exige).
 */
object Http {

    /**
     * User-Agent de navegador. Algunos CDN (sobre todo el de YouTube) responden 403
     * a identificadores de app tipo "Resonar/0.1": no es un bug de la app, es el
     * servicio rechazando el cliente. Un UA de navegador común evita ese rechazo.
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** GET que devuelve JSON parseado. Falla con excepción si el código no es 2xx. */
    suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(getString(url))
    }

    /** GET como texto (por si una fuente devuelve algo que no es JSON puro). */
    suspend fun getString(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json, */*")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode} en $url")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
