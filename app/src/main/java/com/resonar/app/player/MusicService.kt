package com.resonar.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.resonar.app.data.TrackNames
import com.resonar.app.online.OnlineCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Servicio de reproducción (ETAPA 0).
 *
 * Puntos clave por los que acá pasa TODO el audio de Resonar:
 *  - Hereda de MediaSessionService: Media3 se encarga de mostrar la notificación
 *    con controles (play/pausa/siguiente/anterior), integrarla con la pantalla de bloqueo,
 *    con los auriculares y con el sistema — sin trabajo extra de nuestra parte.
 *  - El manifest lo declara con foregroundServiceType="mediaPlayback" y el intent-filter
 *    correspondiente, así el sistema puede reconectar y pedir reanudación.
 *  - Estado (canción, índice, posición, cola) se persiste siempre: si el sistema mata el
 *    proceso, onPlaybackResumption reanuda excluyente desde la notificación o el botón del auricular.
 */
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var focusHandler: AudioFocusHandler? = null
    private var saveScope: CoroutineScope? = null
    private var tone: PlaybackEqualizer? = null
    private lateinit var store: PlaybackStateStore
    private var cachedPlaylist: List<SavedTrack>? = null
    private var cachedCount = -1
    private var cachedEnds: String? = null
    /** mediaIds "online:…" que ya intentamos re-resolver una vez (no insistimos). */
    private val reResolved = HashSet<String>()
    /** Scope propio del servicio para tareas que sobreviven a los eventos. */
    private var scope: CoroutineScope? = null
    // Coalescer de guardados de posición: en una ráfaga de eventos (cambio de tema,
    // READY, etc.) solo la ÚLTIMA posición llega a disco, 250 ms después del primero.
    private var pendingPosition: PlaybackPosition? = null
    private var positionSaveJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        store = PlaybackStateStore(this)

        // ── Player ──────────────────────────────────────────────────────────
        // setWakeMode(C.WAKE_MODE_NETWORK): mientras transmite, mantiene la CPU despierta
        //   (con WAKE_LOCK) para que el audio no se corte al entrar en deep sleep.
        // attributes de MÚSICA (USAGE_MEDIA + CONTENT_TYPE_MUSIC): routing de audio correcto
        //   (parlante/auricular bluetooth/stream de música).
        // handleAudioFocus = false: el foco lo maneja AudioFocusHandler (nuestro código), NO ExoPlayer.
        // Arranca la siguiente apenas hay un cuarto de segundo listo.
        // El buffer por defecto espera 1 s y, en un FLAC, se nota al pasar de tema.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 10_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 250,
                /* bufferForPlaybackAfterRebufferMs = */ 500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Caché de streaming: los temas de Drive se bajan UNA vez a disco y después
            // suenan sin red. Los archivos locales siguen leyéndose directo (sin copias).
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(SmartDataSourceFactory(this) { store.isStreamCacheEnabled() }),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false,
            )
            .build()

        // Sin un id propio, en este Motorola audioSessionId queda en 0 y el
        // ecualizador nunca se crea: el interruptor queda gris.
        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val sessionId = audioManager.generateAudioSessionId()
        if (sessionId != android.media.AudioManager.ERROR) {
            player.setAudioSessionId(sessionId)
        }

        focusHandler = AudioFocusHandler(this, player) { store.isKeepPlayingEnabled() }
        tone = PlaybackEqualizer(store, player).also { it.start() }

        // ── Restaurar estado guardado (canción, índice, posición, cola) ─────
        // Se restaura PAUSADO: el usuario decide si sigue (o el sistema llama a onPlaybackResumption).
        store.loadState()?.let { saved ->
            if (saved.playlist.isNotEmpty()) {
                // Poda de la cola guardada: puede traer canciones que ya no
                // están (el usuario las borró, o cambió de carpeta de música).
                // Sin esto, el reproductor intenta abrirlas y muestra
                // "No se pudo abrir" en el mini reproductor.
                val keptIndices = saved.playlist.indices.filter { existsLocally(saved.playlist[it].uri) }
                val playlist = keptIndices.map { saved.playlist[it] }
                if (playlist.isEmpty()) {
                    Log.i("ResonarPlayer", "Cola guardada vacía tras la poda: no se restaura")
                    store.savePlaylistJson(emptyList())
                } else {
                    val index = keptIndices.indexOf(saved.currentIndex).let { if (it >= 0) it else 0 }
                    if (keptIndices.size != saved.playlist.size) {
                        Log.i("ResonarPlayer", "Cola podada: ${saved.playlist.size} → ${playlist.size} temas")
                    }
                    player.setMediaItems(
                        playlist.map { cleanSavedTrack(it) }.map { savedToMediaItem(it) },
                        index,
                        saved.positionMs,
                    )
                    player.prepare()
                }
            }
        }

        // ── Foco de audio + persistencia en eventos clave del player ────────
        player.addListener(object : Player.Listener {
            /** Temas rotos seguidos: si pasan 4, freno en vez de girar la cola en falso. */
            private var consecutiveErrors = 0

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.i("ResonarPlayer", "isPlaying=$isPlaying pos=${player.currentPosition} ms")
                tone?.refreshSession()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                Log.i("ResonarPlayer", "audioSessionId=$audioSessionId")
                tone?.refreshSession()
            }

            /**
             * DIAGNÓSTICO: cada tema que no arranca deja acá su causa exacta
             * (archivo faltante, sin permiso, formato no soportado, corrupto…).
             * Además la app NO queda trabada: salta al siguiente de la cola.
             *
             * Temas EN LÍNEA (mediaId "online:fuente:id"): antes de saltar
             * probamos RE-RESOLVER el stream una vez — las URIs de stream vencen
             * o dan 403, pero el tema sigue existiendo en su fuente.
             */
            override fun onPlayerError(error: PlaybackException) {
                Log.e("ResonarPlayer", "Error al reproducir: ${error.errorCodeName}", error)
                error.cause?.let { cause -> Log.e("ResonarPlayer", "  Causa: ${cause.message}", cause) }
                consecutiveErrors++
                if (consecutiveErrors > 3) {
                    Log.w("ResonarPlayer", "Demasiados errores seguidos: pauso la cola")
                    player.pause()
                    return
                }
                val current = player.currentMediaItem
                val mediaId = current?.mediaId.orEmpty()
                val isSourceError = error.errorCodeName.startsWith("ERROR_CODE_IO") ||
                    error.errorCodeName.startsWith("ERROR_CODE_SOURCE") ||
                    error.errorCodeName == "ERROR_CODE_TIMEOUT"
                if (current != null && mediaId.startsWith("online:") && isSourceError && mediaId !in reResolved) {
                    if (reResolved.size > 32) reResolved.clear()
                    reResolved.add(mediaId)
                    val index = player.currentMediaItemIndex
                    val parts = mediaId.split(":")
                    Log.w("ResonarPlayer", "Stream vencido/403: re-resolviendo con la fuente")
                    scope?.launch {
                        val handle = runCatching {
                            OnlineCatalog.resolve(applicationContext, parts[1], parts[2])
                        }.getOrNull()
                        if (handle != null) {
                            val replaced = current!!.buildUpon()
                                .setUri(handle.uri)
                                .build()
                            player.replaceMediaItem(index, replaced)
                            if (player.playbackState == Player.STATE_IDLE) {
                                player.prepare()
                                player.play()
                            }
                        } else {
                            Log.w("ResonarPlayer", "Re-resolución falló: siguiente tema")
                            if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.pause()
                        }
                    }
                    return
                }
                if (player.hasNextMediaItem()) {
                    Log.w("ResonarPlayer", "Salto al siguiente tema")
                    player.seekToNextMediaItem()
                } else {
                    player.pause()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Log.i("ResonarPlayer", "transición -> mediaId=${mediaItem?.mediaId} reason=$reason")
                saveSession(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val name = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    else -> "ENDED"
                }
                Log.i("ResonarPlayer", "estado=$name dur=${player.duration} ms")
                if (playbackState == Player.STATE_READY) consecutiveErrors = 0
                tone?.refreshSession()
                // BUFFERING ocurre en cada cambio de tema. Guardar ahí frena el hilo
                // que está abriendo el archivo siguiente.
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    saveSession(player)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Si algo externo (sistema, auriculares, otra app) manda pausar, queda registrado.
                Log.i("ResonarPlayer", "playWhenReady=$playWhenReady reason=$reason")
                // El foco se pide al dar play y se suelta al pausar.
                // No en el hueco entre una canción y la siguiente: ese hueco es buffering,
                // playWhenReady sigue en true, y soltar el foco hacía tardar el cambio.
                if (playWhenReady) focusHandler?.requestAudioFocus() else focusHandler?.abandonAudioFocus()
                saveSession(player)
            }
        })

        // ── Sesión multimedia ────────────────────────────────────────────────
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(object : MediaSession.Callback {
                /**
                 * PLAYBACK RESUMPTION: si el sistema mató el proceso mientras sonaba,
                 * el usuario puede tocar ▶ en la notificación (o el botón de "play" del auricular)
                 * y el sistema llama acá antes de recrear nada. Devolvemos cola + índice + posición.
                 */
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val restored = store.loadState()
                    return if (restored == null || restored.playlist.isEmpty()) {
                        // Nada que reanudar: el sistema simplemente no muestra el botón de reanudar.
                        // No es un error de la app, no lo tratamos como uno.
                        Futures.immediateFailedFuture(IllegalStateException("No hay sesión previa guardada"))
                    } else {
                        Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(
                                restored.playlist.map { savedToMediaItem(it) },
                                restored.currentIndex,
                                restored.positionMs,
                            ),
                        )
                    }
                }
            })
            .build()

        // ── Guardado periódico de posición mientras suena ───────────────────
        // Cada 15 s, solo si suena. Liviano: escribe SharedPreferences, sin base de datos.
        saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = saveScope
        saveScope?.launch {
            while (isActive) {
                delay(15_000)
                if (player.isPlaying) saveSession(player)
            }
        }
    }

    /**
     * Media3 pide la sesión activa por cada controller nuevo (notificación, app, asistente).
     * Devolvemos siempre la única que creamos en onCreate.
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Serializa el estado actual en DOS partes:
     *  - la COLA (JSON grande) solo se escribe si cambió (otra playlist, otro tamaño),
     *  - la POSICIÓN (JSON chico) se agrupa: en una ráfaga de eventos solo el último
     *    valor llega a disco. Así el cambio de tema no compite con ningún guardado.
     * Se llama en cada transición/cambio de estado cada 15 s mientras suena y en onDestroy.
     */
    private fun saveSession(player: Player) {
        val count = player.mediaItemCount
        if (count == 0) return
        val ends = player.getMediaItemAt(0).mediaId + "|" + player.getMediaItemAt(count - 1).mediaId
        if (cachedPlaylist == null || cachedCount != count || cachedEnds != ends) {
            val tracks = (0 until count).mapNotNull { index ->
                val item = player.getMediaItemAt(index)
                val uri = item.localConfiguration?.uri?.toString() ?: return@mapNotNull null
                val meta = item.mediaMetadata
                SavedTrack(
                    mediaId = item.mediaId,
                    uri = uri,
                    title = meta.title?.toString().orEmpty(),
                    artist = meta.artist?.toString().orEmpty(),
                    durationMs = C.TIME_UNSET,
                    artUri = meta.artworkUri?.toString(),
                )
            }
            if (tracks.isEmpty()) return
            cachedPlaylist = tracks
            cachedCount = count
            cachedEnds = ends
            // El JSON de toda la cola no puede ir en el hilo que abre la canción siguiente.
            saveScope?.launch(Dispatchers.Default) { store.savePlaylistJson(tracks) }
        }
        val pos = PlaybackPosition(
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            playWhenReady = player.playWhenReady,
        )
        // Coalescer: solo la última posición de cada ráfaga se escribe.
        pendingPosition = pos
        if (positionSaveJob?.isActive != true) {
            positionSaveJob = saveScope?.launch(Dispatchers.Default) {
                delay(250)
                pendingPosition?.let { store.savePosition(it) }
                pendingPosition = null
            }
        }
    }

    /**
     * Guardado síncrono (sin scope, sin debounce): para onDestroy y para estar
     * seguros de que la sesión quedó en disco justo antes de morir el proceso.
     * Escrituras chicas; en el hilo actual está bien.
     */
    private fun saveSessionSync(player: Player) {
        val count = player.mediaItemCount
        if (count == 0) return
        val ends = player.getMediaItemAt(0).mediaId + "|" + player.getMediaItemAt(count - 1).mediaId
        val tracks = if (cachedPlaylist != null && cachedCount == count && cachedEnds == ends) {
            cachedPlaylist
        } else {
            (0 until count).mapNotNull { index ->
                val item = player.getMediaItemAt(index)
                val uri = item.localConfiguration?.uri?.toString() ?: return@mapNotNull null
                val meta = item.mediaMetadata
                SavedTrack(
                    mediaId = item.mediaId,
                    uri = uri,
                    title = meta.title?.toString().orEmpty(),
                    artist = meta.artist?.toString().orEmpty(),
                    durationMs = C.TIME_UNSET,
                    artUri = meta.artworkUri?.toString(),
                )
            }.also {
                cachedPlaylist = it
                cachedCount = count
                cachedEnds = ends
            }
        }
        if (tracks.isNullOrEmpty()) return
        store.savePlaylistJson(tracks)
        store.savePosition(
            PlaybackPosition(
                index = player.currentMediaItemIndex,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                playWhenReady = player.playWhenReady,
            ),
        )
    }

    override fun onDestroy() {
        tone?.release()
        tone = null
        mediaSession?.let { session ->
            // Último guardado SÍNCRONO antes de liberar: acá no puede haber scope que
            // se cancele a mitad de la escritura (antes el save del final podía perderse).
            saveSessionSync(session.player)
            session.player.release()
            session.release()
        }
        mediaSession = null
        saveScope?.cancel()
        super.onDestroy()
    }
}

