package com.resonar.app.online

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * METADATOS DE MusicBrainz + Cover Art Archive.
 *
 * MusicBrainz es la base de datos abierta de música: no da audio, da DATOS
 * (ISRC, año, duración, y el id del lanzamiento). La Cover Art Archive guarda
 * las carátulas de esos lanzamientos.
 *
 * Para qué se usa acá: para completar la info de los resultados que no traen
 * carátula (o ISRC), y para poder decir "este resultado y este otro son
 * GRABACIONES DISTINTAS" con el ISRC en vez de eyeball.
 *
 * REGLAS DE LA API (importantes, si no la bloquean):
 *  - Piden un User-Agent identificable (si no, responden 403).
 *  - Piden un máximo de 1 pedido por segundo desde la misma IP.
 *
 * QUÉ SE ROMPE SI EL SERVICIO CAMBIA: si MusicBrainz cambia el JSON de /ws/2
 * o la Cover Art Archive cambia su ruta, la app deja de completar la info.
 * No es grave: la búsqueda y la reproducción NO dependen de esto.
 */
object MusicBrainz {

    private const val BASE = "https://musicbrainz.org/ws/2/recording/"
    private const val UA = "Resonar/1.0 (app de música personal; Android)"

    /** Lo que se sabe de una grabación concreta. */
    data class Recording(
        val isrc: String? = null,
        val coverUrl: String? = null,
        val year: Int? = null,
        val durationMs: Long? = null,
        val title: String? = null,
    )

    /**
     * Busca la grabación por artista + título. Si esa consulta no da nada,
     * reintenta solo por título (muchos resultados en línea traen el artista
     * escrito distinto y la consulta estricta no encuentra nada).
     * Devuelve null si no encuentra (o falla): eso es normal y no se muestra
     * ningún error.
     */
    suspend fun lookup(artist: String, title: String): Recording? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val strict = if (artist.isBlank()) null else search("artist:\"${artist.trim()}\" AND recording:\"${title.trim()}\"")
        if (strict != null) return@withContext strict
        // Segundo intento: solo el título (más laxo, más resultados).
        search("recording:\"${title.trim()}\"")
    }

    /** Una consulta a la API y la traducción a [Recording]. */
    private suspend fun search(query: String): Recording? {
        val url = BASE + "?query=" + URLEncoder.encode(query, "UTF-8") +
            "&fmt=json&limit=1"
        // OJO: MusicBrainz responde 503 si el cliente dice ser un navegador
        // genérico. Pide identificarse: por eso su propio User-Agent.
        val json = runCatching { Http.getJson(url, UA) }.getOrNull() ?: return null
        val recording = json.optJSONArray("recordings")?.optJSONObject(0) ?: return null

        val isrc = recording.optJSONArray("isrcs")?.optString(0)?.takeIf { it.isNotBlank() }
        val duration = if (recording.has("length")) recording.optLong("length").takeIf { it > 0 } else null
        val releases = recording.optJSONArray("releases")
        val release = releases?.optJSONObject(0)
        val year = release?.optString("date")?.take(4)?.toIntOrNull()
        val mbid = release?.optString("id")?.takeIf { it.isNotBlank() }
        // La Cover Art Archive redirige a la imagen: Coil sigue la redirección.
        val cover = mbid?.let { "https://coverartarchive.org/release/$it/front-500" }

        return Recording(
            isrc = isrc,
            coverUrl = cover,
            year = year,
            durationMs = duration,
            title = recording.optString("title").takeIf { it.isNotBlank() },
        )
    }
}
