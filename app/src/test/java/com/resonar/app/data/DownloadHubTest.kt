package com.resonar.app.data

import com.resonar.app.online.OnlineTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auditoría: la cola de descargas la escriben el hilo principal (usuario) y
 * el worker (progreso) a la vez. El fix de hoy — sets concurrentes y update
 * atómico — tiene que sostener: cancelar/pausar se ve desde el worker,
 * no se duplican trabajos y los reintentos cuentan bien.
 */
class DownloadHubTest {

    private fun track(id: String = "abc123") = OnlineTrack(
        sourceId = "youtube",
        externalId = id,
        title = "Prueba",
        artist = "Artista",
        durationMs = 180_000L,
        artUrl = null,
    )

    @Test
    fun `no se encola dos veces el mismo tema`() = runTest {
        val hub = DownloadHub()
        assertTrue(hub.enqueue(track(), null))
        assertFalse(hub.enqueue(track(), null))
        assertEquals(1, hub.jobs.first().size)
    }

    @Test
    fun `cancelar pedida se ve al toque`() = runTest {
        val hub = DownloadHub()
        hub.enqueue(track(), null)
        assertFalse(hub.isCancelled(track().mediaId))
        hub.requestCancel(track().mediaId)
        assertTrue(hub.isCancelled(track().mediaId))
        hub.markCancelled(track().mediaId)
        assertEquals(DownloadStatus.CANCELLED, hub.jobs.first().first().status)
    }

    @Test
    fun `pausar un trabajo que no arranco lo marca pausado`() = runTest {
        val hub = DownloadHub()
        hub.enqueue(track(), null)
        hub.requestPause(track().mediaId)
        assertEquals(DownloadStatus.PAUSED, hub.jobs.first().first().status)
        // Reanudar lo vuelve a la cola y borra la pausa pedida.
        assertTrue(hub.resume(track().mediaId))
        assertEquals(DownloadStatus.PENDING, hub.jobs.first().first().status)
        assertFalse(hub.isPaused(track().mediaId))
    }

    @Test
    fun `reintento cuenta intentos y vuelve a la cola`() = runTest {
        val hub = DownloadHub()
        hub.enqueue(track(), null)
        hub.markRunning(track().mediaId)
        hub.retry(track().mediaId)
        val job = hub.jobs.first().first()
        assertEquals(DownloadStatus.PENDING, job.status)
        assertEquals(1, job.attempts)
    }

    @Test
    fun `terminado y limpiar quita los finalizados`() = runTest {
        val hub = DownloadHub()
        hub.enqueue(track(), null)
        hub.markDone(track().mediaId)
        assertEquals(DownloadStatus.DONE, hub.jobs.first().first().status)
        hub.clearFinished()
        assertTrue(hub.jobs.first().isEmpty())
    }
}
