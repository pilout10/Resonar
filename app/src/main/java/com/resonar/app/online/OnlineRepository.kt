package com.resonar.app.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlin.math.abs

/**
 * MOTOR DE BÚSQUEDA FEDERADA (2A).
 *
 * Reglas que el plan manda y que este archivo cumple:
 *  - Dispara en PARALELO sobre las fuentes habilitadas, con MÁXIMO 3 a la vez
 *    (Semaphore) y TIMEOUT de 8 s por fuente: una fuente caída NO bloquea ni
 *    rompe a las demás — se reporta y listo.
 *  - Orden por relevancia: los resultados se INTERCALAN entre fuentes
 *    (nunca una lista de Audius y después una de Archive) y DESPUÉS se deduplican.
 *  - Dedupe ANTES de mostrar: título+artista normalizados y duración ±5 s
 *    (cuando las dos fuentes informan duración). Un solo resultado por tema,
 *    con variantes guardadas para la hoja inferior de "versiones".
 */

/** Un tema único con sus versiones de otras fuentes. */
data class OnlineResult(
    val primary: OnlineTrack,
    val variants: List<OnlineTrack> = emptyList(),
)

/** Lo que salió de una búsqueda federada completa. */
data class FederatedResults(
    val results: List<OnlineResult>,
    /** Fuentes que fallaron (para el aviso "fuente caída", sin fingir que nada pasó). */
    val failedSources: List<String>,
)

class OnlineRepository(private val app: Context) {

    val registry = SourceRegistry(app)

    // ── Búsqueda federada ───────────────────────────────────────────────

    suspend fun federatedSearch(query: String, page: Int = 0): FederatedResults {
        val sources = registry.enabledSources()
        if (sources.isEmpty()) return FederatedResults(emptyList(), emptyList())
        val semaphore = Semaphore(3) // máximo 3 pedidos en vuelo: red y RAM contenidas

        suspend fun runAll(q: String): Pair<List<List<OnlineTrack>>, List<String>> = coroutineScope {
            val perSource = sources.map { source ->
                async {
                    semaphore.withPermit {
                        try {
                            withTimeout(8_000) { source.search(q, page) }
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "ResonarOnline",
                                "Fuente ${source.displayName} falló: ${e.message}",
                            )
                            null // fuente caída: no rompe a las demás
                        }
                    }
                }
            }.awaitAll()
            val failed = sources.map { it.displayName }
                .filterIndexed { index, _ -> perSource[index] == null }
            perSource.filterNotNull() to failed
        }

