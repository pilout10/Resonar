package com.resonar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resonar.app.R
import com.resonar.app.online.AudioOption
import com.resonar.app.online.OnlineResult
import com.resonar.app.player.PlaybackUiState
import com.resonar.app.online.OnlineRepository
import com.resonar.app.online.OnlineTrack

/**
 * Panel "EN LÍNEA" del buscador (2A: Audius + Internet Archive; YouTube en 2B).
 * Estados REALES, sin fingir: cargando, vacío, sin conexión y fuente caída.
 * Cada fila muestra badge de fuente y de licencia, y si hay varias versiones
 * del mismo tema, un chip "+N versiones" que abre la hoja para elegir fuente.
 */
@Composable
fun OnlineSearchPanel(viewModel: ResonarViewModel, playback: PlaybackUiState) {
    val state by viewModel.onlineState.collectAsState()
    val ytFilter by viewModel.ytFilter.collectAsState()
    val enabledSources by viewModel.enabledSources.collectAsState()
    val youtubeOn = enabledSources["youtube"] == true
    val artPx = artDecodePx(120f, playback.audioQuality, playback.dataSaver)
    val activeNames = enabledSources.filterValues { it }.keys.map { sourceLabel(it) }

    Column(Modifier.fillMaxSize()) {
        // YouTube (2B): el usuario elige si busca canciones (YT Music, limpio)
        // o videos. Evita que la búsqueda se llene de basura.
        if (youtubeOn) {
            ResonarTabs(
                labels = listOf("Canciones", "Videos"),
                selected = ytFilter,
                onSelect = viewModel::setYtFilter,
            )
        }
        when {
            state.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EqualizerBars(playing = true)
                        Text(
                            "Buscando en ${activeNames.joinToString(" + ")}…",
                            modifier = Modifier.padding(top = 10.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            state.offline -> {
                OnlineHint(
                    R.drawable.ic_clear,
                    "Sin conexión",
                    "La búsqueda en línea necesita internet. Podés seguir escuchando tu biblioteca mientras tanto.",
                )
            }
            state.query.isBlank() -> {
                OnlineHint(
                    R.drawable.ic_search,
                    "Buscá en la nube",
                    "Escribí \"artista - tema\" y Resonar busca a la vez en ${activeNames.joinToString()}.",
                )
            }
            state.results.isEmpty() -> {
                OnlineHint(
                    R.drawable.ic_music,
                    "Nada en las fuentes en línea",
                    if (state.failedSources.isEmpty()) {
                        "Probá con otro nombre, o buscá tu tema en la biblioteca del teléfono."
                    } else {
                        "${state.failedSources.joinToString()} no respondió. Probá de nuevo en un rato."
                    },
                )
            }
            else -> {
                if (state.failedSources.isNotEmpty()) {
                    Text(
                        "${state.failedSources.joinToString()} no respondió — el resto de las fuentes sí.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
                    items(state.results, key = { it.primary.mediaId }) { result ->
                        OnlineRow(result.primary, artPx, viewModel) { viewModel.playOnline(result.primary) }
                        if (result.variants.isNotEmpty()) {
                            VersionsChip(
                                count = result.variants.size,
                                onClick = { viewModel.openVersionSheet(result) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Chip "+N versiones" (fuera de la fila, debajo): abre la hoja para elegir fuente. */
@Composable
private fun VersionsChip(count: Int, onClick: () -> Unit) {
    Text(
        "+$count versiones",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(start = 80.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Fila de resultado en línea: carátula, título, artista, duración, badges y descarga. */
@Composable
private fun OnlineRow(
    track: OnlineTrack,
    artPx: Int,
    viewModel: ResonarViewModel,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArt(model = track.artUrl, size = 52.dp, corner = 12.dp, maxDecodePx = artPx)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OnlineBadge(sourceLabel(track.sourceId), highlighted = true)
                if (track.license != null) {
                    Spacer(Modifier.width(6.dp))
                    OnlineBadge(track.license, highlighted = false)
                }
                // Si la fuente solo puede dar un previo (Deezer: 30 s), se dice.
                if (track.isPreview) {
                    Spacer(Modifier.width(6.dp))
                    OnlineBadge("previo 30 s", highlighted = false)
                }
                if (track.providerLabel != null) {
                    Spacer(Modifier.width(6.dp))
                    OnlineBadge(track.providerLabel, highlighted = false)
                }
            }
        }
        Text(
            // Duración honesta: "—" cuando la fuente no la informa (IA a veces).
            track.durationMs?.let { formatClock(it) } ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        DownloadControl(track, viewModel)
    }
}

/**
 * CONTROL DE DESCARGA (2B): icono de descarga → progreso → ✓. Un tema con ✓
 * suena del disco, incluso sin internet.
 */
@Composable
private fun DownloadControl(track: OnlineTrack, viewModel: ResonarViewModel) {
    val downloads by viewModel.onlineDownloads.collectAsState()
    val downloadedIds by viewModel.downloadedIds.collectAsState()
    val dl = downloads[track.mediaId]
    when {
        track.mediaId in downloadedIds -> {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = "Descargado",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        dl != null && dl.error == null -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                if (dl.percent != null && dl.percent >= 0) {
                    Text(
                        "${dl.percent}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        else -> {
            androidx.compose.material3.IconButton(
                onClick = { viewModel.downloadOnline(track) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_download),
                    contentDescription = "Descargar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun OnlineBadge(text: String, highlighted: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (highlighted) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun OnlineHint(icon: Int, title: String, subtitle: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun sourceLabel(sourceId: String): String = when (sourceId) {
    "audius" -> "Audius"
    "archive" -> "Internet Archive"
    "youtube" -> "YouTube"
    "deezer" -> "Deezer"
    "ccmixter" -> "ccMixter"
    "openverse" -> "Openverse"
    "jamendo" -> "Jamendo"
    "url" -> "Enlace"
    else -> sourceId
}

/**
 * HOJA DE VERSIONES: el mismo tema aparece en varias fuentes. Elegir acá
 * de cuál sonar. Se dibuja como overlay a nivel AppShell (patrón probado:
 * los diálogos de Material dentro de capas animadas no reciben el toque
 * en el Motorola).
 */
@Composable
fun OnlineVersionSheet(
    viewModel: ResonarViewModel,
    result: OnlineResult,
    onPlay: (OnlineTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        ScrimOverlay(onDismiss)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(result.primary.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "El mismo tema en ${result.variants.size + 1} fuentes. Elegí cuál sonar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                (listOf(result.primary) + result.variants).forEach { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onPlay(track) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_play),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(sourceLabel(track.sourceId), fontWeight = FontWeight.SemiBold)
                                if (track.license != null) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        track.license,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            // Título de la variante: si hay varias de la misma fuente
                            // (ej. tres audios de Internet Archive) se distinguen.
                            if (track.title != result.primary.title || track.sourceId != result.primary.sourceId) {
                                Text(
                                    track.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            track.durationMs?.let { formatClock(it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        DownloadControl(track, viewModel)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * HOJA DE CALIDADES: qué calidades ofrece REALMENTE la fuente de ese tema
 * (formato, bitrate y tamaño aproximado cuando la fuente los dice). Solo se
 * listan las que existen de verdad: nada de inventar un "MP3 320" que la
 * fuente no tiene.
 *
 * Se dibuja como overlay a nivel AppShell (mismo patrón que la hoja de
 * versiones: los diálogos de Material no reciben el toque en el Motorola).
 */
@Composable
fun OnlineQualitySheet(
    choice: ResonarViewModel.QualityChoice,
    onPick: (AudioOption) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        ScrimOverlay(onDismiss)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    choice.track.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Elegí la calidad. Solo se muestran las que ofrece ${sourceLabel(choice.track.sourceId)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                choice.options.take(MAX_QUALITIES).forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onPick(option) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_download),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(option.label, fontWeight = FontWeight.SemiBold)
                            val detail = buildString {
                                if (!option.bitrate.isNullOrBlank()) append(option.bitrate)
                                if (option.approxBytes != null && option.approxBytes > 0) {
                                    if (isNotEmpty()) append(" · ")
                                    append(formatSize(option.approxBytes))
                                }
                            }
                            if (detail.isNotBlank()) {
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (choice.options.size > MAX_QUALITIES) {
                    Text(
                        "… y ${choice.options.size - MAX_QUALITIES} más",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Máximo de calidades que se listan (la lista no debe ser un muro). */
private const val MAX_QUALITIES = 6

/** Tamaño en bytes → texto corto ("3,2 MB"). */
private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1) String.format(java.util.Locale.US, "%.1f MB", mb)
    else String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
}

/** Fondo que cierra al tocar afuera (igual que LanguageNote). */
@Composable
private fun ScrimOverlay(onDismiss: () -> Unit) {
    Box(
        Modifier            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    )
}
