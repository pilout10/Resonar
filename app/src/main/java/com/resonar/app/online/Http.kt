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
    suspend fun getJson(url: String, userAgent: String = USER_AGENT): JSONObject =
        withContext(Dispatchers.IO) {
            JSONObject(getString(url, userAgent))
        }

    /**
     * GET que devuelve un ARREGLAJO JSON (algunas APIs, como ccMixter, responden
     * con [...] en la raíz y no con {...}).
     */
    suspend fun getJsonArray(url: String): org.json.JSONArray = withContext(Dispatchers.IO) {
        org.json.JSONArray(getString(url))
    }

    /** GET como texto (por si una fuente devuelve algo que no es JSON puro). */
    suspend fun getString(url: String, userAgent: String = USER_AGENT): String =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept", "application/json, */*")
                conn.setRequestProperty("User-Agent", userAgent)
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${conn.responseCode} en $url")
                }
                readCapped(conn.inputStream)
            } finally {
                conn.disconnect()
            }
        }

    /**
     * TOPE DE TAMAÑO DE RESPUESTA (auditoría): ninguna API de las 6 fuentes
     * devuelve más que esto en sus respuestas de metadatos. Sin tope, un
     * servidor roto o malicioso podría mandar cientos de MB de "JSON" y la
     * app intentaría cargarlo entero en RAM: en un teléfono de 2 GB es un
     * cierre forzoso.
     */
    private const val MAX_RESPONSE_BYTES = 2_000_000

    /** Lee el stream con tope; si lo pasa, falla fuerte en vez de llenar la RAM. */
    private fun readCapped(input: java.io.InputStream): String {
        val acumulado = java.io.ByteArrayOutputStream()
        val trozo = ByteArray(8 * 1024)
        input.use { stream ->
            while (true) {
                val n = stream.read(trozo)
                if (n < 0) break
                if (acumulado.size() + n > MAX_RESPONSE_BYTES) {
                    throw IllegalStateException("Respuesta demasiado grande (> 2 MB)")
                }
                acumulado.write(trozo, 0, n)
            }
        }
        return acumulado.toString("UTF-8")
    }

    /**
     * GET con un User-Agent propio. Hay servicios (MusicBrainz, por ejemplo) que
     * responden 503 si el cliente dice ser un navegador: piden identificarse.
     */
    suspend fun getStringWithAgent(url: String, userAgent: String): String = getString(url, userAgent)
}
