package com.resonar.app.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.StatFs
import android.util.Log
import com.resonar.app.player.isLocalMediaUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * DESCARGADOR AUTOMÁTICO de la biblioteca de nube (Google Drive) al teléfono.
 *
 * La idea es la misma del "Descargar todo" de Spotify/YouTube Music: los temas de
 * Drive se copian UNA vez a una carpeta de la app, y de ahí en más suenan directo
 * del disco (sin internet, sin esperas de 30 s).
 *
 * Reglas de sentido común (para el Motorola E15 y sus datos):
 *  - Automático SOLO con WiFi sin límite de datos; "Descargar ahora" acepta cualquier red.
 *  - Se salta lo que ya está descargado (no duplica).
 *  - Se frena si: batería < 20% sin cargador, o espacio libre < 500 MB.
 *  - Un solo archivo a la vez (secuencial): no satura la red ni la RAM.
 *  - Repite el chequeo cada minuto mientras esté frenado: si volvió el WiFi,
 *    retoma solo.
 */
class LibrarySyncer(
    private val app: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val library: LocalLibraryRepository,
) {

    /** Estado visible para la UI (tarjeta en Ajustes). */
    data class SyncProgress(
        val total: Int = 0,        // temas de nube en la biblioteca
        val done: Int = 0,         // ya descargados
        val failed: Int = 0,       // fallaron esta vuelta (se reintentan)
        val currentTitle: String = "",
        val running: Boolean = false,
        val paused: Boolean = false,
        val reason: String = "",   // por qué está frenado ("Sin WiFi", "Pausado"…)
    )

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    /** Bajadas: carpeta exclusiva de la app; se borra si se desinstala. */
    private val dir: File =
        File(app.getExternalFilesDir(null) ?: app.filesDir, "descargas").apply { mkdirs() }

    private var job: Job? = null

    /** true cuando lo arrancó el usuario a mano: ignora WiFi/batería una vez. */
    private var bypassGuards = false

    /** Pausa pedida por el usuario (vence al terminar o al reanudar). */
    private var userPaused = false

    /** Para respetar el switch "Descarga automática" de Ajustes en cada chequeo. */
    private val settings = com.resonar.app.player.PlaybackStateStore(app)

    init {
        // Cambia la biblioteca (scan, permiso, carpeta elegida) → recalcular pendientes.
        scope.launch {
            library.state
                .map { it.tracks.size to it.musicFolderLabel }
                .distinctUntilChanged()
                .collect { refreshCountsAndNudge() }
        }
        refreshCountsAndNudge()
    }

    // ── API para la UI ─────────────────────────────────────────────────────

    /** "Descargar todo ahora": baja con cualquier red (datos móviles incluidos). */
    fun startNow() {
        userPaused = false
        bypassGuards = true
        launchIfNeeded()
    }

    fun pause() {
        userPaused = true
        _progress.update { it.copy(running = false, paused = true, reason = "Pausado") }
    }

    fun resume() {
        userPaused = false
        _progress.update { it.copy(paused = false, reason = "") }
        launchIfNeeded()
    }

    /** Recalcula pendientes y, si el automático puede avanzar, arranca. */
    fun refreshCountsAndNudge() {
        val cloud = cloudTracks()
        val done = cloud.count { isDownloaded(it) }
        _progress.update {
            it.copy(total = cloud.size, done = if (it.running) it.done else done)
        }
        if (job?.isActive != true) {
            // Sin trabajo corriendo: si falta algo y las condiciones dan, arrancar.
            val pending = cloud.any { !isDownloaded(it) }
            if (pending && !userPaused) launchIfNeeded()
            if (!pending) _progress.update { it.copy(running = false, paused = false, reason = "") }
        }
    }

    /**
     * Copia local de un tema YA descargado (para reproducir directo del disco).
     * null = todavía no está en el teléfono (que se use la URI de nube original).
     */
    fun localUriFor(track: LocalTrack): Uri? {
        val f = fileFor(track)
        return if (f.exists() && f.length() > 1024) Uri.fromFile(f) else null
    }

    /** Borrado limpio: si el tema tenía copia descargada al teléfono, fuera. */
    fun deleteDownloadedCopy(track: LocalTrack): Boolean {
        val f = fileFor(track)
        return if (f.exists()) f.delete() else true
    }

    // ── Motor ──────────────────────────────────────────────────────────────

    private fun launchIfNeeded() {
        if (job?.isActive == true) return
        val pending = cloudTracks().any { !isDownloaded(it) }
        if (!pending) return
        job = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        withContext(Dispatchers.IO) {
            val cloud = cloudTracks()
            var pending = cloud.filter { !isDownloaded(it) }
            var done = cloud.size - pending.size
            var failedTotal = 0
            _progress.update {
                it.copy(total = cloud.size, done = done, failed = 0, running = true, paused = false, reason = "")
            }
            Log.i("ResonarSync", "Descarga: ${pending.size} pendientes de ${cloud.size}")

            // Hasta 3 pasadas: si Drive rechaza (límite de pedidos al descargar muy
            // rápido), esperar un rato y reintentar SOLO lo que falló.
            for (attempt in 1..3) {
                val failed = ArrayList<LocalTrack>()
                for (track in pending) {
                    // Esperar condiciones (solo en modo automático; el manual las saltea).
                    while (!bypassGuards && !userPaused) {
                        val blocker = guardBlocker()
                        if (blocker == null) break
                        _progress.update { it.copy(running = false, paused = true, reason = blocker) }
                        delay(60_000)
                    }
                    if (userPaused) return@withContext
                    _progress.update { it.copy(running = true, paused = false, reason = "", currentTitle = track.title) }

                    if (downloadOne(track)) {
                        done++
                        Log.i("ResonarSync", "OK: ${track.title}")
                    } else {
                        Log.w("ResonarSync", "FALLÓ: ${track.title}")
                        failed.add(track)
                    }
                    _progress.update { it.copy(done = done, failed = failedTotal + failed.size, currentTitle = "") }
                    // Respiro entre archivos: Drive molesta si le pegan sin pausa.
                    delay(1_500)
                }
                if (failed.isEmpty()) break
                failedTotal += failed.size
                pending = failed
                if (attempt < 3) {
                    Log.i("ResonarSync", "Reintentando ${failed.size} en 30 s (pasada ${attempt + 1})")
                    delay(30_000)
                }
            }

            _progress.update { it.copy(running = false, paused = false, reason = "", currentTitle = "") }
        }
    }

    /**
     * Devuelve POR QUÉ no se puede bajar ahora (o null si todo bien).
     * Solo se consulta en modo automático; "Descargar ahora" va aunque falle.
     */
    private fun guardBlocker(): String? {
        if (!settings.isAutoDownload()) return "Descarga automática apagada"
        if (!isWifiUnmetered()) return "Sin WiFi"
        if (batteryTooLow()) return "Batería baja"
        val stat = StatFs(dir.absolutePath)
        if (stat.availableBytes < 500L * 1024 * 1024) return "Poco espacio (500 MB libres)"
        return null
    }

    /** WiFi activo y sin límite de datos (la consulta en el device es instantánea). */
    private fun isWifiUnmetered(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Batería < 20% y sin cargador → frenar (con cargador se puede igual). */
    private fun batteryTooLow(): Boolean {
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return false
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level < 0) return false
        if (level >= 20) return false
        val sticky = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return !charging
    }

    // ── Temas y archivos ───────────────────────────────────────────────────

    /** Temas de nube de la biblioteca (Drive y similares; MediaStore no entra). */
    private fun cloudTracks(): List<LocalTrack> =
        library.state.value.tracks.filter { !isLocalMediaUri(Uri.parse(it.uri)) }

    /** Archivo destino: id + nombre saneado, sin riesgo de colisión ni de caracteres raros. */
    private fun fileFor(track: LocalTrack): File {
        val safe = track.fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return File(dir, "${track.id}_$safe")
    }

    private fun isDownloaded(track: LocalTrack): Boolean {
        val f = fileFor(track)
        return f.exists() && f.length() > 1024
    }

    /**
     * Baja UN tema: nube → archivo temporal → renombrar. El renombrado final
     * garantiza que nunca quede a la mitad un archivo "bueno".
     */
    private fun downloadOne(track: LocalTrack): Boolean {
        val file = fileFor(track)
        if (isDownloaded(track)) return true
        val temp = File(dir, file.name + ".parte")
        try {
            app.contentResolver.openInputStream(Uri.parse(track.uri))?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            } ?: return false
            if (temp.length() < 1024) return false // Drive devolvió vacío o un error HTML
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            return true
        } catch (e: Exception) {
            Log.w("ResonarSync", "Error bajando ${track.title}: ${e.message}")
            return false
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
