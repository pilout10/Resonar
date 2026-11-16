package com.resonar.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.resonar.app.R
import com.resonar.app.ResonarApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * SERVICIO DE DESCARGAS (Etapa 3): es la pieza que responde a "me salgo y
 * deja de descargarse".
 *
 * ANTES: la descarga corría en una corrutina del ViewModel, que vive con la
 * pantalla. Al salir de la app, el proceso quedaba en segundo plano sin
 * prioridad y Android lo mataba (en un teléfono de 2 GB, seguro) — la
 * descarga moría a la mitad.
 *
 * AHORA: este servicio va en PRIMER PLANO con una notificación de progreso.
 * Mientras la notificación está viva, Android lo trata como algo que el
 * usuario está viendo: no lo mata. Podés navegar, cerrar la app, apagar la
 * pantalla: la descarga sigue.
 *
 * La cola de verdad vive en DownloadHub (memoria del proceso); este servicio
 * solo la consume y muestra el progreso. Por eso no guarda nada propio.
 */
class DownloadService : Service() {

    private val container get() = (application as ResonarApp).container
    private val hub get() = container.downloadHub
    private val downloader get() = container.onlineDownloader

    private var scope: CoroutineScope? = null
    private var worker: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Primer plano YA (Android 14 exige llamarlo a los pocos segundos de
        // un startForegroundService). El tipo dataSync es el correcto para
        // "bajar un archivo": avisa qué clase de trabajo es.
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            idleNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (worker == null || worker?.isActive != true) {
            worker = scope?.launch { processQueue() }
        }
        // NOT_STICKY: si el sistema mata el servicio con la cola vacía, no hace
        // falta re-levantarlo. Si había trabajos pendientes, la pantalla los
        // tiene y el próximo toque de descarga lo vuelve a arrancar.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Baja la cola de a UNA (el descargador tiene Mutex que ya serializa). */
    private suspend fun processQueue() {
        while (true) {
            val job = hub.nextPending() ?: break

            if (hub.isCancelled(job.id)) {
                hub.markCancelled(job.id)
                continue
            }

            hub.markRunning(job.id)
            showProgress(job.track.title, null)

            try {
                downloader.download(
                    job.track,
                    resolve = { container.onlineRepository.resolveStream(it) },
                    onProgress = { percent ->
                        hub.updateProgress(job.id, percent)
                        if (percent >= 0) showProgress(job.track.title, percent)
                    },
                    choice = job.option,
                    shouldCancel = { hub.isCancelled(job.id) },
                )
                hub.markDone(job.id)
                // La descarga es parte de "Tu música": refrescar la biblioteca.
                container.libraryRepository.refresh()
                showFinished(job.track.title, error = null, cancelled = false)
            } catch (e: Exception) {
                if (hub.isCancelled(job.id)) {
                    hub.markCancelled(job.id)
                    showFinished(job.track.title, error = null, cancelled = true)
                } else {
                    hub.markError(job.id, e.message)
                    showFinished(job.track.title, error = e.message ?: "Error", cancelled = false)
                }
            }
        }
        // Cola servida: la notificación final queda un rato y el servicio se va.
        // Si llega otra descarga, startForegroundService lo re-crea.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    // ── Notificaciones ───────────────────────────────────────────────────

    private fun notificationBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainIntent())

    /** Mientras baja: barra de progreso, sin sonido, "pegada". */
    private fun showProgress(title: String, percent: Int?) {
        val builder = notificationBuilder()
            .setContentTitle("Descargando")
            .setContentText(title)
            .setOngoing(true)
            .setProgress(0, 0, percent == null)
        if (percent != null) builder.setProgress(100, percent, false)
        notifySafely(builder.build())
    }

    /** Estado final: ya no es "pegada", se puede deslizar. */
    private fun showFinished(title: String, error: String?, cancelled: Boolean) {
        val text = when {
            error != null -> "No se pudo descargar: $title"
            cancelled -> "Descarga cancelada: $title"
            else -> "Descargado: $title"
        }
        val builder = notificationBuilder()
            .setContentTitle(if (error != null) "Error de descarga" else "Resonar")
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setSilent(true)
        if (error != null) builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        notifySafely(builder.build())
    }

    /** Notificación mínima para ganar el primer plano ANTES de saber qué baja. */
    private fun idleNotification(): Notification = notificationBuilder()
        .setContentTitle("Resonar")
        .setContentText("Preparando descarga…")
        .setOngoing(true)
        .build()

    private fun notifySafely(notification: Notification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, notification)
    }

    private fun mainIntent() = android.app.PendingIntent.getActivity(
        this,
        0,
        Intent(this, com.resonar.app.MainActivity::class.java),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Descargas",
            NotificationManager.IMPORTANCE_LOW, // sin sonido: el progreso no debe molestar
        ).apply { description = "Progreso de las descargas de música" }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "resonar_descargas"
        const val NOTIF_ID = 4201
    }
}
