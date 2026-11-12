package com.resonar.app.online

import android.net.Uri
import android.util.Log
import com.resonar.app.BuildConfig
import java.net.URLEncoder

/**
 * FUENTE JAMENDO — API OFICIAL (https://developer.jamendo.com/v3.0), CON API key.
 *
 * La key es gratuita pero hay que pedirla: https://devportal.jamendo.com/signup
 * Se carga en el archivo local "local.properties" (NO se versiona) con esta línea:
 *
 *     jamendo.client_id=TU_CLIENT_ID
 *
 * Si la key no está, esta fuente se SALTA (no rompe nada): así el resto de la
 * app anda igual en una instalación sin key.
 *
 * Qué anda:
 *  - Búsqueda: GET https://api.jamendo.com/v3.0/tracks/?client_id=..&format=json
 *      &limit=25&offset=N&namesearch=<q>&include=musicinfo&audioformat=mp32
 *    Devuelve "results[]" con id, name, artist_name, duration (segundos),
 *    audio (URL del MP3), album_image y musicinfo (licencia, año, sello).
 *  - Stream: el campo "audio" (MP3 de 96 kbps, el formato gratuito de Jamendo).
 *
 * QUÉ SE ROMPE SI EL SERVICIO CAMBIA: si Jamendo cambia los parámetros o el
 * formato del JSON, esta fuente deja de dar resultados o de sonar.
 */
class JamendoSource : MusicSource {

    override val id = "jamendo"
    override val displayName = "Jamendo"
    override val requiresKey = true

    /** La key viene del build (leída de local.properties). Vacía = fuente apagada. */
    private val clientId: String = BuildConfig.JAMENDO_CLIENT_ID.trim()

    /** ¿Hay key puesta? Si no, el registro no incluye esta fuente. */
    fun isConfigured(): Boolean = clientId.isNotBlank()

    override suspend fun search(query: String, page: Int): List<OnlineTrack> {
        if (clientId.isBlank() || query.isBlank()) return emptyList()
        val url = "https://api.jamendo.com/v3.0/tracks/" +
            "?client_id=$clientId&format=json&limit=25&offset=${page * 25}" +
            "&namesearch=${URLEncoder.encode(query.trim(), "UTF-8")}" +
            "&include=musicinfo&audioformat=mp32"
        val json = Http.getJson(url)
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<OnlineTrack>(results.length())
        for (i in 0 until results.length()) {
            val t = results.optJSONObject(i) ?: continue
            val title = t.optString("name").trim()
            val audio = t.optString("audio").trim()
            if (title.isBlank() || !audio.startsWith("http")) continue
            val info = t.optJSONObject("musicinfo")
            out.add(
                OnlineTrack(
                    sourceId = id,
                    externalId = t.optString("id"),
                    title = title,
                    artist = t.optString("artist_name").trim().ifBlank { "Jamendo" },
                    durationMs = if (t.has("duration")) t.optLong("duration") * 1_000L else null,
                    artUrl = t.optString("album_image").takeIf { it.startsWith("http") },
                    albumTitle = t.optString("album_name").takeIf { it.isNotBlank() },
                    license = licenseLabel(info?.optString("license_ccurl")),
                    isrc = info?.optString("isrc")?.takeIf { it.isNotBlank() },
                ),
            )
        }
        Log.i("ResonarOnline", "Jamendo search '${query.trim()}' → ${out.size} resultados")
        return out
    }

    override suspend fun resolveStream(track: OnlineTrack): StreamHandle {
        if (clientId.isBlank()) throw IllegalStateException("Jamendo sin API key")
        val json = Http.getJson(
            "https://api.jamendo.com/v3.0/tracks/?client_id=$clientId&format=json" +
                "&id=${track.externalId}&audioformat=mp32",
        )
        val results = json.optJSONArray("results") ?: throw IllegalStateException("Jamendo: sin resultados")
        val audio = results.optJSONObject(0)?.optString("audio").orEmpty()
        if (!audio.startsWith("http")) throw IllegalStateException("Jamendo: tema sin audio")
        Log.i("ResonarOnline", "Jamendo stream: ${track.title}")
        return StreamHandle(Uri.parse(audio), "audio/mpeg")
    }

    private fun licenseLabel(ccUrl: String?): String? {
        val url = ccUrl.orEmpty()
        return when {
            url.isBlank() -> null
            url.contains("by-nc") -> "CC BY-NC"
            url.contains("by-sa") -> "CC BY-SA"
            url.contains("creativecommons.org") -> "Creative Commons"
            else -> null
        }
    }
}
