package com.resonar.app.online

import android.net.Uri
import android.util.Log
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * FUENTE INTERNET ARCHIVE — API OFICIAL keyless de verdad
 * (https://archive.org/developers/mdsearch.html). Sin cuentas ni tokens.
 *
 * Cómo funciona:
 *  - Búsqueda: GET https://archive.org/advancedsearch.php
 *      ?q=(mediatype:(audio)) AND title:(consulta)
 *      &fl[]=identifier, title, creator, length, licenseurl, downloads
 *      &sort[]=downloads desc &rows=25 &page=N &output=json
 *    Sólo audios (mediatype:audio), ordenados por descargas: lo que la gente
 *    realmente escucha, no lo que se subió hace 15 años y nadie abrió.
 *  - Stream: se piden los archivos del ítem con
 *      GET https://archive.org/metadata/{identifier}
 *    y se elige el mejor AUDIO (VBR MP3 → cualquier MP3 → m4a/ogg/flac).
 *    La URL de descarga es estable: https://archive.org/download/{id}/{archivo}.
 *
 * Particularidad honesta: el campo "length" del ítem suele venir en el METADATA,
 * no en la búsqueda, y puede venir en segundos ("185"), en "m:ss" o no venir.
 * La duración del resultado de búsqueda puede ser null: el dedupe lo tolera.
 *
 * QUÉ SE ROMPE SI EL SERVICIO CAMBIA: si cambian los nombres de campo
 * (response.docs, identifier, files[].name) la búsqueda deja de dar resultados
 * o la resolución deja de sonar. Queda logueado y la app sigue con las demás fuentes.
 */
class InternetArchiveSource : MusicSource {

    override val id = "archive"
    override val displayName = "Internet Archive"
    override val requiresKey = false

    /** Resoluciones ya hechas (identifier → mejor archivo). Tope para no crecer. */
    private val resolvedFiles = ConcurrentHashMap<String, String>()

    override suspend fun search(query: String, page: Int): List<OnlineTrack> {
        // Se sacan comillas y paréntesis de la consulta: el q es una query de
        // solr real y caracteres raros rompen el parser.
        val safe = query.replace(Regex("""[()"\[\]:]"""), " ").trim()
        if (safe.isBlank()) return emptyList()
        val q = "(mediatype:(audio)) AND (title:($safe))"
        val url = "https://archive.org/advancedsearch.php" +
            "?q=${URLEncoder.encode(q, "UTF-8")}" +
            "&fl%5B%5D=identifier" +
            "&fl%5B%5D=title" +
            "&fl%5B%5D=creator" +
            "&fl%5B%5D=length" +
            "&fl%5B%5D=licenseurl" +
            "&fl%5B%5D=downloads" +
            "&sort%5B%5D=downloads+desc" +
            "&rows=25&page=$page&output=json"
        val json = Http.getJson(url)
        val response = json.optJSONObject("response") ?: return emptyList()
        val docs = response.optJSONArray("docs") ?: return emptyList()
        val out = ArrayList<OnlineTrack>(docs.length())
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            val identifier = d.optString("identifier").trim()
            val title = asFirstString(d.opt("title")).trim()
            if (identifier.isBlank() || title.isBlank()) continue
            out.add(
                OnlineTrack(
                    sourceId = id,
                    externalId = identifier,
                    title = title,
                    artist = asFirstString(d.opt("creator")).ifBlank { "Internet Archive" },
                    durationMs = parseLength(d.opt("length")),
                    artUrl = null, // IA no da carátulas en la búsqueda; el badge de fuente cubre la fila
                    license = licenseLabel(d.optString("licenseurl")),
                ),
            )
        }
        Log.i("ResonarOnline", "IA search '${query.trim()}' → ${out.size} resultados (numFound=${response.optInt("numFound")})")
        return out
    }

    override suspend fun resolveStream(track: OnlineTrack): StreamHandle {
        val identifier = track.externalId
        val fileName = resolvedFiles[identifier] ?: pickBestAudio(identifier).also {
            if (resolvedFiles.size > 200) resolvedFiles.clear()
            resolvedFiles[identifier] = it
        }
        // Los nombres de archivo de IA tienen espacios y acentos: SIEMPRE se
        // codifican, y con %20 (no "+"): en una ruta de URL el "+" es un
        // carácter literal y el servidor responde 404. Lección del dispositivo.
        val encId = URLEncoder.encode(identifier, "UTF-8").replace("+", "%20")
        val encFile = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val url = "https://archive.org/download/$encId/$encFile"
        Log.i("ResonarOnline", "IA stream: $url")
        return StreamHandle(Uri.parse(url), mimeTypeFor(fileName))
    }

    /**
     * Pega de URL: archive.org/details/{id} o archive.org/download/{id}/...
     * → baja el metadata y arma el tema con lo que haya.
     */
    suspend fun resolveUrl(url: String): OnlineTrack? {
        val path = Uri.parse(url).path ?: return null
        val identifier = path.substringAfterLast("/details/", "").substringBefore('/')
            .ifBlank { path.substringAfterLast("/download/", "").substringBefore('/') }
            .ifBlank { return null }
        val metadata = Http.getJson("https://archive.org/metadata/$identifier")
        val info = metadata.optJSONObject("metadata") ?: return null
        val title = asFirstString(info.opt("title")).trim().ifBlank { identifier }
        val artist = asFirstString(info.opt("creator")).ifBlank { "Internet Archive" }
        val lengthMs = parseLength(info.opt("length"))
        val license = licenseLabel(asFirstString(info.opt("licenseurl")))
        return OnlineTrack(id, identifier, title, artist, lengthMs, null, license)
    }

    fun canResolveUrl(url: String): Boolean = url.contains("archive.org", ignoreCase = true)

    /** Elige el mejor audio del ítem. VBR MP3 primero: es el formato universal. */
    private suspend fun pickBestAudio(identifier: String): String {
        val metadata = Http.getJson("https://archive.org/metadata/$identifier")
        val files = metadata.optJSONArray("files") ?: throw IllegalStateException("IA sin archivos")
        var fallback: String? = null
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name")
            val format = f.optString("format", "").lowercase()
            val lower = name.lowercase()
            if (lower.endsWith(".mp3")) {
                if (format.contains("vbr")) return name
                if (fallback == null) fallback = name
            } else if (lower.endsWith(".m4a") || lower.endsWith(".ogg") || lower.endsWith(".flac")) {
                if (fallback == null) fallback = name
            }
        }
        return fallback ?: throw IllegalStateException("IA: sin audio en $identifier")
    }

    // ── Ayudantes de formato de IA (los campos llegan como string O como lista) ──

    /** IA devuelve title/creator a veces como string y a veces como array. */
    private fun asFirstString(value: Any?): String = when (value) {
        is String -> value
        is org.json.JSONArray -> if (value.length() > 0) value.optString(0) else ""
        else -> ""
    }

    /** "185" = segundos. "3:25" = mm:ss. "1:03:45" = h:mm:ss. null/otro = null. */
    private fun parseLength(value: Any?): Long? {
        val raw = when (value) {
            is String -> value
            is Number -> value.toDouble().toLong().toString()
            else -> return null
        }.trim()
        val seconds = if (raw.contains(':')) {
            raw.split(':').fold(0L) { acc, part -> acc * 60 + (part.toLongOrNull() ?: return null) }
        } else {
            raw.split('.').first().toLongOrNull() ?: return null
        }
        return if (seconds > 0) seconds * 1_000L else null
    }

    private fun licenseLabel(licenseUrl: String): String? = when {
        licenseUrl.contains("publicdomain") -> "Dominio público"
        licenseUrl.contains("creativecommons") -> "Creative Commons"
        else -> null
    }

    private fun mimeTypeFor(fileName: String): String? = when (fileName.substringAfterLast('.').lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> null
    }
}
