package com.resonar.app.player

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Una fila de la cola, lista para dibujar sin volver a preguntarle al player. */
data class QueueItemUi(
    val index: Int,
    val mediaId: String,
    val title: String,
    val artist: String,
    val artUri: String?,
)

/** Una banda del ecualizador del sistema, en milibelios. */
@kotlinx.serialization.Serializable
data class EqBandUi(
    val index: Int,
    val label: String,
    val levelMb: Int,
    val minMb: Int,
    val maxMb: Int,
)

/** Estado de reproducción observado desde la UI (una sola fuente de verdad). */
data class PlaybackUiState(
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val currentTitle: String? = null,
    val currentArtist: String? = null,
    val currentArtUri: String? = null,
    /** mediaId del item actual ("local:123"): sirve para resaltar la fila que suena. */
    val currentMediaId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queueSize: Int = 0,
    val queueIndex: Int = -1,
    val ramMb: Int = -1,
    val keepPlaying: Boolean = true,
    val batteryExempt: Boolean = false,
    val themeMode: String = "dark",
    val notice: String? = null,
    val queue: List<QueueItemUi> = emptyList(),
    val audioQuality: String = "alta",
    val autoDownload: Boolean = true,
    val streamCache: Boolean = true,
    val dataSaver: Boolean = false,
    val wifiOnlyDownloads: Boolean = false,
    val askDownloadQuality: Boolean = true,
    val eqAvailable: Boolean = false,
    val eqEnabled: Boolean = false,
    val eqPresetName: String = "Apagado",
    val eqPresets: List<String> = emptyList(),
    val eqBands: List<EqBandUi> = emptyList(),
    val bass: Int = 0,
)

/**
 * ÚNICO puente de la app con el MusicSessionService.
 * Vive a nivel de Application (AppContainer), así:
 *  - la librería, el mini reproductor y la pantalla completa leen SIEMPRE el mismo estado,
 *  - conectarse/desconectarse entre pantallas no toca el MediaController,
 *  - hay UN solo sondeo de estado (RAM, posición) para toda la UI: cero trabajos duplicados.
 */
class PlayerConnection(context: Context, private val scope: CoroutineScope) {

    private val app: Context = context.applicationContext
    private val store = PlaybackStateStore(app)

    private val eqJson = Json { ignoreUnknownKeys = true }
    private val savedEqSnapshot: EqSnapshot? = store.getEqSnapshotJson()?.let { raw ->
        runCatching { eqJson.decodeFromString<EqSnapshot>(raw) }.getOrNull()
    }

    private val _state = MutableStateFlow(
        PlaybackUiState(
            keepPlaying = store.isKeepPlayingEnabled(),
            batteryExempt = isIgnoringBatteryOptimizations(),
            themeMode = store.getThemeMode(),
            audioQuality = store.getAudioQuality(),
            autoDownload = store.isAutoDownload(),
            streamCache = store.isStreamCacheEnabled(),
            dataSaver = store.isDataSaver(),
            wifiOnlyDownloads = store.isWifiOnlyDownloads(),
            askDownloadQuality = store.isAskDownloadQuality(),
            // La foto ya guardada trae las bandas. Si no se lee al abrir,
            // el interruptor queda prendido y las opciones no aparecen
            // hasta apagarlo y prenderlo otra vez.
            eqAvailable = savedEqSnapshot?.available == true || !savedEqSnapshot?.bands.isNullOrEmpty(),
            eqEnabled = store.isEqEnabled() || savedEqSnapshot?.enabled == true,
            eqPresetName = savedEqSnapshot?.presetName ?: store.getEqPresetName(),
            eqPresets = savedEqSnapshot?.presets.orEmpty(),
            eqBands = savedEqSnapshot?.bands.orEmpty(),
            bass = savedEqSnapshot?.bass ?: store.getBassStrength(),
        ),
    )
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var lastEqSnapshot: String? = store.getEqSnapshotJson()

