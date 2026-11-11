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
) {
    val mediaId: String get() = "online:$sourceId:$externalId"
}

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
 * Cada implementación comenta qué API usa y qué se rompe si el servicio cambia.
 */
interface MusicSource {
    val id: String
    val displayName: String
    val requiresKey: Boolean
    suspend fun search(query: String, page: Int): List<OnlineTrack>
    suspend fun resolveStream(track: OnlineTrack): StreamHandle
}

/**
 * Registro central de fuentes. Los toggles ON/OFF de Ajustes viven en
 * SharedPreferences, así el ViewModel y el MusicService (procesos/hilos
 * distintos) leen SIEMPRE lo mismo.
 */
class SourceRegistry(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("resonar_sources", Context.MODE_PRIVATE)

    /** Las fuentes disponibles en 2A. 2B agrega YouTube acá. */
    val sources: List<MusicSource> = listOf(
        AudiusSource(),
        InternetArchiveSource(),
        YoutubeSource(),
    )

    fun isEnabled(id: String): Boolean = prefs.getBoolean("enabled_$id", true)

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$id", enabled).apply()
    }

    fun enabledSources(): List<MusicSource> = sources.filter { isEnabled(it.id) }

    /** Para el gancho de re-resolución del MusicService: fuente por id o null. */
    fun byId(id: String): MusicSource? = sources.firstOrNull { it.id == id }
}
