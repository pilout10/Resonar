package com.resonar.app.data

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.resonar.app.online.OnlineRepository
import com.resonar.app.online.OnlineTrack
import java.text.Normalizer

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

    /**
     * Clave anti-duplicado por contenido: "tema|artista" normalizada. El id
     * solo alcanza si el mismo archivo entra dos veces, pero la MISMA canción
     * puede estar en la cola con ids distintos (la copia descargada de un tema
     * online, o la misma búsqueda de la radio que ya estaba como archivo
     * local). Por eso la radio compara también esto.
     *
     * La normalización limpia el ruido típico de re-subidas que no aporta
     * identidad: "official video", "letra/lyrics", "y2mate.com", paréntesis y
     * signos, y el nombre del artista pegado al título ("Tal Vez Paulo
     * Londra" → "tal vez"). No es 100 %: re-subidas con metadatos peor
     * armados siguen escapándose (documentado en el INFORME).
     */
    fun queueKey(title: CharSequence?, artist: CharSequence?): String? {
        val t = title?.toString() ?: return null
        if (t.isBlank()) return null
        val a = artist?.toString().orEmpty()
        val titTok = cleanTokens(t, cleanTokens(a))
        val artTok = cleanTokens(a)
        if (titTok.isEmpty()) return null
        return (titTok.joinToString(" ") + "|" + artTok.joinToString(" "))
    }

    /** Palabras que no identifican una versión de una canción. */
    private val ruido = setOf(
        "official", "oficial", "video", "audio", "letra", "lyrics", "lyric",
        "videoclip", "clip", "hd", "hq", "y2mate", "y2matecom",
    )

    /** Minúsculas, sin tildes, troceado por signos, sin palabras de ruido. */
    private fun cleanTokens(text: String, quitar: List<String> = emptyList()): List<String> {
        if (text.isBlank()) return emptyList()
        val sinTilde = Regex("\\p{Mn}+")
        val plano = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(sinTilde, "")
        var tokens = plano.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotEmpty() && it !in ruido }
        // El artista pegado al título ("tal vez paulo londra" + artista
        // "paulo londra"): lo saco si aparece tal cual al inicio o al final.
        if (quitar.isNotEmpty() && tokens.size > quitar.size) {
            if (tokens.take(quitar.size) == quitar) tokens = tokens.drop(quitar.size)
            else if (tokens.takeLast(quitar.size) == quitar) tokens = tokens.dropLast(quitar.size)
        }
        return tokens
    }

    suspend fun build(
        library: LocalLibraryRepository,
        syncer: LibrarySyncer,
        online: OnlineRepository,
        seedMediaId: String?,
        seedArtist: String?,
        seedTitle: String?,
        excludeMediaIds: Set<String>,
        queueKeys: Set<String>,
    ): List<MediaItem> {
        val tracks = library.state.value.tracks
        fun excluded(track: LocalTrack): Boolean {
            if ("local:${track.id}" in excludeMediaIds) return true
            val key = queueKey(track.title, track.artist)
            return key != null && key in queueKeys
        }

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
                    .filter { track ->
                        if (track.mediaId in excludeMediaIds) return@filter false
                        val key = queueKey(track.title, track.artist)
                        key == null || key !in queueKeys
                    }
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
