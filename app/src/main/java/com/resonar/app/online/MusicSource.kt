package com.resonar.app.online

import android.content.Context

/**
 * Tipos base del motor de fuentes en línea (ETAPA 2A).
 *
 * TEMA compuesto: cada tema vive en una FUENTE y se identifica con
 * "fuente:idExterno". El mediaId del player usa el prefijo "online:" para que
 * el servicio sepa que si falla, hay que RE-RESOLVER (las URIs de stream no
 * viven para siempre) en vez de tratarlo como archivo local roto.
 */

/** Un tema encontrado en una fuente externa (nada de esto es música del teléfono). */
data class OnlineTrack(
    val sourceId: String,
    val externalId: String,
    val title: String,
    val artist: String,
    /** null = la fuente no informa duración (pasa en Internet Archive). */
    val durationMs: Long?,
    val artUrl: String?,
    /** Ej. "Dominio público", "CC BY". null = la fuente no lo dice. */
    val license: String? = null,
    /** Nombre del álbum, si la fuente lo da. */
    val albumTitle: String? = null,
    /**
     * true si la fuente solo puede dar un PREVIO (Deezer: 30 s). La app lo
     * muestra en el badge para no prometer la canción entera.
     */
    val isPreview: Boolean = false,
    /** Fuente original detrás de un agregador (Openverse → Jamendo, Freesound…). */
    val providerLabel: String? = null,
    /** Código ISRC, si la fuente lo informa (sirve para deduplicar). */
    val isrc: String? = null,
) {
    val mediaId: String get() = "online:$sourceId:$externalId"
}

/**
 * UNA calidad que la fuente REALMENTE ofrece (no inventada): formato, bitrate,
 * tamaño aproximado y la URL directa.
 */
data class AudioOption(
    val id: String,
    /** "MP3 320", "FLAC", "OGG 96"… (texto corto para la lista). */
    val label: String,
    val mimeType: String?,
    /** Tamaño aproximado en bytes, si la fuente lo dice. */
    val approxBytes: Long?,
    /** Bitrate tal como lo informa la fuente ("CBR", "128", "VBR"…). */
    val bitrate: String? = null,
    /** URL directa del archivo de esa calidad. */
    val url: String,
)

/** Datos extra de un tema, de donde la fuente los pueda sacar. */
data class TrackMetadata(
    val isrc: String? = null,
    val year: Int? = null,
    val label: String? = null,
    val albumTitle: String? = null,
    val artUrl: String? = null,
)

/** Lo que una fuente entrega para que ExoPlayer abra y suene. */
data class StreamHandle(
    val uri: android.net.Uri,
    /** "audio/mpeg", "audio/mp4"… null = que ExoPlayer lo deduzca del archivo. */
    val mimeType: String?,
)

/**
 * Contrato de una FUENTE de música en línea. Liviano a propósito:
 *  - search: lista de resultados para una consulta y página.
 *  - resolveStream: convierte el tema en algo jugable. Solo usa externalId,
 *    así el servicio puede re-resolver sin tener los metadatos completos.
 *  - listQualities: qué calidades ofrece DE VERDAD (para el selector de
 *    descarga). Por defecto, ninguna: cada fuente implementa lo que sabe.
 *  - getMetadata: datos extra (ISRC, año, sello). Por defecto, nada.
 * Cada implementación comenta qué API usa y qué se rompe si el servicio cambia.
 */
interface MusicSource {
    val id: String
    val displayName: String
    val requiresKey: Boolean
    suspend fun search(query: String, page: Int): List<OnlineTrack>
    suspend fun resolveStream(track: OnlineTrack): StreamHandle

    /** Calidades reales que ofrece la fuente para ese tema. */
    suspend fun listQualities(track: OnlineTrack): List<AudioOption> = emptyList()

    /** Datos extra del tema, si la fuente los puede dar. */
    suspend fun getMetadata(track: OnlineTrack): TrackMetadata? = null
}

/**
 * Registro central de fuentes. Los toggles ON/OFF de Ajustes viven en
 * SharedPreferences, así el ViewModel y el MusicService (procesos/hilos
 * distintos) leen SIEMPRE lo mismo.
 */
class SourceRegistry(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("resonar_sources", Context.MODE_PRIVATE)

    /**
     * Todas las fuentes, en el ORDEN DE PRIORIDAD (la primera es la que se
     * consulta primero y la que gana si dos fuentes traen lo mismo).
     * YouTube va primero: es el catálogo más amplio que existe.
     *
     * DEEZER NO ESTÁ EN LA LISTA: su API pública solo entrega un PREVIEW de
     * 30 segundos, no el tema entero. Un reproductor con previews es ruido, así
     * que no aparece en las búsquedas. La clase sigue en el proyecto porque su
     * API es una de las mejores para METADATOS (ISRC, año, sello, carátula
     * grande) y se usa para completar la info de los resultados de otras
     * fuentes, sin que el usuario vea temas que suenan a medias.
     */
    val sources: List<MusicSource> = listOf(
        YoutubeSource(),
        AudiusSource(),
        CcMixterSource(),
        OpenverseSource(),
        InternetArchiveSource(),
        JamendoSource(),
    )

    /** Fuentes que se pueden usar de verdad (con key, solo si la key está puesta). */
    fun availableSources(): List<MusicSource> = sources.filter { source ->
        when {
            !source.requiresKey -> true
            source is JamendoSource -> source.isConfigured()
            else -> true
        }
    }

    fun isEnabled(id: String): Boolean = prefs.getBoolean("enabled_$id", true)

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$id", enabled).apply()
    }

    /** Fuentes habilitadas Y disponibles, en orden de prioridad. */
    fun enabledSources(): List<MusicSource> =
        availableSources().filter { isEnabled(it.id) }

    /** Para el gancho de re-resolución del MusicService: fuente por id o null. */
    fun byId(id: String): MusicSource? = sources.firstOrNull { it.id == id }
}
