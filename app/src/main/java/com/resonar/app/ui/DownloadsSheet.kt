package com.resonar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resonar.app.R
import com.resonar.app.data.DownloadJobUi
import com.resonar.app.data.DownloadStatus

/**
 * APARTADO "DESCARGANDO AHORA" (Etapa 3).
 *
 * El usuario lo pidiÃ³ explÃ­cito: "le hace falta un apartado que muestre quÃ©
 * se estÃ¡ descargando ahora mismo". Son dos piezas:
 *
 *  - [DownloadStatusPill]: una pÃ­ldora compacta que vive SIEMPRE visible
 *    (encima del mini reproductor) mientras hay algo en cola o bajando.
 *  - [DownloadsSheet]: la hoja con la cola completa, progreso y cancelar.
 *
 * El estado viene de DownloadHub (compartido con el servicio): se ve la
 * descarga aunque la app se haya cerrado y reabierto mientras baja.
 */

/** PÃ­ldora compacta: quÃ© baja ahora y en quÃ© % estÃ¡. Tocar abre la hoja. */
@Composable
fun DownloadStatusPill(
    jobs: List<DownloadJobUi>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = jobs.filter { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.RUNNING }
    if (active.isEmpty()) return
    val running = active.firstOrNull { it.status == DownloadStatus.RUNNING } ?: active.first()
    val pending = active.count { it.status == DownloadStatus.PENDING }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column(
            Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_download),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    running.track.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (pending > 0) {
                    Text(
                        "+$pending en cola",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            val percent = running.percent
            if (running.status == DownloadStatus.RUNNING && percent != null && percent >= 0) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Indeterminado: el servidor no dio el tamaÃ±o total (o estÃ¡ en cola).
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (running.status == DownloadStatus.PENDING) "En cola" else "Descargandoâ€¦",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Hoja con la cola completa: quÃ© hay pendiente, quÃ© baja y quÃ© terminÃ³. */
@Composable
fun DownloadsSheet(
    jobs: List<DownloadJobUi>,
    onCancel: (String) -> Unit,
    onDismissItem: (String) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // Fondo que cierra al tocar afuera (mismo patrÃ³n que las otras hojas:
        // los diÃ¡logos de Material dentro de capas animadas no reciben el
        // toque en el Motorola).
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                .clickable(onClick = onClose),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Descargas",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_clear), contentDescription = "Cerrar")
                    }
                }
                if (jobs.isEmpty()) {
                    Text(
                        "No hay descargas en curso.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                    jobs.take(10).forEach { job -> DownloadJobRow(job, onCancel, onDismissItem) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DownloadJobRow(
    job: DownloadJobUi,
    onCancel: (String) -> Unit,
    onDismissItem: (String) -> Unit,
) {
    val finished = job.status != DownloadStatus.PENDING && job.status != DownloadStatus.RUNNING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                job.track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                job.track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            val percent = job.percent
            when (job.status) {
                DownloadStatus.RUNNING -> {
                    if (percent != null && percent >= 0) {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        )
                        Text("$percent%", style = MaterialTheme.typography.labelSmall)
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        )
                        Text("Descargandoâ€¦", style = MaterialTheme.typography.labelSmall)
                    }
                }
                DownloadStatus.PENDING -> Text(
                    "En cola",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DownloadStatus.DONE -> Text(
                    "Listo",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                DownloadStatus.ERROR -> Text(
                    "Error: ${job.error ?: "desconocido"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                DownloadStatus.CANCELLED -> Text(
                    "Cancelada (el prÃ³ximo intento retoma donde quedÃ³)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (finished) {
            // La X saca la fila terminada de la lista.
            IconButton(onClick = { onDismissItem(job.id) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    painterResource(R.drawable.ic_clear),
                    contentDescription = "Quitar de la lista",
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            // Cancelar una descarga activa: conserva el .parte para reanudar.
            IconButton(onClick = { onCancel(job.id) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    painterResource(R.drawable.ic_clear),
                    contentDescription = "Cancelar descarga",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