    private var controller: MediaController? = null
    private var connecting = false
    private var lastConnectAttemptMs = 0L

    // Cola pedida por la UI ANTES de que el puente conectara: se suelta apenas conecta.
    private var pendingQueue: PendingQueue? = null
    private data class PendingQueue(val items: List<MediaItem>, val startIndex: Int)

    init {
        connect()
        // ÚNICO loop de sondeo de toda la app (excepto el del servicio): cada 500 ms
        // sincroniza posición/estado y se autocura si el sistema mató el servicio.
        scope.launch {
            var ramTick = 0
            while (isActive) {
                ensureConnection()
                pullEqSnapshot()
                controller?.let { c ->
                    // Aviso honesto: si lo que suena viene de la nube y está BUFFERING,
                    // decimos "bajando desde la nube" en vez de dejar la app muda.
                    val cloudNow = !isLocalMediaUri(c.currentMediaItem?.localConfiguration?.uri)
                    val bufferingNow = c.playbackState == Player.STATE_BUFFERING
                    // Aviso honesto con la fuente REAL: Drive, Audius, Archive o "la nube".
                    val streamHost = c.currentMediaItem?.localConfiguration?.uri?.host.orEmpty()
                    val cloudNotice = when {
                        streamHost.contains("audius") -> "Bajando desde Audius…"
                        streamHost.contains("archive.org") -> "Bajando desde Internet Archive…"
                        streamHost.isBlank() -> "Bajando desde la nube (Drive)… la próxima vez suena al toque"
                        else -> "Bajando desde la nube…"
                    }
                    _state.update {
                        it.copy(
                            isConnected = c.isConnected,
                            isPlaying = c.isPlaying,
                            playWhenReady = c.playWhenReady,
                            positionMs = c.currentPosition.coerceAtLeast(0L),
                            durationMs = if (c.duration > 0) c.duration else it.durationMs,
                            ramMb = if (ramTick % 4 == 0) readProcessRamMb() else it.ramMb,
                            batteryExempt = isIgnoringBatteryOptimizations(),
                            notice = when {
                                bufferingNow && cloudNow -> cloudNotice
                                c.isPlaying -> null
                                else -> it.notice
                            },
                        )
                    }
                }
                ramTick++
                delay(500)
            }
        }
    }

