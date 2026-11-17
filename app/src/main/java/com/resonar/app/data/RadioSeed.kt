package com.resonar.app.data

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.resonar.app.online.OnlineRepository
import com.resonar.app.online.OnlineTrack

/**
 * RADIO AUTOMÁTICA (Etapa 4): qué temas sumar cuando la cola termina.
 *
 * Orden de "parecido" (honesto, sin magia):
 *  1. Mismo artista que el tema que sonaba.
 *  2. Mismo género (si el archivo trae la etiqueta).
 *  3. Cualquier tema de la biblioteca, al azar.
 *  4. Si la biblioteca dio poco Y hay internet: búsqueda en línea con el nombre
 *     del artista (o del tema), resolviendo cada stream ANTES de sumarlo — si
 *     un stream no se resuelve, ese tema no entra.
 *
 * Devuelve lo que consiguió; si no consiguió nada, lista vacía y la cola
 * queda como estaba. Nunca se inventa un "parecido" que no existe.
 */
object RadioSeed {

    /** Cuántos temas agrega la radio por tanda. */
    private const val TAKE = 8

    suspend fun build(
        library: LocalLibraryRepository,
        syncer: LibrarySyncer,
        online: OnlineRepository,
        seedMediaId: String?,
        seedArtist: String?,
        seedTitle: String?,
        excludeMediaIds: Set<String>,
    ): List<MediaItem> {
        val tracks = library.state.value.tracks
        fun excluded(track: LocalTrack) = "local:${track.id}" in excludeMediaIds

        val seedTrack = seedMediaId?.removePrefix("local:")?.toLongOrNull()
            ?.let { id -> tracks.firstOrNull { it.id == id } }
        val artistKey = seedTrack?.artist?.takeIf { it.isNotBlank() }
            ?: seedArtist?.takeIf { it.isNotBlank() }
        val genreKey = seedTrack?.genre?.takeIf { it.isNotBlank() }

        // Biblioteca local: mismo artista primero, después género, después todo.
        val sameArtist = artistKey?.let { key ->
            tracks.filter { it.artist.equals(key, ignoreCase = true) }
        }.orEmpty()
        val sameGenre = genreKey?.let { key ->
            tracks.filter { it.genre.equals(key, ignoreCase = true) }
        }.orEmpty()

        val picks = LinkedHashMap<Long, LocalTrack>()
        sameArtist.shuffled().forEach { if (!excluded(it)) picks[it.id] = it }
        if (picks.size < TAKE / 2) sameGenre.shuffled().forEach { if (!excluded(it)) picks[it.id] = it }
        if (picks.size < TAKE / 2) tracks.shuffled().forEach { if (!excluded(it)) picks[it.id] = it }

        val items = mutableListOf<MediaItem>()
        picks.values.take(TAKE).forEach { items += it.toRadioItem { t -> syncer.localUriFor(t) } }

        // Refuerzo online solo si la biblioteca dio poco. Con el artista como
        // consulta (una radio por artista); sin artista, el título del tema.
        if (items.size < 4) {
            val query = artistKey ?: seedTitle
            if (!query.isNullOrBlank() && online.isOnline()) {
                val results = runCatching { online.federatedSearch(query.trim()) }.getOrNull()
                val candidates = results?.results.orEmpty()
                    .map { it.primary }
                    .filter { it.mediaId !in excludeMediaIds }
                for (track in candidates) {
                    if (items.size >= TAKE) break
                    // Cada stream se resuelve antes de entrar a la cola: si
                    // falla, ese tema no entra (no se promete lo que no hay).
                    val handle = runCatching { online.resolveStream(track) }.getOrNull() ?: continue
                    items += track.toStreamItem(handle.uri)
                }
            }
        }
        return items
    }

    /** MediaItem de un tema local (misma lógica que usa el ViewModel para la cola). */
    private fun LocalTrack.toRadioItem(localUriFor: (LocalTrack) -> Uri?): MediaItem {
        val artUri: Uri? = artUrl?.let { Uri.parse(it) }
            ?: if (albumArtId > 0L) albumArtUri(albumArtId) else null
        val playUri = localUriFor(this) ?: Uri.parse(uri)
        return MediaItem.Builder()
            .setMediaId("local:$id")
            .setUri(playUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artUri)
                    .build(),
            )
            .build()
    }

    /**
     * MediaItem de un tema online ya resuelto. El mediaId "online:fuente:id"
     * es el mismo de la búsqueda: si el stream vence, el servicio lo re-resuelve.
     */
    private fun OnlineTrack.toStreamItem(uri: Uri): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(artUrl?.let { Uri.parse(it) })
                    .build(),
            )
            .build()
}
