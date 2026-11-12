package com.resonar.app.online

import android.net.Uri
import android.util.Log
import java.net.URLEncoder

/**
 * FUENTE DEEZER — API OFICIAL PÚBLICA (https://api.deezer.com), sin API key.
 *
 * Qué anda:
 *  - Búsqueda: GET https://api.deezer.com/search?q=...&limit=25&index=N
 *    Devuelve "data[]" con id, title, artist.name, duration (segundos),
 *    album.cover_xl (carátula) y album.title.
 *  - Stream: GET https://api.deezer.com/track/{id} → "preview" (MP3 de 30 s).
 *
 * LÍMITE HONESTO (importante): Deezer NO da la canción completa por su API
 * pública; el campo "mp3" (que antes traía el stream entero) hoy viene vacío y
 * la "preview" dura 30 segundos. Por eso todos los resultados de esta fuente
 * se marcan como PREVIEW: la app no promete la canción entera.
 *
 * QUÉ SE ROMPE SI EL SERVICIO CAMBIA: si Deezer cambia el JSON de /search o
 * quita el campo "preview", esta fuente deja de dar resultados o deja de sonar.
 * Queda logueado y las demás fuentes siguen andando.
 */
class DeezerSource : MusicSource {

    override val id = "deezer"
    override val displayName = "Deezer"
    override val requiresKey = false

    override suspend fun search(query: String, page: Int): List<OnlineTrack> {
        if (query.isBlank()) return emptyList()
        val url = "https://api.deezer.com/search" +
            "?q=${URLEncoder.encode(query.trim(), "UTF-8")}" +
            "&limit=25&index=${page * 25}"
        val json = Http.getJson(url)
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<OnlineTrack>(data.length())
        for (i in 0 until data.length()) {
            val t = data.optJSONObject(i) ?: continue
            val title = t.optString("title").trim()
            if (title.isBlank()) continue
            val artist = t.optJSONObject("artist")?.optString("name").orEmpty().trim()
            val album = t.optJSONObject("album")
            out.add(
                OnlineTrack(
                    sourceId = id,
                    externalId = t.optString("id"),
                    title = title,
                    artist = artist.ifBlank { "Deezer" },
                    durationMs = if (t.has("duration")) t.optLong("duration") * 1_000L else null,
                    artUrl = album?.optString("cover_xl")?.takeIf { it.startsWith("http") },
                    albumTitle = album?.optString("title")?.takeIf { it.isNotBlank() },
                    isPreview = true, // Deezer solo da 30 s por API pública
                ),
            )
        }
        Log.i("ResonarOnline", "Deezer search '${query.trim()}' → ${out.size} resultados")
        return out
    }

    override suspend fun resolveStream(track: OnlineTrack): StreamHandle {
        val json = Http.getJson("https://api.deezer.com/track/${track.externalId}")
        val preview = json.optString("preview").trim()
        if (preview.isBlank() || !preview.startsWith("http")) {
            throw IllegalStateException("Deezer no tiene preview para este tema")
        }
        Log.i("ResonarOnline", "Deezer preview: ${track.title}")
        return StreamHandle(Uri.parse(preview), "audio/mpeg")
    }

    /** Datos extra del tema: ISRC, sello, fecha y disco. */
    override suspend fun getMetadata(track: OnlineTrack): TrackMetadata? {
        val json = runCatching { Http.getJson("https://api.deezer.com/track/${track.externalId}") }
            .getOrNull() ?: return null
        val album = json.optJSONObject("album")
        return TrackMetadata(
            isrc = json.optString("isrc").takeIf { it.isNotBlank() },
            year = json.optString("release_date").take(4).toIntOrNull(),
            label = json.optJSONObject("label")?.optString("name")?.takeIf { it.isNotBlank() },
            albumTitle = album?.optString("title")?.takeIf { it.isNotBlank() },
            artUrl = album?.optString("cover_xl")?.takeIf { it.startsWith("http") },
        )
    }
}
