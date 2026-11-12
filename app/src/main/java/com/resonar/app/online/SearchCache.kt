package com.resonar.app.online

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * CACHÉ DE RESULTADOS DE BÚSQUEDA, con vencimiento (TTL).
 *
 * POR QUÉ ESTA INTERFAZ Y NO ROOM: el plan original pedía Room para esto, pero
 * para un caché de consultas el motor de base de datos no aporta nada (son
 * pocas filas, se leen enteras y se escriben enteras) y sí cuesta: motor,
 * KSP y RAM. Acá hay un archivo JSON.
 *
 * La INTERFAZ es lo importante: si algún día se quiere Room, se escribe otra
 * implementación de SearchCache y no se toca ni la UI ni el repositorio.
 *
 * Reglas:
 *  - Solo se cachea una búsqueda COMPLETA (si alguna fuente falló, no se cachea:
 *    es mejor volver a consultar que mostrar resultados incompletos).
 *  - Vencen a las 24 h.
 *  - Tope de 30 consultas (la más vieja se cae) y 40 resultados por consulta:
 *    el archivo no crece sin límite en un teléfono de 2 GB.
 */
interface SearchCache {
    /** Resultados cacheados de la consulta, o null si no hay o venció. */
    suspend fun get(query: String): List<OnlineTrack>?

    /** Guarda los resultados de una consulta. */
    suspend fun put(query: String, tracks: List<OnlineTrack>)
}

/** Caché en un archivo JSON dentro de la carpeta privada de la app. */
class JsonSearchCache(
    context: Context,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) : SearchCache {

    private val file = File(context.applicationContext.filesDir, "busquedas_cache.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = kotlinx.coroutines.sync.Mutex()

    /** En memoria: el archivo se lee una vez por sesión y se escribe al guardar. */
    private var entries: MutableMap<String, CacheEntry> = load()

    override suspend fun get(query: String): List<OnlineTrack>? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = keyOf(query)
            val entry = entries[key]
            if (entry == null) {
                null
            } else {
                val age = System.currentTimeMillis() - entry.savedAt
                if (age > ttlMs) {
                    // Venció: se tira y se devuelve null (la app vuelve a consultar).
                    entries.remove(key)
                    save()
                    null
                } else {
                    entry.tracks.map { it.toTrack() }
                }
            }
        }
    }

    override suspend fun put(query: String, tracks: List<OnlineTrack>) {
        if (tracks.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val cached = tracks.take(MAX_TRACKS).map { it.toCached() }
                entries[keyOf(query)] = CacheEntry(savedAt = System.currentTimeMillis(), tracks = cached)
                // Tope: se cae la más vieja.
                if (entries.size > MAX_QUERIES) {
                    val oldest = entries.minByOrNull { it.value.savedAt }?.key
                    if (oldest != null) entries.remove(oldest)
                }
                save()
            }
        }
    }

    private fun save() {
        runCatching { file.writeText(json.encodeToString<Map<String, CacheEntry>>(entries)) }
            .onFailure { Log.w("ResonarOnline", "No pude guardar el caché: ${it.message}") }
    }

    private fun load(): MutableMap<String, CacheEntry> {
        val loaded = runCatching {
            if (!file.exists()) return@runCatching emptyMap()
            json.decodeFromString<Map<String, CacheEntry>>(file.readText())
        }.getOrDefault(emptyMap())
        return loaded.toMutableMap()
    }

    /** La clave no distingue mayúsculas ni espacios: "Adele" y "adele " son la misma. */
    private fun keyOf(query: String): String = query.trim().lowercase()

    companion object {
        const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000
        const val MAX_QUERIES = 30
        const val MAX_TRACKS = 40
    }
}

/** Una consulta cacheada: cuándo se guardó y qué se encontró. */
@Serializable
data class CacheEntry(
    val savedAt: Long,
    val tracks: List<CachedTrack>,
)

/**
 * Como el modelo [OnlineTrack] puede cambiar (se le agregan campos), el caché
 * guarda una versión propia y se convierte al vuelo. Si un campo falta, se usa
 * el valor por defecto: un caché viejo nunca rompe la app.
 */
@Serializable
data class CachedTrack(
    val sourceId: String,
    val externalId: String,
    val title: String,
    val artist: String,
    val durationMs: Long? = null,
    val artUrl: String? = null,
    val license: String? = null,
    val albumTitle: String? = null,
    val isPreview: Boolean = false,
    val providerLabel: String? = null,
    val isrc: String? = null,
) {
    fun toTrack(): OnlineTrack = OnlineTrack(
        sourceId = sourceId,
        externalId = externalId,
        title = title,
        artist = artist,
        durationMs = durationMs,
        artUrl = artUrl,
        license = license,
        albumTitle = albumTitle,
        isPreview = isPreview,
        providerLabel = providerLabel,
        isrc = isrc,
    )
}

/** OnlineTrack → versión cacheable (mismos campos, copia propia). */
fun OnlineTrack.toCached(): CachedTrack = CachedTrack(
    sourceId = sourceId,
    externalId = externalId,
    title = title,
    artist = artist,
    durationMs = durationMs,
    artUrl = artUrl,
    license = license,
    albumTitle = albumTitle,
    isPreview = isPreview,
    providerLabel = providerLabel,
    isrc = isrc,
)
