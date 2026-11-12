package com.resonar.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.resonar.app.R
import com.resonar.app.data.LocalTrack
import com.resonar.app.data.albumArtUri
import com.resonar.app.player.PlaybackUiState

/** Tope de pixeles de carátula. En ahorro se pide menos para no llenar la RAM. */
fun artDecodePx(displayDp: Float, quality: String, dataSaver: Boolean): Int {
    val requested = (displayDp * 2f).toInt().coerceAtLeast(96)
    val cap = when {
        dataSaver || quality == "ahorro" -> 360
        quality == "estandar" -> 640
        else -> 960
    }
    return requested.coerceAtMost(cap)
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun TrackArt(model: Any?, size: Dp, corner: Dp = 14.dp, maxDecodePx: Int = 256) {
    var failed by remember(model) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null && !failed) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(model)
                    .crossfade(false)
                    .size(maxDecodePx)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
            )
        } else {
            Icon(
                painterResource(R.drawable.ic_music),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun FillArt(model: Any?, modifier: Modifier, corner: Dp = 18.dp, maxDecodePx: Int = 640) {
    var failed by remember(model) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null && !failed) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(model)
                    .crossfade(false)
                    .size(maxDecodePx)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
            )
        } else {
            Icon(
                painterResource(R.drawable.ic_music),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun GradientTile(
    colors: List<Color>,
    iconRes: Int,
    modifier: Modifier,
    corner: Dp = 16.dp,
    iconSize: Dp = 28.dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = Color.White,
        )
    }
}

@Composable
fun ArtistAvatar(name: String, size: Dp, corner: Dp = size / 2) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(Color(0xFF6D28D9), Color(0xFFC084FC)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initialsOf(name),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun initialsOf(name: String): String =
    name.trim().split(" ").filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "♪" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackRow(
    track: LocalTrack,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    artPx: Int,
    favorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    /** Deslizar hacia la izquierda pide borrar la canción (con confirmación). */
    onDelete: (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val hasMenu = onFavorite != null || onAddToPlaylist != null || onRemove != null

    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isCurrent) colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackArt(
                // La portada de la fuente en línea (si la hay) tiene prioridad:
                // las descargas de YouTube traen la URL de la imagen y así se ven.
                model = track.artUrl ?: if (track.albumArtId > 0L) albumArtUri(track.albumArtId) else null,
                size = 52.dp,
                corner = 12.dp,
                maxDecodePx = artPx,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isCurrent) colorScheme.primary else colorScheme.onSurface,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${track.artist} · ${track.album}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isCurrent && isPlaying) {
                EqualizerBars(playing = true)
                Spacer(Modifier.width(4.dp))
            } else {
                Text(
                    formatClock(track.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            if (hasMenu) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            painterResource(R.drawable.ic_more),
                            contentDescription = "Más opciones",
                            tint = colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (onFavorite != null) {
                            DropdownMenuItem(
                                text = { Text(if (favorite) "Quitar de Me gusta" else "Me gusta") },
                                onClick = { menu = false; onFavorite() },
                            )
                        }
                        if (onAddToPlaylist != null) {
                            DropdownMenuItem(
                                text = { Text("Agregar a playlist") },
                                onClick = { menu = false; onAddToPlaylist() },
                            )
                        }
                        if (onRemove != null) {
                            DropdownMenuItem(
                                text = { Text("Quitar de esta lista") },
                                onClick = { menu = false; onRemove() },
                            )
                        }
                    }
                }
            }
        }
    }

    if (onDelete == null) {
        row()
        return
    }

    // Deslizar hacia la izquierda revela el tacho. NO desplaza la fila sola:
    // abre la confirmación y la fila vuelve a su lugar. El borrado real lo hace
    // la confirmación, así nunca desaparece algo sin querer.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                false // la fila vuelve; desaparece recién cuando se borra de verdad
            } else {
                true
            }
        },
    )
    // El fondo rojo con el tacho SOLO aparece mientras deslizás (target EndToStart);
    // en reposo el fondo es invisible y la fila se ve normal.
    val revealed by animateFloatAsState(
        targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) 1f else 0f,
        animationSpec = tween(150),
        label = "fondoBorrado",
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colorScheme.errorContainer.copy(alpha = revealed)),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_trash),
                    contentDescription = "Eliminar",
                    modifier = Modifier.padding(end = 24.dp),
                    tint = colorScheme.onErrorContainer.copy(alpha = revealed),
                )
            }
        },
    ) {
        row()
    }
}

/**
 * Barra persistente. Toque en el nombre: abre el reproductor.
 * Play y siguiente son botones aparte, para que un toque no cambie de canción.
 */
@Composable
fun MiniPlayer(
    playback: PlaybackUiState,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val artPx = artDecodePx(48f, playback.audioQuality, playback.dataSaver)
    val progress = if (playback.durationMs > 0L) {
        (playback.positionMs.toFloat() / playback.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpen),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackArt(playback.currentArtUri, 46.dp, 12.dp, artPx)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(
                            playback.currentTitle.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            playback.notice ?: playback.currentArtist.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (playback.notice != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onPrevious) {
                    Icon(painterResource(R.drawable.ic_prev), contentDescription = "Anterior")
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(role = Role.Button, onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(if (playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (playback.isPlaying) "Pausar" else "Reproducir",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(painterResource(R.drawable.ic_next), contentDescription = "Siguiente")
                }
            }
        }
    }
}

@Composable
fun PermissionPrompt(onRequest: () -> Unit, onSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GradientTile(
            colors = listOf(Color(0xFF6D28D9), Color(0xFFDB2777)),
            iconRes = R.drawable.ic_music,
            modifier = Modifier.size(88.dp),
            corner = 28.dp,
            iconSize = 40.dp,
        )
        Spacer(Modifier.height(18.dp))
        Text("Resonar necesita tu música", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Con el permiso de audio mostramos canciones, artistas y álbumes del teléfono y la microSD. No se sube nada a internet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onRequest, shape = RoundedCornerShape(50)) { Text("Dar permiso de música") }
        Spacer(Modifier.height(8.dp))
        Text(
            "Ajustes",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onSettings).padding(8.dp),
        )
    }
}

@Composable
fun EmptyLibrary(onRefresh: () -> Unit, onSettings: () -> Unit, onSearchOnline: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No hay música indexada", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "FLAC, MP3 y WAV entran. Si elegiste una carpeta, solo se muestra esa. Google Drive necesita internet: tocá Actualizar. Si los archivos están en el teléfono, elegí la carpeta del almacenamiento, no la de Drive.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        // Salida: con la biblioteca vacía se puede buscar y descargar en línea.
        Button(onClick = onSearchOnline, shape = RoundedCornerShape(50)) { Text("Buscar en línea") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRefresh) { Text("Actualizar biblioteca") }
        Spacer(Modifier.height(8.dp))
        Text(
            "Ajustes",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onSettings).padding(8.dp),
        )
    }
}

@Composable
fun ScanningLibrary() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Escaneando música…", style = MaterialTheme.typography.bodyLarge)
    }
}

fun formatClock(ms: Long): String {
    val safe = ms.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(safe / 60, safe % 60)
}
