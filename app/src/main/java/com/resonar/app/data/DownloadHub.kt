package com.resonar.app.data

import com.resonar.app.online.AudioOption
import com.resonar.app.online.OnlineTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * COLA DE DESCARGAS compartida por TODO el proceso: la pantalla la mira, el
 * servicio la consume. Antes las descargas vivían dentro del ViewModel: al
 * salir de la app, Android mataba el proceso y la descarga moría con él.
 * Ahora el que descarga es un SERVICIO EN PRIMER PLANO (DownloadService) que
 * sigue vivo aunque el usuario esté en otro lado; esta cola es el puente.
 *
 * Es memoria del proceso: si el sistema mata el proceso entero (juego pesado),
 * la cola se pierde — pero con el servicio en primer plano eso es rarísimo,
 * y un .parte a medio bajar queda en el disco para reanudar.
 */
enum class DownloadStatus { PENDING, RUNNING, PAUSED, DONE, ERROR, CANCELLED }

/** Un trabajo de la cola: qué, con qué calidad y cómo viene. */
data class DownloadJobUi(
    val track: OnlineTrack,
    val option: AudioOption? = null,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val percent: Int? = null, // null = indeterminado (el servidor no da el tamaño)
    val error: String? = null,
    val attempts: Int = 0, // reintentos ya usados (el reintento automático lo sube)
) {
    val id: String get() = track.mediaId
}

class DownloadHub {

    private val _jobs = MutableStateFlow<List<DownloadJobUi>>(emptyList())
    val jobs: StateFlow<List<DownloadJobUi>> = _jobs.asStateFlow()

    /** Ids de cancelación pedida. El worker lo consulta en cada trozo bajado. */
    private val cancelRequested = HashSet<String>()

    /** Ids de pausa pedida. Igual que cancelar pero conservando el estado para retomar. */
    private val pauseRequested = HashSet<String>()
    private val mutex = Mutex()

    /**
     * Encola una descarga. false si ese tema ya está en cola o bajando
     * (no duplicar). Un trabajo viejo DONE/ERROR/CANCELLED se reemplaza.
     */
    suspend fun enqueue(track: OnlineTrack, option: AudioOption?): Boolean = mutex.withLock {
        val active = _jobs.value.any {
            it.id == track.mediaId && (it.status == DownloadStatus.PENDING || it.status == DownloadStatus.RUNNING)
        }
        if (active) return false
        cancelRequested.remove(track.mediaId)
        pauseRequested.remove(track.mediaId)
        _jobs.value = _jobs.value.filterNot { it.id == track.mediaId } +
            DownloadJobUi(track = track, option = option)
        true
    }

    /** El siguiente trabajo por bajar, o null si la cola está servida. */
    fun nextPending(): DownloadJobUi? =
        _jobs.value.firstOrNull { it.status == DownloadStatus.PENDING }

    fun markRunning(id: String) = update(id) { it.copy(status = DownloadStatus.RUNNING, percent = null, error = null) }

    fun updateProgress(id: String, percent: Int) =
        update(id) { it.copy(status = DownloadStatus.RUNNING, percent = percent) }

    fun markDone(id: String) = update(id) { it.copy(status = DownloadStatus.DONE, percent = 100, error = null) }

    fun markError(id: String, message: String?) =
        update(id) { it.copy(status = DownloadStatus.ERROR, error = message ?: "Error de descarga") }

    fun markCancelled(id: String) = update(id) { it.copy(status = DownloadStatus.CANCELLED, error = null) }

    fun markPaused(id: String) = update(id) { it.copy(status = DownloadStatus.PAUSED, error = null) }

    /**
     * REINTENTO AUTOMÁTICO (Etapa 3 parte 2): tras un error transitorio
     * (el CDN de YouTube corta conexiones seguido), el trabajo vuelve a
     * PENDING con attempts+1 y el worker lo agarra de nuevo. El `.parte`
     * queda en el disco: la descarga retoma desde donde quedó.
     */
    fun retry(id: String) = update(id) {
        it.copy(
            status = DownloadStatus.PENDING,
            percent = null,
            error = null,
            attempts = it.attempts + 1,
        )
    }

    /** El usuario pidió cancelar. El worker se entera en el próximo trozo. */
    suspend fun requestCancel(id: String) = mutex.withLock {
        pauseRequested.remove(id)
        cancelRequested.add(id)
    }

    fun isCancelled(id: String): Boolean = cancelRequested.contains(id)

    /** El usuario pidió pausar. Se aplica al vuelo si ya está bajando. */
    suspend fun requestPause(id: String) = mutex.withLock {
        cancelRequested.remove(id)
        pauseRequested.add(id)
        // Si todavía no arrancó, se marca PAUSED acá mismo (el worker ni lo agarra).
        val current = _jobs.value.firstOrNull { it.id == id }
        if (current != null && current.status == DownloadStatus.PENDING) {
            update(id) { it.copy(status = DownloadStatus.PAUSED) }
        }
    }

    fun isPaused(id: String): Boolean = pauseRequested.contains(id)

    /** Reanudar una pausada: vuelve a la cola (y el .parte se reutiliza). */
    suspend fun resume(id: String): Boolean = mutex.withLock {
        pauseRequested.remove(id)
        val current = _jobs.value.firstOrNull { it.id == id && it.status == DownloadStatus.PAUSED }
        if (current == null) return false
        update(id) { it.copy(status = DownloadStatus.PENDING, error = null) }
        true
    }

    /** ¿Hay algo en cola o bajando? (para mostrar la píldora y mantener el servicio) */
    fun hasActive(): Boolean = _jobs.value.any {
        it.status == DownloadStatus.PENDING || it.status == DownloadStatus.RUNNING
    }

    /** Quita de la lista los trabajos terminados (Listo/Error/Cancelada). */
    suspend fun clearFinished() = mutex.withLock {
        _jobs.value = _jobs.value.filter {
            it.status == DownloadStatus.PENDING || it.status == DownloadStatus.RUNNING
        }
    }

    /** Saca UN trabajo terminado de la lista (la X de la fila). */
    suspend fun remove(id: String) = mutex.withLock {
        _jobs.value = _jobs.value.filterNot { it.id == id && it.status != DownloadStatus.RUNNING }
    }

    private fun update(id: String, transform: (DownloadJobUi) -> DownloadJobUi) {
        val current = _jobs.value
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) _jobs.value = current.toMutableList().also { it[index] = transform(current[index]) }
    }
}
