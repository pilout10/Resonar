package com.resonar.app.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.resonar.app.ResonarApp
import com.resonar.app.data.LibraryState
import com.resonar.app.data.LibrarySyncer
import com.resonar.app.data.LocalLibraryRepository
import com.resonar.app.data.LocalTrack
import com.resonar.app.data.MusicCollections
import com.resonar.app.data.UserLibrarySnapshot
import com.resonar.app.data.UserLibraryStore
import com.resonar.app.data.albumArtUri
import com.resonar.app.online.FederatedResults
import com.resonar.app.online.OnlineRepository
import com.resonar.app.online.OnlineResult
import com.resonar.app.online.OnlineTrack
import com.resonar.app.online.StreamHandle
import com.resonar.app.online.YoutubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MainTab { Home, Search, Library, Playlists }

/** Detalle que se abre encima de las pestañas: artista, álbum, género o playlist. */
sealed interface LibraryDetail {
    data class Artist(val id: Long) : LibraryDetail
    data class Album(val id: Long) : LibraryDetail
    data class Genre(val name: String) : LibraryDetail
    data class Collection(val key: String) : LibraryDetail
}

/**
 * Estado de toda la app: biblioteca, reproducción, pestañas y detalles.
 * La navegación es StateFlow a propósito: son pocas pantallas y no hace falta
 * una librería de navigation.
 */
class ResonarViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as ResonarApp).container
    private val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val library: LocalLibraryRepository = container.libraryRepository
    val playback = container.playerConnection
    val userLibrary: UserLibraryStore = container.userLibrary

    val libraryState: StateFlow<LibraryState> = library.state
    val playbackState = playback.state
    val userState: StateFlow<UserLibrarySnapshot> = userLibrary.state

    /** Progreso de la descarga automática de la biblioteca de nube (tarjeta en Ajustes). */
    val syncProgress: StateFlow<LibrarySyncer.SyncProgress> = container.librarySyncer.progress

    private val _tab = MutableStateFlow(MainTab.Home)
    val tab: StateFlow<MainTab> = _tab.asStateFlow()

    private val _librarySection = MutableStateFlow(0)
    val librarySection: StateFlow<Int> = _librarySection.asStateFlow()

    private val _showPlayer = MutableStateFlow(false)
    val showPlayer: StateFlow<Boolean> = _showPlayer.asStateFlow()

    private val _showQueue = MutableStateFlow(false)
    val showQueue: StateFlow<Boolean> = _showQueue.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    private val _showEqualizer = MutableStateFlow(false)
    val showEqualizer: StateFlow<Boolean> = _showEqualizer.asStateFlow()

    private val _showLanguageNote = MutableStateFlow(false)
    val showLanguageNote: StateFlow<Boolean> = _showLanguageNote.asStateFlow()

    private val _detail = MutableStateFlow<LibraryDetail?>(null)
    val detail: StateFlow<LibraryDetail?> = _detail.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _playlistPickerTrackId = MutableStateFlow<Long?>(null)
    val playlistPickerTrackId: StateFlow<Long?> = _playlistPickerTrackId.asStateFlow()

    /** Tema esperando la confirmación de borrado (deslizar → cartel "¿Eliminar?"). */
    private val _pendingDelete = MutableStateFlow<LocalTrack?>(null)
    val pendingDelete: StateFlow<LocalTrack?> = _pendingDelete.asStateFlow()

    /** Pedido de borrado que confirma el PROPIO Android (temas del teléfono, 11+). */
    private val _systemDeleteRequest = MutableStateFlow<IntentSenderRequest?>(null)
    val systemDeleteRequest: StateFlow<IntentSenderRequest?> = _systemDeleteRequest.asStateFlow()

    /** El tema en tránsito para el borrado vía sistema (se limpia cuando confirma). */
    private var systemDeleteTarget: LocalTrack? = null
    /** Id de la descarga online del tema que se está borrando (si es una). */
    private var systemDeleteOnlineId: String? = null

    // ── Búsqueda federada en línea (2A: Audius + Internet Archive) ───────

    private val online = container.onlineRepository

    /** Estado completo de la búsqueda en línea: resultados, cargando, fallos por fuente. */
    data class OnlineSearchState(
        val query: String = "",
        val loading: Boolean = false,
        val results: List<OnlineResult> = emptyList(),
        val failedSources: List<String> = emptyList(),
        val offline: Boolean = false,
    )

    private val _onlineState = MutableStateFlow(OnlineSearchState())
    val onlineState: StateFlow<OnlineSearchState> = _onlineState.asStateFlow()

    /** 0 = Tu música (biblioteca local), 1 = En línea (fuentes federadas). */
    private val _searchMode = MutableStateFlow(0)
    val searchMode: StateFlow<Int> = _searchMode.asStateFlow()

    private var onlineSearchJob: Job? = null
    private var onlineDebounceJob: Job? = null

    fun setSearchMode(mode: Int) {
        _searchMode.value = mode
        if (mode == 1) runOnlineSearch(query.value) // entrar con lo que ya está tipeado
    }

    /** Debounce de 400 ms al tipear y CANCELACIÓN de la búsqueda anterior. */
    fun scheduleOnlineSearch() {
        onlineDebounceJob?.cancel()
        onlineDebounceJob = vmScope.launch {
            kotlinx.coroutines.delay(400)
            runOnlineSearch(query.value)
        }
    }

    private fun runOnlineSearch(rawQuery: String) {
        onlineSearchJob?.cancel()
        if (rawQuery.isBlank()) {
            _onlineState.value = OnlineSearchState()
            return
        }
        // Pegado de URL: si es un enlace de una fuente conocida, un solo resultado.
        if (rawQuery.trim().startsWith("http")) {
            onlineSearchJob = vmScope.launch {
                _onlineState.value = OnlineSearchState(query = rawQuery, loading = true)
                val fromUrl = runCatching { online.resolveUrl(rawQuery.trim()) }.getOrNull()
                _onlineState.value = if (fromUrl != null) {
                    OnlineSearchState(query = rawQuery, results = listOf(OnlineResult(fromUrl)))
                } else {
                    OnlineSearchState(query = rawQuery, failedSources = listOf("Enlace no reconocido"))
                }
            }
            return
        }
        onlineSearchJob = vmScope.launch {
            _onlineState.value = OnlineSearchState(query = rawQuery, loading = true)
            if (!online.isOnline()) {
                // Estado real: sin conexión. No simulamos lista vacía.
                _onlineState.value = OnlineSearchState(query = rawQuery, offline = true)
                return@launch
            }
            val outcome: FederatedResults = runCatching { online.federatedSearch(rawQuery) }
                .getOrDefault(FederatedResults(emptyList(), emptyList()))
            _onlineState.value = OnlineSearchState(
                query = rawQuery,
                results = outcome.results,
                failedSources = outcome.failedSources,
                offline = false,
            )
        }
    }

    /** Tocar un resultado: si ya está DESCARGADO suena del disco; si no, se
     * RESUELVE el stream y suena con el MusicService de la Etapa 0. */
    fun playOnline(track: OnlineTrack) {
        vmScope.launch {
            // Copia local primero: descargar sirve para algo (offline, sin esperas).
            val local = container.onlineDownloader.localUriFor(track)
            if (local != null) {
                playback.playQueue(listOf(track.toMediaItem(local)), 0)
                openPlayer()
                return@launch
            }
            val handle: StreamHandle? = try {
                online.resolveStream(track)
            } catch (e: Exception) {
                null
            }
            if (handle == null) {
                Toast.makeText(
                    getApplication(),
                    "No se pudo abrir el stream de ${track.title}",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            playback.playQueue(listOf(track.toMediaItem(handle.uri)), 0)
            openPlayer()
        }
    }

    // ── Descargas online (2B) ─────────────────────────────────────────────

    /** Estado del botón de descarga de cada fila: progreso, listo o error. */
    data class DownloadUi(
        val percent: Int? = null, // null = indeterminado; -1 = sin tamaño total
        val done: Boolean = false,
        val error: String? = null,
    )

    private val _onlineDownloads = MutableStateFlow<Map<String, DownloadUi>>(emptyMap())
    val onlineDownloads: StateFlow<Map<String, DownloadUi>> = _onlineDownloads.asStateFlow()

    private val _downloadedIds = MutableStateFlow(container.onlineDownloader.downloadedIds())
    val downloadedIds: StateFlow<Set<String>> = _downloadedIds.asStateFlow()

    /**
     * Descarga un resultado al teléfono. Una a la vez (el descargador tiene
     * Mutex). Con datos móviles AVISA y sigue: es una decisión explícita del
     * usuario, no algo que la app decida en silencio.
     */
    fun downloadOnline(track: OnlineTrack) {
        val id = track.mediaId
        if (id in _downloadedIds.value) {
            Toast.makeText(getApplication(), "Ya está en el teléfono", Toast.LENGTH_SHORT).show()
            return
        }
        if (_onlineDownloads.value[id]?.let { !it.done && it.error == null } == true) return // ya bajando
        val downloader = container.onlineDownloader
        val spaceBlocker = downloader.spaceBlocker()
        if (spaceBlocker != null) {
            Toast.makeText(getApplication(), spaceBlocker, Toast.LENGTH_LONG).show()
            return
        }
        val metered = downloader.isOnMeteredNetwork()
        _onlineDownloads.value = _onlineDownloads.value + (id to DownloadUi(percent = null))
        vmScope.launch {
            try {
                if (metered) {
                    Toast.makeText(getApplication(), "Descargando con datos móviles…", Toast.LENGTH_SHORT).show()
                }
                downloader.download(track, resolve = { online.resolveStream(it) }) { percent ->
                    _onlineDownloads.value = _onlineDownloads.value + (id to DownloadUi(percent = percent))
                }
                // El índice ya tiene la entrada: se recalcula desde ahí (es la verdad).
                _downloadedIds.value = container.onlineDownloader.downloadedIds()
                _onlineDownloads.value = _onlineDownloads.value + (id to DownloadUi(done = true))
                // La descarga es parte de "Tu música": refrescar la biblioteca.
                container.libraryRepository.refresh()
                Toast.makeText(getApplication(), "Descargado: ${track.title}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w("ResonarDown", "Descarga falló: ${e.message}")
                _onlineDownloads.value = _onlineDownloads.value + (id to DownloadUi(error = e.message))
                Toast.makeText(
                    getApplication(),
                    "No se pudo descargar: ${e.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // ── YouTube: pills "Canciones / Videos" (2B) ─────────────────────────

    /** 0 = canciones (YouTube Music), 1 = videos (YouTube). */
    private val _ytFilter = MutableStateFlow(0)
    val ytFilter: StateFlow<Int> = _ytFilter.asStateFlow()

    fun setYtFilter(mode: Int) {
        if (_ytFilter.value == mode) return
        _ytFilter.value = mode
        // El modo es estado de la fuente: el servicio (re-resolve) no lo usa,
        // solo la búsqueda, y resolveStream funciona igual en los dos modos.
        (container.onlineRepository.registry.byId("youtube") as? YoutubeSource)
            ?.mode = mode
        if (searchMode.value == 1) runOnlineSearch(query.value)
    }

    /** Hoja de "+N versiones": elegir de qué fuente suena este mismo tema. */
    private val _versionSheet = MutableStateFlow<OnlineResult?>(null)
    val versionSheet: StateFlow<OnlineResult?> = _versionSheet.asStateFlow()
    fun openVersionSheet(result: OnlineResult) { _versionSheet.value = result }
    fun closeVersionSheet() { _versionSheet.value = null }

    /** MediaItem para un Uri ya resuelto (stream vencido a re-resolver, o archivo local descargado). */
    private fun OnlineTrack.toMediaItem(uri: Uri): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId) // "online:fuente:id" — el servicio sabe re-resolver
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(artUrl?.let { Uri.parse(it) })
                    .build(),
            )
            .build()

    // ── Fuentes: toggles de Ajustes ──────────────────────────────────────

    private val _enabledSources = MutableStateFlow(
        container.onlineRepository.registry.availableSources()
            .associate { it.id to container.onlineRepository.registry.isEnabled(it.id) },
    )
    val enabledSources: StateFlow<Map<String, Boolean>> = _enabledSources.asStateFlow()

    /**
     * Catálogo de fuentes para los interruptores de Ajustes. Solo las que se
     * pueden usar de verdad: una fuente que necesita API key y no la tiene no
     * aparece (y se explica en un texto aparte).
     */
    fun sourceCatalog(): List<com.resonar.app.online.MusicSource> =
        container.onlineRepository.registry.availableSources()

    /** ¿Hay alguna fuente con key que esté esperando configuración? */
    fun pendingKeySources(): List<String> =
        container.onlineRepository.registry.sources
            .filter { it.requiresKey && it !in container.onlineRepository.registry.availableSources() }
            .map { it.displayName }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        container.onlineRepository.registry.setEnabled(id, enabled)
        _enabledSources.value = _enabledSources.value + (id to enabled)
    }

    private val _permissionRequests = MutableStateFlow(0)
    val permissionRequests: StateFlow<Int> = _permissionRequests.asStateFlow()

    private val _folderPickRequests = MutableStateFlow(0)
    val folderPickRequests: StateFlow<Int> = _folderPickRequests.asStateFlow()

    init {
        vmScope.launch {
            playback.state
                .map { it.currentMediaId }
                .distinctUntilChanged()
                .collect { mediaId ->
                    val id = mediaId?.removePrefix("local:")?.toLongOrNull() ?: return@collect
                    userLibrary.recordRecent(id)
                }
        }
    }

    override fun onCleared() {
        vmScope.cancel()
    }

    fun setQuery(value: String) {
        _query.value = value
        // La búsqueda en línea reacciona al mismo texto (con debounce + cancelación).
        scheduleOnlineSearch()
    }

    fun requestPermissions() { _permissionRequests.update { it + 1 } }

    fun openTab(tab: MainTab) {
        _tab.value = tab
        _detail.value = null
        if (tab == MainTab.Playlists) _librarySection.value = 0
    }

    /** La lista de canciones, no la de playlists. Es donde se ve la música del teléfono. */
    fun openSongs() {
        _tab.value = MainTab.Library
        _detail.value = null
        _librarySection.value = 3
    }

    fun setLibrarySection(index: Int) { _librarySection.value = index }

    fun openPlayer() { _showPlayer.value = true }
    fun closePlayer() {
        _showPlayer.value = false
        _showQueue.value = false
    }

    fun openQueue() { _showQueue.value = true }
    fun closeQueue() { _showQueue.value = false }

    fun openSettings() { _showSettings.value = true }
    fun closeSettings() {
        _showSettings.value = false
        _showLanguageNote.value = false
    }

    fun openLanguageNote() { _showLanguageNote.value = true }
    fun closeLanguageNote() { _showLanguageNote.value = false }

    fun openEqualizer() { _showEqualizer.value = true }
    fun closeEqualizer() { _showEqualizer.value = false }

    fun openArtist(id: Long) { _detail.value = LibraryDetail.Artist(id) }
    fun openAlbum(id: Long) { _detail.value = LibraryDetail.Album(id) }
    fun openGenre(name: String) { _detail.value = LibraryDetail.Genre(name) }
    fun openCollection(key: String) { _detail.value = LibraryDetail.Collection(key) }
    fun closeDetail() { _detail.value = null }

    fun openPlaylistPicker(trackId: Long) { _playlistPickerTrackId.value = trackId }
    fun closePlaylistPicker() { _playlistPickerTrackId.value = null }

    // ── Reproducción ─────────────────────────────────────────────────────

    fun playTracks(tracks: List<LocalTrack>, startIndex: Int) {
        if (tracks.isEmpty()) return
        playback.playQueue(tracks.map { it.toMediaItem() }, startIndex)
    }

    fun playAll(tracks: List<LocalTrack>) = playTracks(tracks, 0)

    fun shufflePlay(tracks: List<LocalTrack>) {
        if (tracks.isEmpty()) return
        playback.playQueueShuffled(tracks.map { it.toMediaItem() })
    }

    fun playCollection(key: String, shuffle: Boolean = false) {
        val tracks = resolve(LibraryDetail.Collection(key), library.state.value, userLibrary.state.value)
        if (shuffle) shufflePlay(tracks) else playAll(tracks)
    }

    fun playDetail(shuffle: Boolean = false) {
        val tracks = tracksFor(_detail.value ?: return)
        if (shuffle) shufflePlay(tracks) else playAll(tracks)
    }

    fun playDetailAt(index: Int) {
        val tracks = tracksFor(_detail.value ?: return)
        playTracks(tracks, index)
    }

    fun tracksFor(detail: LibraryDetail): List<LocalTrack> = resolve(detail, library.state.value, userLibrary.state.value)

    fun togglePlayback() = playback.togglePlayback()
    fun next() = playback.next()
    fun previous() = playback.previous()
    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)
    fun toggleShuffle() = playback.toggleShuffle()
    fun cycleRepeatMode() = playback.cycleRepeatMode()
    fun playAt(index: Int) = playback.playAt(index)
    fun removeQueueItem(index: Int) = playback.removeQueueItem(index)
    fun removePlayedFromQueue() = playback.removePlayedFromQueue()

    // ── Biblioteca personal ──────────────────────────────────────────────

    fun toggleFavorite(trackId: Long) = userLibrary.toggleFavorite(trackId)

    fun toggleFavoriteCurrent() {
        val id = playback.state.value.currentMediaId?.removePrefix("local:")?.toLongOrNull() ?: return
        userLibrary.toggleFavorite(id)
    }

    fun createPlaylist(name: String): String? = userLibrary.createPlaylist(name)

    fun deletePlaylist(id: String) {
        userLibrary.deletePlaylist(id)
        val open = _detail.value
        if (open is LibraryDetail.Collection && open.key == id) _detail.value = null
    }

    fun addToPlaylist(playlistId: String, trackId: Long) = userLibrary.addToPlaylist(playlistId, trackId)

    fun removeFromOpenCollection(trackId: Long) {
        val detail = _detail.value as? LibraryDetail.Collection ?: return
        when (detail.key) {
            MusicCollections.FAVORITES -> userLibrary.toggleFavorite(trackId)
            MusicCollections.RECENT -> userLibrary.forgetRecent(trackId)
            MusicCollections.DAILY, MusicCollections.TRENDING -> Unit
            else -> userLibrary.removeFromPlaylist(detail.key, trackId)
        }
    }

    fun collectionTitle(key: String): String = when (key) {
        MusicCollections.FAVORITES -> "Me gusta"
        MusicCollections.RECENT -> "Escuchado recientemente"
        MusicCollections.DAILY -> "Mix del día"
        MusicCollections.TRENDING -> "Tendencias"
        else -> userLibrary.state.value.playlists.firstOrNull { it.id == key }?.name ?: "Playlist"
    }

    // ── Ajustes ──────────────────────────────────────────────────────────

    fun setKeepPlaying(enabled: Boolean) = playback.setKeepPlaying(enabled)
    fun setThemeMode(mode: String) = playback.setThemeMode(mode)
    fun setAudioQuality(value: String) = playback.setAudioQuality(value)
    fun setAutoDownload(enabled: Boolean) {
        playback.setAutoDownload(enabled)
        val syncer = container.librarySyncer
        if (enabled) syncer.refreshCountsAndNudge() else syncer.pause()
    }
    fun setStreamCache(enabled: Boolean) = playback.setStreamCache(enabled)

    /** Descarga manual: baja todo lo que falte aunque no haya WiFi. */
    fun startSyncNow() = container.librarySyncer.startNow()
    fun pauseSync() = container.librarySyncer.pause()
    fun resumeSync() = container.librarySyncer.resume()

    // ── Borrado limpio de canciones ──────────────────────────────────────

    /**
     * Deslizar una fila: según el origen del archivo se pide confirmación acá
     * (Drive, con nuestra tarjeta) o la pide el sistema (MediaStore en 11+).
     */
    fun requestDeleteTrack(track: LocalTrack) {
        if (track.id > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val pi = MediaStore.createDeleteRequest(
                    getApplication<Application>().contentResolver,
                    listOf(Uri.parse(track.uri)),
                )
                systemDeleteTarget = track
                // El id de la descarga online se pierde al borrar: lo capturo ANTES.
                systemDeleteOnlineId = container.onlineDownloader.entryByFileName(track.fileName)?.mediaId
                _systemDeleteRequest.value = IntentSenderRequest.Builder(pi.intentSender).build()
            }.onFailure {
                Toast.makeText(getApplication(), "No se pudo iniciar el borrado", Toast.LENGTH_LONG).show()
            }
        } else {
            _pendingDelete.value = track
        }
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    /** El usuario dijo SÍ: borrar el archivo original y recién entonces limpiar. */
    fun confirmDelete() {
        val track = _pendingDelete.value ?: return
        _pendingDelete.value = null
        // El id de la descarga online se pierde al borrar: lo capturo ANTES.
        // Válido para descargas de la app (privada) y de la carpeta elegida (Drive).
        val onlineDownloadId = container.onlineDownloader.entryByFileName(track.fileName)?.mediaId
        vmScope.launch {
            val deleted = container.trackDeleter.deleteSource(track)
            if (!deleted) {
                Toast.makeText(
                    getApplication(),
                    if (track.fromDownloads) {
                        "No se pudo borrar la descarga"
                    } else {
                        "No se pudo borrar: revisá la conexión a Drive"
                    },
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            if (onlineDownloadId != null) {
                _downloadedIds.value = _downloadedIds.value - onlineDownloadId
                _onlineDownloads.value = _onlineDownloads.value - onlineDownloadId
            }
            finishDelete(track, onlineDownloadId)
        }
    }

    /** El cartel del sistema se cerró: si borró, limpiamos; si no, no tocamos nada. */
    fun onSystemDeleteResult(deleted: Boolean) {
        _systemDeleteRequest.value = null
        val track = systemDeleteTarget
        val onlineId = systemDeleteOnlineId
        systemDeleteTarget = null
        systemDeleteOnlineId = null
        if (!deleted) return
        if (onlineId != null) {
            _downloadedIds.value = _downloadedIds.value - onlineId
            _onlineDownloads.value = _onlineDownloads.value - onlineId
        }
        vmScope.launch { finishDelete(track ?: return@launch, onlineId) }
    }

    /** Pasos comunes de limpieza una vez que el archivo original ya no está. */
    private suspend fun finishDelete(track: LocalTrack, onlineMediaId: String? = null) {
        container.trackDeleter.cleanAppSide(track)
        container.playerConnection.removeTrackById(track.id, track.uri, onlineMediaId)
        // El índice de descargas es la verdad: si el archivo ya no está, el tema
        // tiene que volver a ser descargable desde el buscador.
        _downloadedIds.value = container.onlineDownloader.downloadedIds()
        container.trackDeleter.rescan()
    }
    fun setDataSaver(enabled: Boolean) = playback.setDataSaver(enabled)
    fun setEqEnabled(enabled: Boolean) = playback.setEqEnabled(enabled)
    fun setBass(strength: Int) = playback.setBass(strength)
    fun setEqPreset(name: String) = playback.setEqPreset(name)
    fun previewEqBand(index: Int, levelMb: Int) = playback.previewEqBand(index, levelMb)
    fun commitEqBand(index: Int, levelMb: Int) = playback.commitEqBand(index, levelMb)
    fun openBatterySettings() = playback.openBatteryOptimizationSettings()
    fun requestBatteryExemption() = playback.requestDirectBatteryExemption()
    fun refreshLibrary() = library.refresh()
    fun onPermissionResult(granted: Boolean) = library.onPermissionChanged(granted)

    /** Abre el selector de carpetas del sistema. */
    fun chooseMusicFolder() { _folderPickRequests.update { it + 1 } }

    fun onMusicFolderPicked(uri: Uri) = library.setMusicFolder(uri)

    fun clearMusicFolder() = library.clearMusicFolder()

    private fun resolve(detail: LibraryDetail, library: LibraryState, user: UserLibrarySnapshot): List<LocalTrack> {
        val byId = library.tracks.associateBy { it.id }
        return when (detail) {
            is LibraryDetail.Artist -> library.tracks
                .filter { it.artistId == detail.id }
                .sortedWith(compareBy({ it.album.lowercase() }, { it.trackNo }))
            is LibraryDetail.Album -> library.tracks
                .filter { it.albumId == detail.id }
                .sortedBy { it.trackNo }
            is LibraryDetail.Genre -> MusicCollections.tracksForGenre(library.tracks, detail.name)
            is LibraryDetail.Collection -> when (detail.key) {
                MusicCollections.FAVORITES -> user.favoriteIds.mapNotNull { byId[it] }
                MusicCollections.RECENT -> user.recentIds.mapNotNull { byId[it] }
                MusicCollections.DAILY -> MusicCollections.dailyMix(library.tracks, salt = 1)
                MusicCollections.TRENDING -> MusicCollections.dailyMix(library.tracks, salt = 99)
                else -> user.playlists.firstOrNull { it.id == detail.key }?.trackIds?.mapNotNull { byId[it] }.orEmpty()
            }
        }
    }

    private fun LocalTrack.toMediaItem(): MediaItem {
        // La carátula: la URL de la fuente en línea si la hay (descargas de
        // YouTube), si no la de MediaStore (álbum del teléfono).
        val artUri: Uri? = artUrl?.let { Uri.parse(it) }
            ?: if (albumArtId > 0) albumArtUri(albumArtId) else null
        // Si el tema de nube ya fue descargado al teléfono, tocar la copia local:
        // arranca al toque y sin internet. Si no, su URI original (Drive la baja).
        val playUri = container.librarySyncer.localUriFor(this) ?: Uri.parse(uri)
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
}
