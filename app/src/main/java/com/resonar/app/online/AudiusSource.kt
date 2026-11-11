package com.resonar.app.online

import android.net.Uri
import android.util.Log
import java.net.URLEncoder

/**
 * FUENTE AUDIUS — API OFICIAL (https://docs.audius.org/api), sin API key:
 * solo se identifica la app con ?app_name=Resonar en cada pedido, tal como
 * pide la documentación. No hay cuentas ni tokens.
 *
 * Cómo funciona:
 *  - https://api.audius.co devuelve {"data":["https://host1", ...]}: una lista
 *    de servidores (hosts) que hablan el mismo API. Elegimos uno y lo guardamos
 *    en memoria; si algún día el host elegido cae, se vuelve a descubrir.
 *  - Búsqueda: GET {host}/v1/tracks/search?query=...&app_name=Resonar
 *  - Stream:   GET {host}/v1/tracks/{id}/stream?app_name=Resonar → redirige (302)
 *    al CDN con un MP3. ExoPlayer sigue redirecciones solo, acá no hacemos nada.
 *
 * QUÉ SE ROMPE SI EL SERVICIO CAMBIA: si Audius cambia el formato del JSON de
 * search (campos "data", "title", "user.name", "duration") la búsqueda deja de
 * dar resultados; si cambia el endpoint /stream, los temas ya no suenan.
 * Ambos casos quedan LOGUEADOS acá abajo y la búsqueda sigue con las demás fuentes.
 */
class AudiusSource : MusicSource {

    override val id = "audius"
    override val displayName = "Audius"
    override val requiresKey = false

    private var cachedHost: String? = null

    /** Descubre (una vez) un host vivo de la red Audius. */
    private suspend fun host(): String {
        cachedHost?.let { return it }
        val json = Http.getJson("https://api.audius.co")
        val hosts = json.optJSONArray("data") ?: throw IllegalStateException("Audius sin hosts")
        if (hosts.length() == 0) throw IllegalStateException("Audius devolvió 0 hosts")
        val host = hosts.optString(0).trimEnd('/')
        if (host.isBlank()) throw IllegalStateException("Audius host vacío")
        Log.i("ResonarOnline", "Audius host: $host")
        cachedHost = host
        return host
    }

    override suspend fun search(query: String, page: Int): List<OnlineTrack> {
        // La API de búsqueda de Audius no tiene paginación documentada:
        // una página es una página (la página > 0 devuelve vacío, honesto).
        if (page > 0) return emptyList()
        val url = "${host()}/v1/tracks/search" +
            "?query=${URLEncoder.encode(query, "UTF-8")}" +
            "&app_name=Resonar"
        val json = Http.getJson(url)
        val array = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<OnlineTrack>(array.length())
        for (i in 0 until array.length()) {
            val t = array.optJSONObject(i) ?: continue
            val title = t.optString("title").trim()
            if (title.isBlank()) continue
            val user = t.optJSONObject("user")
            val artist = user?.optString("name").orEmpty()
                .ifBlank { "Audius" }
            // Carátula: el campo "artwork" trae URLs por medida. Si algún tema no
            // lo trae, se arma desde el CID de "cover_art_sizes" (probado: 200 OK).
            val art = t.optJSONObject("artwork")?.optString("150x150")
                ?: t.optString("cover_art_sizes").takeIf { it.isNotBlank() }
                    ?.let { "https://discoveryprovider.audius.co/content/$it/150x150.jpg" }
            out.add(
                OnlineTrack(
                    sourceId = id,
                    externalId = t.optString("id"),
                    title = title,
                    artist = artist,
                    durationMs = if (t.has("duration")) t.optLong("duration") * 1_000L else null,
                    artUrl = art?.takeIf { it.startsWith("http") },
                ),
            )
        }
        Log.i("ResonarOnline", "Audius search '${query.trim()}' → ${out.size} resultados")
        return out
    }

    override suspend fun resolveStream(track: OnlineTrack): StreamHandle {
        // La URI del endpoint de stream es estable: ExoPlayer la abre, Audius
        // responde 302 al CDN y el audio fluye. Nada firmado que venza acá.
        val url = "${host()}/v1/tracks/${track.externalId}/stream?app_name=Resonar"
        Log.i("ResonarOnline", "Audius stream: $url")
        return StreamHandle(Uri.parse(url), "audio/mpeg")
    }

    /** Pegado de URL: audius.co/usuario/slug → /v1/resolve devuelve el tema. */
    suspend fun resolveUrl(url: String): OnlineTrack? {
        val resolved = Http.getJson(
            "${host()}/v1/resolve?url=${URLEncoder.encode(url, "UTF-8")}&app_name=Resonar",
        )
        val t = resolved.optJSONObject("data") ?: return null
        val title = t.optString("title").trim()
        if (title.isBlank()) return null
        val user = t.optJSONObject("user")
        val art = t.optJSONObject("artwork")?.optString("150x150")
        return OnlineTrack(
            sourceId = id,
            externalId = t.optString("id"),
            title = title,
            artist = user?.optString("name").orEmpty().ifBlank { "Audius" },
            durationMs = if (t.has("duration")) t.optLong("duration") * 1_000L else null,
            artUrl = art?.takeIf { it.startsWith("http") },
        )
    }

    /** ¿Esta URL es de Audius? Para el pegado de enlaces. */
    fun canResolveUrl(url: String): Boolean = url.contains("audius.co", ignoreCase = true)
}
