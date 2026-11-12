package com.resonar.app.online

import android.net.Uri
import android.util.Log
import java.net.URLEncoder

/**
 * FUENTE OPENVERSE — API OFICIAL PÚBLICA (https://api.openverse.org/v1/audio/),
 * sin API key. Es un AGREGADOR: junta audio libre de Jamendo, Freesound,
 * Wikimedia Commons y otros, y da la URL directa del archivo.
 *
 * Qué anda:
 *  - Búsqueda: GET https://api.openverse.org/v1/audio/?q=<q>&page_size=20&page=N
 *    Devuelve "results[]" con title, creator, url (¡el audio directo!), duration
 *    (ms), license ("by", "by-sa", "cc0", "pdm"…), thumbnail y filesize.
 *  - Stream: el campo "url". Es un archivo de audio real (mp3/ogg/flac), muchas
 *    veces con redirección (el CDN de Jamendo) — ExoPlayer sigue redirecciones.
 *
 * Todos los temas son de licencia libre (Creative Commons o dominio público):
 * por eso el badge de licencia siempre aparece en esta fuente.
 *
 * QUÉ SE ROMPE SI EL SERVICIO CAMBIA: si Openverse cambia los nombres de campo
 * o exige un token para el uso anónimo, esta fuente deja de dar resultados.
 * Queda logueado y las demás fuentes siguen andando.
 */
class OpenverseSource : MusicSource {

    override val id = "openverse"
    override val displayName = "Openverse"
    override val requiresKey = false

    override suspend fun search(query: String, page: Int): List<OnlineTrack> {
        if (query.isBlank()) return emptyList()
        val url = "https://api.openverse.org/v1/audio/" +
            "?q=${URLEncoder.encode(query.trim(), "UTF-8")}" +
            "&page_size=20&page=${page + 1}"
        val json = Http.getJson(url)
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<OnlineTrack>(results.length())
        for (i in 0 until results.length()) {
            val t = results.optJSONObject(i) ?: continue
            val title = t.optString("title").trim()
            val audio = t.optString("url").trim()
            if (title.isBlank() || !audio.startsWith("http")) continue
            out.add(
                OnlineTrack(
                    sourceId = id,
                    // El id externo ES la URL del audio: es lo que hay que abrir
                    // para reproducir, y es estable (el archivo no se mueve).
                    externalId = audio,
                    title = title,
                    artist = t.optString("creator").trim().ifBlank { "Openverse" },
                    durationMs = if (t.has("duration")) t.optLong("duration").takeIf { it > 0 } else null,
                    artUrl = t.optString("thumbnail").takeIf { it.startsWith("http") },
                    license = licenseLabel(t.optString("license")),
                    // La fuente original (Jamendo, Freesound…) se puede ver en la UI.
                    providerLabel = t.optString("provider").takeIf { it.isNotBlank() },
                ),
            )
        }
        Log.i("ResonarOnline", "Openverse search '${query.trim()}' → ${out.size} resultados")
        return out
    }

    override suspend fun resolveStream(track: OnlineTrack): StreamHandle {
        val url = track.externalId
        if (!url.startsWith("http")) throw IllegalStateException("Openverse: id sin URL")
        Log.i("ResonarOnline", "Openverse stream: $url")
        return StreamHandle(Uri.parse(url), null)
    }

    /**
     * Pegado de URL: openverse.org/audio/{id} → la API oficial del mismo id.
     */
    suspend fun resolveUrl(url: String): OnlineTrack? {
        val id = Uri.parse(url).pathSegments?.lastOrNull { it.isNotBlank() && it != "audio" }
            ?: return null
        val json = Http.getJson("https://api.openverse.org/v1/audio/$id/")
        val title = json.optString("title").trim()
        val audio = json.optString("url").trim()
        if (title.isBlank() || !audio.startsWith("http")) return null
        return OnlineTrack(
            sourceId = id,
            externalId = audio,
            title = title,
            artist = json.optString("creator").trim().ifBlank { "Openverse" },
            durationMs = if (json.has("duration")) json.optLong("duration").takeIf { it > 0 } else null,
            artUrl = json.optString("thumbnail").takeIf { it.startsWith("http") },
            license = licenseLabel(json.optString("license")),
            providerLabel = json.optString("provider").takeIf { it.isNotBlank() },
        )
    }

    /** ¿Esta URL es de Openverse? */
    fun canResolveUrl(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase().orEmpty()
        return host.contains("openverse.org") && Uri.parse(url).pathSegments?.contains("audio") == true
    }

    /** "by" → "CC BY", "cc0" → "CC0"… (lo que la fuente declara, sin inventar). */
    private fun licenseLabel(code: String): String? = when (code.lowercase()) {
        "by" -> "CC BY"
        "by-sa" -> "CC BY-SA"
        "by-nd" -> "CC BY-ND"
        "by-nc" -> "CC BY-NC"
        "cc0" -> "CC0 (dominio público)"
        "pdm" -> "PDM"
        "" -> null
        else -> code.uppercase()
    }
}
