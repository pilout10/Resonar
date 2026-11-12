package com.resonar.app.online

import android.net.Uri
import android.util.Log
import java.net.URLEncoder

/**
 * FUENTE CCMIXTER — API OFICIAL keyless (http://ccmixter.org/api/query).
 * Música con licencia Creative Commons, en general completa (MP3 y FLAC).
 *
 * Qué anda:
 *  - Búsqueda: GET http://ccmixter.org/api/query?f=json&limit=25&search=<q>&offset=N
 *    (también acepta "tags=" y "artist="). Devuelve un arreglo plano con
 *    upload_id, upload_name, user_name/user_real_name, license_name, license_url
 *    y "files[]" (cada archivo con download_url, file_format_info y tamaño).
 *  - Stream: el download_url del mejor archivo (MP3 primero, después FLAC).
 *    Son URLs estables: no vencen, no hay que re-resolver.
 *
 * QUÉ SE ROMPE SI EL SERVICIO CAMBIA: si ccMixter cambia el formato del JSON o
 * la ruta /content/, esta fuente deja de dar resultados o de sonar. Queda
 * logueado y las demás fuentes siguen.
 */
class CcMixterSource : MusicSource {

    override val id = "ccmixter"
    override val displayName = "ccMixter"
    override val requiresKey = false

    override suspend fun search(query: String, page: Int): List<OnlineTrack> {
        if (query.isBlank()) return emptyList()
        // OJO: https y no http. Android 9+ bloquea el http plano, y ccMixter
        // también responde por https.
        val url = "https://ccmixter.org/api/query" +
            "?f=json&limit=25&offset=${page * 25}" +
            "&search=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val array = Http.getJsonArray(url)
        val out = ArrayList<OnlineTrack>(array.length())
        for (i in 0 until array.length()) {
            val t = array.optJSONObject(i) ?: continue
            val title = t.optString("upload_name").trim()
            val uploadId = t.optString("upload_id").trim()
            if (title.isBlank() || uploadId.isBlank()) continue
            val artist = t.optString("user_real_name").trim()
                .ifBlank { t.optString("user_name").trim() }
                .ifBlank { "ccMixter" }
            val files = t.optJSONArray("files")
            val best = pickBestFile(files)
            out.add(
                OnlineTrack(
                    sourceId = id,
                    externalId = uploadId,
                    title = title,
                    artist = artist,
                    durationMs = best?.let { parseDuration(it) },
                    artUrl = null, // ccMixter no da carátula por tema
                    license = t.optString("license_name").trim().takeIf { it.isNotBlank() }
                        ?: "Creative Commons",
                ),
            )
        }
        Log.i("ResonarOnline", "ccMixter search '${query.trim()}' → ${out.size} resultados")
        return out
    }

    override suspend fun resolveStream(track: OnlineTrack): StreamHandle {
        val array = Http.getJsonArray(
            "https://ccmixter.org/api/query?f=json&limit=100&upload_id=${track.externalId}",
        )
        val first = array.optJSONObject(0) ?: throw IllegalStateException("ccMixter: vacío")
        val best = pickBestFile(first.optJSONArray("files"))
            ?: throw IllegalStateException("ccMixter: sin archivos de audio")
        val url = best.optString("download_url").trim()
        if (!url.startsWith("http")) throw IllegalStateException("ccMixter: archivo sin URL")
        Log.i("ResonarOnline", "ccMixter stream: $url")
        return StreamHandle(Uri.parse(url), best.optJSONObject("file_format_info")?.optString("mime_type"))
    }

    /** ccMixter publica el MISMO tema en varios formatos: se listan los reales. */
    override suspend fun listQualities(track: OnlineTrack): List<AudioOption> {
        val array = runCatching {
            Http.getJsonArray("https://ccmixter.org/api/query?f=json&limit=100&upload_id=${track.externalId}")
        }.getOrNull() ?: return emptyList()
        val first = array.optJSONObject(0) ?: return emptyList()
        val files = first.optJSONArray("files") ?: return emptyList()
        val out = ArrayList<AudioOption>()
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val url = f.optString("download_url").trim()
            if (!url.startsWith("http")) continue
            val info = f.optJSONObject("file_format_info")
            val mime = info?.optString("mime_type").orEmpty()
            val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
            val format = when {
                mime.contains("flac") || ext == "flac" -> "FLAC"
                ext == "mp3" || mime.contains("mpeg") -> "MP3"
                ext == "ogg" -> "OGG"
                else -> ext.uppercase().ifBlank { "AUDIO" }
            }
            out.add(
                AudioOption(
                    id = f.optString("file_id").ifBlank { url },
                    label = format,
                    mimeType = mime.takeIf { it.isNotBlank() },
                    approxBytes = f.optLong("file_rawsize").takeIf { it > 0 },
                    bitrate = info?.optString("br")?.takeIf { it.isNotBlank() },
                    url = url,
                ),
            )
        }
        return out
    }

    /** El mejor archivo: MP3 primero (universal), después FLAC, después lo que sea. */
    private fun pickBestFile(files: org.json.JSONArray?): org.json.JSONObject? {
        if (files == null) return null
        var mp3: org.json.JSONObject? = null
        var other: org.json.JSONObject? = null
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val url = f.optString("download_url").trim()
            if (!url.startsWith("http")) continue
            val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
            if (ext == "mp3") { mp3 = f; break }
            if (other == null) other = f
        }
        return mp3 ?: other
    }

    /** "2:03" → 123.000 ms. */
    private fun parseDuration(file: org.json.JSONObject): Long? {
        val ps = file.optJSONObject("file_format_info")?.optString("ps")?.trim().orEmpty()
        if (ps.isBlank()) return null
        val parts = ps.split(":")
        val seconds = parts.fold(0L) { acc, part -> acc * 60 + (part.toLongOrNull() ?: 0L) }
        return if (seconds > 0) seconds * 1_000L else null
    }
}
