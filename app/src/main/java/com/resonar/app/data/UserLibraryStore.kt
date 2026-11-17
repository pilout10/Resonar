package com.resonar.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Tema de la búsqueda ONLINE guardado dentro de una playlist (Etapa 3 parte 3).
 * No vive en MediaStore ni en la biblioteca: es una referencia a lo que se
 * puede buscar/bajar. Su mediaId es el mismo que en la búsqueda, así el ✓ de
 * descarga y la cola funcionan igual.
 */
@Serializable
data class OnlineTrackRef(
    val mediaId: String,
    val sourceId: String,
    val externalId: String,
    val title: String,
    val artist: String,
    val durationMs: Long? = null,
    val artUrl: String? = null,
) {
    /** El mismo tema, listo para reproducir o descargar (la cola habla en OnlineTrack). */
    fun toOnlineTrack(): com.resonar.app.online.OnlineTrack =
        com.resonar.app.online.OnlineTrack(
            sourceId = sourceId,
            externalId = externalId,
            title = title,
            artist = artist,
            durationMs = durationMs,
            artUrl = artUrl,
        )
}

/** Recorta un tema de la búsqueda a su referencia persistente (lo que guarda una playlist). */
fun com.resonar.app.online.OnlineTrack.toRef(): OnlineTrackRef = OnlineTrackRef(
    mediaId = mediaId,
    sourceId = sourceId,
    externalId = externalId,
    title = title,
    artist = artist,
    durationMs = durationMs,
    artUrl = artUrl,
)

/** Playlist armada por la persona. El id es estable para poder abrirla después de reiniciar. */
@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val trackIds: List<Long> = emptyList(),
    /** Temas de la búsqueda online: se reproducen por stream y se pueden bajar. */
    val onlineTracks: List<OnlineTrackRef> = emptyList(),
)

/** Favoritos, recientes y playlists. Vive en SharedPreferences: no hace falta Room para esto. */
@Serializable
data class UserLibrarySnapshot(
    val favoriteIds: List<Long> = emptyList(),
    val recentIds: List<Long> = emptyList(),
    val playlists: List<UserPlaylist> = emptyList(),
)

class UserLibraryStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("resonar_user", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<UserLibrarySnapshot> = _state.asStateFlow()

    fun toggleFavorite(trackId: Long) = update { snap ->
        val next = if (trackId in snap.favoriteIds) snap.favoriteIds - trackId else listOf(trackId) + snap.favoriteIds
        snap.copy(favoriteIds = next.distinct())
    }

    /** Lo más nuevo queda primero. Tope de 40 para que la fila de inicio no crezca sin límite. */
    fun recordRecent(trackId: Long) = update { snap ->
        val next = (listOf(trackId) + snap.recentIds.filter { it != trackId }).take(MAX_RECENT)
        snap.copy(recentIds = next)
    }

    fun forgetRecent(trackId: Long) = update { snap ->
        snap.copy(recentIds = snap.recentIds.filter { it != trackId })
    }

    /** Devuelve el id nuevo, o null si el nombre está vacío. */
    fun createPlaylist(rawName: String): String? {
        val name = rawName.trim().take(40)
        if (name.isEmpty()) return null
        val id = UUID.randomUUID().toString()
        update { it.copy(playlists = it.playlists + UserPlaylist(id, name)) }
        return id
    }

    fun deletePlaylist(id: String) = update { snap ->
        snap.copy(playlists = snap.playlists.filter { it.id != id })
    }

    fun addToPlaylist(playlistId: String, trackId: Long) = update { snap ->
        snap.copy(
            playlists = snap.playlists.map { playlist ->
                if (playlist.id != playlistId || trackId in playlist.trackIds) playlist
                else playlist.copy(trackIds = playlist.trackIds + trackId)
            },
        )
    }

    /**
     * Suma un tema de la búsqueda ONLINE a la playlist (Etapa 3 parte 3).
     * Se evita duplicar por mediaId (la misma búsqueda puede tocarla dos veces).
     */
    fun addOnlineToPlaylist(playlistId: String, ref: OnlineTrackRef) = update { snap ->
        snap.copy(
            playlists = snap.playlists.map { playlist ->
                if (playlist.id != playlistId || playlist.onlineTracks.any { it.mediaId == ref.mediaId }) playlist
                else playlist.copy(onlineTracks = playlist.onlineTracks + ref)
            },
        )
    }

    fun removeOnlineFromPlaylist(playlistId: String, mediaId: String) = update { snap ->
        snap.copy(
            playlists = snap.playlists.map { playlist ->
                if (playlist.id != playlistId) playlist
                else playlist.copy(onlineTracks = playlist.onlineTracks.filter { it.mediaId != mediaId })
            },
        )
    }

    fun removeFromPlaylist(playlistId: String, trackId: Long) = update { snap ->
        snap.copy(
            playlists = snap.playlists.map { playlist ->
                if (playlist.id != playlistId) playlist
                else playlist.copy(trackIds = playlist.trackIds.filter { it != trackId })
            },
        )
    }

    /**
     * Borrado limpio de una canción: desaparece de favoritos, de "escuchadas hace
     * poco" y de TODAS las playlists a la vez. Así no quedan fantasmas que
     * apuntan a un tema que ya no existe.
     */
    fun removeEverywhere(trackId: Long) = update { snap ->
        snap.copy(
            favoriteIds = snap.favoriteIds.filter { it != trackId },
            recentIds = snap.recentIds.filter { it != trackId },
            playlists = snap.playlists.map { playlist ->
                playlist.copy(trackIds = playlist.trackIds.filter { it != trackId })
            },
        )
    }

    private fun update(transform: (UserLibrarySnapshot) -> UserLibrarySnapshot) {
        val next = transform(_state.value)
        _state.value = next
        runCatching { prefs.edit().putString(KEY, json.encodeToString(next)).apply() }
    }

    private fun load(): UserLibrarySnapshot = runCatching {
        prefs.getString(KEY, null)?.let { json.decodeFromString<UserLibrarySnapshot>(it) }
    }.getOrNull() ?: UserLibrarySnapshot()

    private companion object {
        const val KEY = "user_library"
        const val MAX_RECENT = 40
    }
}