        var (tracks, failed) = runAll(query)
        // Si NADIE devolvió nada, se reintenta con variantes de la consulta
        // ("adele feat. x" → "adele", sin tildes, sin paréntesis). Es lo que pide
        // el plan: una búsqueda vacía se reintenta con las variantes normalizadas.
        if (tracks.all { it.isEmpty() }) {
            for (variant in queryVariants(query)) {
                android.util.Log.i("ResonarOnline", "Sin resultados: reintento con '$variant'")
                val retry = runAll(variant)
                if (retry.first.any { it.isNotEmpty() }) {
                    tracks = retry.first
                    failed = retry.second
                    break
                }
            }
        }
        return FederatedResults(dedupe(interceave(tracks)), failed)
    }

    /**
     * Variantes de una consulta para reintentar cuando no hay resultados:
     * sin paréntesis, sin "feat", sin tildes y sin palabras de versión.
     */
    fun queryVariants(query: String): List<String> {
        val out = ArrayList<String>(4)
        val withoutParens = query.replace(Regex("[()\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim()
        if (withoutParens.isNotBlank() && withoutParens != query) out.add(withoutParens)
        val withoutFeat = withoutParens.replace(Regex("(?i)\\b(feat|ft|featuring)\\b.*$"), "").trim()
        if (withoutFeat.isNotBlank() && withoutFeat != withoutParens) out.add(withoutFeat)
        val withoutNoise = withoutFeat
            .replace(Regex("(?i)\\b(official|video|audio|live|remaster(ed)?|lyrics|karaoke)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (withoutNoise.isNotBlank() && withoutNoise != withoutFeat) out.add(withoutNoise)
        val noAccents = java.text.Normalizer.normalize(withoutNoise, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .trim()
        if (noAccents.isNotBlank() && noAccents != withoutNoise) out.add(noAccents)
        return out
    }

    /** Intercala fuente por fuente: relevancia pareja, nunca agrupado por fuente. */
    private fun interceave(lists: List<List<OnlineTrack>>): List<OnlineTrack> {
        val out = ArrayList<OnlineTrack>()
        var index = 0
        while (out.size < lists.sumOf { it.size }) {
            lists.forEach { list -> if (index < list.size) out.add(list[index]) }
            index++
        }
        return out
    }

    /** Un solo resultado por tema. El primero del grupo manda; el resto son versiones. */
    fun dedupe(tracks: List<OnlineTrack>): List<OnlineResult> {
        val groups = ArrayList<MutableList<OnlineTrack>>()
        for (track in tracks) {
            val group = groups.firstOrNull { matches(it.first(), track) }
            if (group != null) group.add(track) else groups.add(mutableListOf(track))
        }
        return groups.map { OnlineResult(it.first(), it.drop(1)) }
    }

    /**
     * Match difuso: título normalizado igual (o uno contiene al otro, para
     * "Tema" vs "Tema (En Vivo)"), artista igual si ambos lo informan,
     * duración dentro de ±5 s si AMBAS fuentes la informan (IA a veces no).
     */
    private fun matches(a: OnlineTrack, b: OnlineTrack): Boolean {
        val ta = normalize(a.title)
        val tb = normalize(b.title)
        if (ta.isBlank() || tb.isBlank()) return false
        val titlesMatch = ta == tb || ta.contains(tb) || tb.contains(ta)
        if (!titlesMatch) return false
        val aa = normalize(a.artist)
        val ab = normalize(b.artist)
        if (aa.isNotBlank() && ab.isNotBlank() && aa != ab) {
            // "artista - tema" en una fuente y solo "tema" en la otra:
            // si uno contiene al otro, no lo penalizamos.
            if (!(aa.contains(ab) || ab.contains(aa))) return false
        }
        val da = a.durationMs
        val db = b.durationMs
        if (da != null && db != null && abs(da - db) > 5_000) return false
        return true
    }

    companion object {
        /** Normalización del match: minúsculas, sin tildes, sin paréntesis, sin ruido. */
        private val NOISE = listOf(
            "official video", "official audio", "official music video", "official",
            "video oficial", "audio oficial", "lyrics", "letra", "hd", "hq", "4k",
            "remastered", "remasterizado", "visualizer", "cover art", "feat",
            "ft", "en vivo", "live", "karaoke", "instrumental", "version", "versión",
        )

        fun normalize(raw: String): String {
            var text = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "") // tildes
                .lowercase()
            text = text.replace(Regex("[\\[()\\]]"), " ") // (algo), [algo]
            NOISE.forEach { text = text.replace(it, " ") }
            return text.replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    // ── Streaming y pegado de URL ───────────────────────────────────────

    /** Convertir un tema en algo jugable. Las fuentes se auto-cachean adentro. */
    suspend fun resolveStream(track: OnlineTrack): StreamHandle =
        registry.byId(track.sourceId)?.resolveStream(track)
            ?: throw IllegalStateException("Fuente desconocida: ${track.sourceId}")

    /**
     * Pegado de URL (atajo secundario): Audius (con /v1/resolve), Internet
     * Archive (con /metadata) o un archivo de audio directo (.mp3/.flac/…).
     * Devuelve null si no es ninguna de esas: honesto, sin fingir.
     */
    suspend fun resolveUrl(url: String): OnlineTrack? {
        val audius = AudiusSource()
        if (audius.canResolveUrl(url)) return runCatching { audius.resolveUrl(url) }.getOrNull()
        val archive = InternetArchiveSource()
        if (archive.canResolveUrl(url)) return runCatching { archive.resolveUrl(url) }.getOrNull()
        val path = Uri.parse(url).path?.lowercase().orEmpty()
        val directFormats = listOf(".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav")
        if (url.startsWith("http") && directFormats.any { path.endsWith(it) }) {
            val name = Uri.parse(url).lastPathSegment.orEmpty().ifBlank { "Archivo" }
            val title = name.substringBeforeLast('.').replace(Regex("[_+]+"), " ").trim()
            return OnlineTrack(
                sourceId = "url",
                externalId = url.hashCode().toString(),
                title = title.ifBlank { name },
                artist = "Archivo directo",
                durationMs = null,
                artUrl = null,
                license = null,
            )
        }
        return null
    }

    /** ¿Hay red? Para mostrar "sin conexión" en vez de una lista vacía que miente. */
    fun isOnline(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