    private fun connect() {
        if (connecting || controller?.isConnected == true) return
        connecting = true
        lastConnectAttemptMs = System.currentTimeMillis()
        val token = SessionToken(app, ComponentName(app, MusicService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener(
            {
                connecting = false
                runCatching {
                    val old = controller
                    val new = future.get()
                    controller = new
                    new.addListener(playerListener)
                    syncFull(new)
                    // Llevamos la cola que la UI pidió antes de estar conectados.
                    pendingQueue?.let { pending ->
                        pendingQueue = null
                        new.setMediaItems(pending.items, pending.startIndex, 0L)
                        new.prepare()
                        new.play()
                    }
                    _state.update { it.copy(notice = null) }
                }.onFailure {
                    _state.update { it.copy(notice = "No se pudo conectar con el servicio de audio") }
                }
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    /** Si el sistema mató el servicio (kernel en un juego), nos reconectamos solos. */
    private fun ensureConnection() {
        if (controller?.isConnected == true || connecting) return
        val now = System.currentTimeMillis()
        if (now - lastConnectAttemptMs < 5_000) return
        connect()
    }

    /** ── Acciones de reproducción que pide la UI ────────────────────────── */

    /**
     * Carga una cola completa (lista de MediaItem) y arranca en el índice dado.
     * Es el formato de las tres pantallas: canciones, artista, álbum.
     */
    fun playQueue(items: List<MediaItem>, startIndex: Int) {
        val c = controller
        // Trazabilidad: qué pidió la UI y con qué tema arranca (clave para diagnosticar).
        Log.i(
            "ResonarPlayer",
            "playQueue items=${items.size} start=$startIndex primero=${items.getOrNull(startIndex)?.mediaId}",
        )
        if (c == null || !c.isConnected) {
            Log.w("ResonarPlayer", "playQueue sin conexión: queda en cola pendiente")
            pendingQueue = PendingQueue(items, startIndex)
            connect()
            return
        }
        _state.update { it.copy(notice = null) }
        c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)), 0L)
        c.prepare()
        c.play()
    }

    /**
     * Pone [item] a sonar sin tirar la cola que ya había.
     * Si el tema ya está en la fila, salta a esa fila. Si no, lo inserta después
     * del que suena. Una cola vacía arranca solo con este tema.
     */
    fun playKeepingQueue(item: MediaItem) {
        val c = controller
        if (c == null || !c.isConnected || c.mediaItemCount == 0) {
            playQueue(listOf(item), 0)
            return
        }
        val id = item.mediaId
        for (index in 0 until c.mediaItemCount) {
            if (c.getMediaItemAt(index).mediaId == id) {
                c.seekTo(index, 0L)
                c.play()
                return
            }
        }
        val index = (c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount)
        c.addMediaItem(index, item)
        c.seekTo(index, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlayback() {
        val c = controller ?: return
        // playWhenReady es el estado fuente-de-verdad (lección de la prueba con el juego).
        if (c.playWhenReady && c.isPlaying) c.pause() else c.play()
    }

    /** Arranca una cola en modo aleatorio (shuffle global activo + índice inicial al azar). */
    fun playQueueShuffled(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val c = controller
        if (c == null || !c.isConnected) {
            pendingQueue = PendingQueue(items, 0)
            connect()
            return
        }
        _state.update { it.copy(notice = null) }
        c.shuffleModeEnabled = true
        val start = kotlin.random.Random.nextInt(items.size)
        c.setMediaItems(items, start, 0L)
        c.prepare()
        c.play()
    }

    fun next() { controller?.seekToNext() }

    fun previous() {
        // Comportamiento estándar de reproductores: al inicio de la canción → anterior;
        // ya avanzada la canción → al 0:00 de la misma.
        val c = controller ?: return
        if (c.currentPosition > 3_000) c.seekTo(0) else c.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        controller?.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    /** Salta a una fila de la cola (pantalla Fila de reproducción). */
    fun playAt(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekTo(index, 0L)
        c.play()
    }

    fun removeQueueItem(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount || c.mediaItemCount <= 1) return
        c.removeMediaItem(index)
        syncFull(c)
    }

    /**
     * Borrado limpio: si el tema eliminado estaba sonando o esperando en la
     * cola, se saca de ahí (y el cambio de cola re-graba la sesión sola).
     *
     * Se busca por id, por URI y por id de descarga online: un tema puede
     * entrar en la cola con el id de MediaStore y borrarse con el id de la
     * carpeta (o al revés), y si no coincidieran el tema borrado seguiría
     * sonando o esperando en la cola.
     */
    fun removeTrackById(trackId: Long, uri: String? = null, onlineMediaId: String? = null) {
        val c = controller ?: return
        val mediaId = "local:$trackId"
        var changed = false
        for (index in c.mediaItemCount - 1 downTo 0) {
            val item = c.getMediaItemAt(index)
            val sameUri = uri != null && item.localConfiguration?.uri?.toString() == uri
            val sameOnline = onlineMediaId != null && item.mediaId == onlineMediaId
            if (item.mediaId == mediaId || sameUri || sameOnline) {
                if (c.mediaItemCount <= 1) {
                    c.clearMediaItems()
                    changed = true
                    break
                }
                c.removeMediaItem(index)
                changed = true
            }
        }
        if (changed) syncFull(c)
    }

    /** Saca de la fila lo que ya sonó, y deja la canción actual en el puesto 0. */
    fun removePlayedFromQueue() {
        val c = controller ?: return
        val current = c.currentMediaItemIndex
        if (current <= 0) return
        for (index in current - 1 downTo 0) c.removeMediaItem(index)
        syncFull(c)
    }

    fun toggleShuffle() {
        controller?.shuffleModeEnabled = controller?.shuffleModeEnabled == false
    }

    /** Ciclo de repetición: no repetir → repetir todo → repetir una. */
    fun cycleRepeatMode() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** ── Ajustes (foco de audio + batería) ───────────────────────────────── */

    fun setKeepPlaying(enabled: Boolean) {
        store.setKeepPlayingEnabled(enabled)
        _state.update { it.copy(keepPlaying = enabled) }
    }

    /** "dark" / "light" / "system". La pantalla principal la aplica vía ResonarTheme. */
    fun setThemeMode(mode: String) {
        store.setThemeMode(mode)
        _state.update { it.copy(themeMode = mode) }
    }

    fun setAudioQuality(value: String) {
        store.setAudioQuality(value)
        _state.update { it.copy(audioQuality = value) }
    }

    fun setAutoDownload(enabled: Boolean) {
        store.setAutoDownload(enabled)
        _state.update { it.copy(autoDownload = enabled) }
    }

    /** Caché de streaming: independiente de copiar la biblioteca entera. */
    fun setStreamCache(enabled: Boolean) {
        store.setStreamCacheEnabled(enabled)
        _state.update { it.copy(streamCache = enabled) }
    }

    fun setDataSaver(enabled: Boolean) {
        store.setDataSaver(enabled)
        _state.update { it.copy(dataSaver = enabled) }
    }

    /** Descargas de búsqueda: bloquearlas con datos móviles si el usuario lo pide. */
    fun setWifiOnlyDownloads(enabled: Boolean) {
        store.setWifiOnlyDownloads(enabled)
        _state.update { it.copy(wifiOnlyDownloads = enabled) }
    }

    /** Preguntar calidad en cada descarga, o tomar la mejor sin preguntar. */
    fun setAskDownloadQuality(enabled: Boolean) {
        store.setAskDownloadQuality(enabled)
        _state.update { it.copy(askDownloadQuality = enabled) }
    }

    /**
     * El efecto corre en el servicio. Acá solo se guarda la preferencia;
     * PlaybackEqualizer la aplica y devuelve las bandas reales del teléfono.
     */
    fun previewEqBand(index: Int, levelMb: Int) {
        val levels = store.getEqLevels().toMutableList()
        while (levels.size <= index) levels.add(0)
        levels[index] = levelMb
        if (store.getEqPresetName() != "Personalizado") store.setEqPresetName("Personalizado")
        if (!store.isEqEnabled()) store.setEqEnabled(true)
        store.setEqLevels(levels)
        _state.update { state ->
            state.copy(
                eqEnabled = true,
                eqPresetName = "Personalizado",
                eqBands = state.eqBands.map { band ->
                    if (band.index == index) band.copy(levelMb = levelMb) else band
                },
            )
        }
    }

    /** Graves extra, 0 a 1000. No cambia el archivo: solo el parlante o los auriculares. */
    fun setBass(strength: Int) {
        val value = strength.coerceIn(0, 1000)
        store.setBassStrength(value)
        _state.update { it.copy(bass = value) }
    }

    fun commitEqBand(index: Int, levelMb: Int) = previewEqBand(index, levelMb)

    fun setEqEnabled(enabled: Boolean) {
        // Apagar no borra la curva: al volver a prender sigue "Más graves" o la que fuera.
        if (enabled && store.getEqPresetName() == "Apagado") store.setEqPresetName("Personalizado")
        store.setEqEnabled(enabled)
        _state.update {
            it.copy(
                eqEnabled = enabled,
                eqPresetName = if (enabled && it.eqPresetName == "Apagado") "Personalizado" else it.eqPresetName,
            )
        }
    }

    fun setEqPreset(name: String) {
        val enabled = name != "Apagado"
        store.setEqEnabled(enabled)
        store.setEqPresetName(name)
        _state.update { it.copy(eqEnabled = enabled, eqPresetName = name) }
    }

    fun openBatteryOptimizationSettings() {
        runCatching {
            app.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun requestDirectBatteryExemption() {
        runCatching {
            app.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** ── internos ───────────────────────────────────────────────────────── */

    private fun syncFull(c: MediaController) {
        val item = c.currentMediaItem
        val meta = item?.mediaMetadata
        _state.update {
            it.copy(
                isConnected = c.isConnected,
                isPlaying = c.isPlaying,
                playWhenReady = c.playWhenReady,
                currentTitle = meta?.title?.toString(),
                currentArtist = meta?.artist?.toString(),
                currentArtUri = meta?.artworkUri?.toString(),
                currentMediaId = item?.mediaId,
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = if (c.duration > 0) c.duration else 0L,
                shuffleEnabled = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
                queueSize = c.mediaItemCount,
                queueIndex = c.currentMediaItemIndex,
                queue = readQueue(c),
            )
        }
    }

    private fun readQueue(c: MediaController): List<QueueItemUi> {
        val count = c.mediaItemCount
        if (count <= 0) return emptyList()
        return List(count) { index ->
            val item = c.getMediaItemAt(index)
            val meta = item.mediaMetadata
            QueueItemUi(
                index = index,
                mediaId = item.mediaId,
                title = meta.title?.toString().orEmpty().ifBlank { "Sin título" },
                artist = meta.artist?.toString().orEmpty(),
                artUri = meta.artworkUri?.toString(),
            )
        }
    }

    private fun pullEqSnapshot() {
        val raw = store.getEqSnapshotJson() ?: return
        if (raw == lastEqSnapshot) return
        lastEqSnapshot = raw
        val snap = runCatching { eqJson.decodeFromString<EqSnapshot>(raw) }.getOrNull() ?: return
        _state.update {
            it.copy(
                eqAvailable = snap.available,
                eqEnabled = snap.enabled,
                eqPresetName = snap.presetName,
                eqPresets = snap.presets,
                eqBands = snap.bands,
                bass = snap.bass,
            )
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = controller ?: return
            val meta = mediaItem?.mediaMetadata
            // Solo la canción que entra. Releer toda la cola en cada salto trababa la pantalla.
            _state.update {
                it.copy(
                    isPlaying = c.isPlaying,
                    playWhenReady = c.playWhenReady,
                    currentTitle = meta?.title?.toString(),
                    currentArtist = meta?.artist?.toString(),
                    currentArtUri = meta?.artworkUri?.toString(),
                    currentMediaId = mediaItem?.mediaId,
                    positionMs = 0L,
                    durationMs = if (c.duration > 0) c.duration else 0L,
                    queueSize = c.mediaItemCount,
                    queueIndex = c.currentMediaItemIndex,
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying, notice = if (isPlaying) null else it.notice) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val title = controller?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
            Log.e(
                "ResonarPlayer",
                "Error en la UI: ${error.errorCodeName} tema=\"$title\"",
                error.cause ?: error,
            )
            val failedToOpen = error.errorCode in setOf(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
            _state.update {
                it.copy(
                    isPlaying = false,
                    notice = buildString {
                        append(if (failedToOpen) "No se pudo abrir" else "No se pudo reproducir")
                        if (title.isNotBlank()) append(" \"$title\"")
                        append(" (")
                        append(error.errorCodeName)
                        append(")")
                    },
                )
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _state.update { it.copy(playWhenReady = playWhenReady) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.update { it.copy(repeatMode = repeatMode) }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            controller?.let { syncFull(it) }
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(app.packageName)
    }

    /** RAM real del proceso en MB (PSS): sirve para medir la etapa completa. */
    private fun readProcessRamMb(): Int {
        return runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
            info?.totalPss?.div(1024) ?: -1
        }.getOrDefault(-1)
    }
}