/**
 * Track guardado -> MediaItem para reanudar (misma cola, misma metadata).
 */
private fun savedToMediaItem(track: SavedTrack): MediaItem =
    MediaItem.Builder()
        .setMediaId(track.mediaId)
        .setUri(track.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setArtworkUri(track.artUri?.let { Uri.parse(it) })
                .build(),
        )
        .build()

/**
 * ¿Este tema sigue existiendo en el teléfono?
 *
 * Solo se comprueban los archivos LOCALES, que se verifican sin red:
 *  - "file://…": el archivo en disco.
 *  - "content://media/…": consulta a MediaStore (local, barata).
 *  - Nube (Drive y compañía): NO se comprueba — verificarlo sería una llamada
 *    de red al arrancar, y la app no debe depender de eso para arrancar.
 */
private fun Context.existsLocally(uri: String): Boolean {
    val parsed = Uri.parse(uri)
    return when (parsed.scheme) {
        "file" -> parsed.path?.let { File(it).exists() } ?: false
        "content" -> if (parsed.authority == "media") {
            runCatching {
                contentResolver.query(
                    parsed,
                    arrayOf(android.provider.MediaStore.Audio.Media._ID),
                    null,
                    null,
                    null,
                )?.use { cursor -> cursor.moveToFirst() } == true
            }.getOrDefault(true)
        } else {
            true // nube: no se verifica
        }
        else -> true
    }
}

/**
 * Limpia el título de una canción guardada: si es una descarga de Resonar, el
 * título guardado puede traer el sufijo interno ("… [youtube_xxxx]") y el
 * formato "Artista - Título". Se separa para que el mini reproductor muestre
 * el nombre bien, sin lo que es bookkeeping interno.
 */
private fun cleanSavedTrack(track: SavedTrack): SavedTrack {
    if (!TrackNames.isOwnDownload(track.title)) return track
    val (artist, title) = TrackNames.splitArtistTitle(TrackNames.stripMarker(track.title))
    return track.copy(
        title = title?.takeIf { it.isNotBlank() }
            ?: TrackNames.stripMarker(track.title).ifBlank { track.title },
        artist = artist?.takeIf { it.isNotBlank() } ?: track.artist,
    )
}
