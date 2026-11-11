package com.resonar.app.online

import android.content.Context

/**
 * Puente entre el MusicService y las fuentes, SIN depender del ViewModel ni de
 * la UI. Lo usa el gancho de re-resolución: cuando un stream "online:…" da 403
 * o venció, el servicio pide acá una URI fresca con solo "fuente + id".
 */
object OnlineCatalog {

    /** Resuelve un tema por su identificador compuesto. null = no se pudo (y se informa). */
    suspend fun resolve(context: Context, sourceId: String, externalId: String): StreamHandle? {
        val source = SourceRegistry(context).byId(sourceId) ?: return null
        // Stub de tema: resolveStream SOLO usa externalId (así se diseñó a propósito).
        val stub = OnlineTrack(
            sourceId = sourceId,
            externalId = externalId,
            title = "",
            artist = "",
            durationMs = null,
            artUrl = null,
        )
        return runCatching { source.resolveStream(stub) }.getOrNull()
    }
}
