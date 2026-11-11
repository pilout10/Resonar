package com.resonar.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.resonar.app.R
import com.resonar.app.player.PlaybackUiState
import kotlin.math.abs
import kotlin.math.min


/**
 * Reproductor grande. El dedo sobre la portada cambia de tema (horizontal)
 * o cierra la pantalla (hacia abajo). La portada cruza en fade cuando cambia la canción.
 */
@Composable
fun PlayerScreen(
    viewModel: ResonarViewModel,
    playback: PlaybackUiState,
    inPane: Boolean,
    onBack: () -> Unit,
) {
    val user by viewModel.userState.collectAsState()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var dragY by remember { mutableFloatStateOf(0f) }
    var artX by remember { mutableFloatStateOf(0f) }
    val knownDuration = playback.durationMs > 0L
    val duration = if (knownDuration) playback.durationMs else 1L
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    var showOutput by remember { mutableStateOf(false) }
    val shownPosition = if (dragging) dragPosition else playback.positionMs.toFloat()
    val sliderValue = if (knownDuration) shownPosition.coerceIn(0f, duration.toFloat()) else 0f
    val trackId = playback.currentMediaId?.removePrefix("local:")?.toLongOrNull()
    val favorite = trackId != null && trackId in user.favoriteIds
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val playBg = if (dark) Color.White else Color(0xFF1A1028)
    val playFg = if (dark) Color(0xFF1A1028) else Color.White

    LaunchedEffect(playback.currentMediaId) { artX = 0f }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = dragY }
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (inPane) {
                Spacer(Modifier.size(48.dp))
            } else {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_down), contentDescription = "Cerrar reproductor")
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::openEqualizer) {
                Icon(painterResource(R.drawable.ic_eq), contentDescription = "Ecualizador")
            }
        }

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val side = min(maxWidth.value, maxHeight.value).dp
            val dismissPx = with(density) { 120.dp.toPx() }
            val skipPx = with(density) { 72.dp.toPx() }
            Box(
                modifier = Modifier
                    .size(side)
                    .graphicsLayer { translationX = artX }
                    .shadow(22.dp, RoundedCornerShape(28.dp), ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary)
                    .pointerInput(inPane) {
                        // Un solo eje por gesto. Si el dedo se reparte entre los dos,
                        // la portada salta y cambia de canción sin querer.
                        var axis = 0
                        detectDragGestures(
                            onDragStart = { axis = 0 },
                            onDrag = { change, drag ->
                                change.consume()
                                if (axis == 0 && (abs(drag.x) > 8f || abs(drag.y) > 8f)) {
                                    axis = if (abs(drag.x) > abs(drag.y)) 1 else 2
                                }
                                when (axis) {
                                    1 -> artX += drag.x
                                    2 -> if (!inPane) dragY = (dragY + drag.y).coerceIn(0f, dismissPx * 1.4f)
                                }
                            },
                            onDragCancel = {
                                artX = 0f
                                dragY = 0f
                            },
                            onDragEnd = {
                                val x = artX
                                val y = dragY
                                artX = 0f
                                dragY = 0f
                                when {
                                    !inPane && axis == 2 && y > dismissPx -> onBack()
                                    axis == 1 && x <= -skipPx -> viewModel.next()
                                    axis == 1 && x >= skipPx -> viewModel.previous()
                                }
                            },
                        )
                    },
            ) {
                AnimatedContent(
                    modifier = Modifier.fillMaxSize(),
                    targetState = playback.currentMediaId to playback.currentArtUri,
                    transitionSpec = {
                        (fadeIn(tween(260)) + scaleIn(initialScale = 0.92f, animationSpec = tween(260)))
                            .togetherWith(fadeOut(tween(160)))
                    },
                    label = "portada",
                ) { (_, art) ->
                    FillArt(
                        model = art,
                        modifier = Modifier.fillMaxSize(),
                        corner = 28.dp,
                        maxDecodePx = if (playback.dataSaver) 480 else 900,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    playback.currentTitle ?: "Sin reproducción",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    playback.currentArtist.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = {
                viewModel.toggleFavoriteCurrent()
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }) {
                Icon(
                    painterResource(if (favorite) R.drawable.ic_heart_fill else R.drawable.ic_heart),
                    contentDescription = if (favorite) "Quitar de Me gusta" else "Me gusta",
                    tint = if (favorite) Color(0xFFE879F9) else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        playback.notice?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Slider(
            value = sliderValue,
            onValueChange = {
                if (!knownDuration) return@Slider
                dragging = true
                dragPosition = it
            },
            onValueChangeFinished = {
                if (knownDuration) viewModel.seekTo(dragPosition.toLong())
                dragging = false
            },
            enabled = knownDuration,
            valueRange = 0f..duration.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = if (dark) Color.White else MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                formatClock(if (knownDuration) shownPosition.toLong() else playback.positionMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (knownDuration) formatClock(playback.durationMs) else "–:–",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = viewModel::toggleShuffle) {
                Icon(
                    painterResource(R.drawable.ic_shuffle),
                    contentDescription = "Aleatorio",
                    tint = if (playback.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = viewModel::previous, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_prev), contentDescription = "Anterior", modifier = Modifier.size(34.dp))
            }
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(playBg)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.togglePlayback()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = if (playback.isPlaying) "Pausar" else "Reproducir",
                    modifier = Modifier.size(36.dp),
                    tint = playFg,
                )
            }
            IconButton(onClick = viewModel::next, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_next), contentDescription = "Siguiente", modifier = Modifier.size(34.dp))
            }
            Box {
                IconButton(onClick = viewModel::cycleRepeatMode) {
                    Icon(
                        painterResource(R.drawable.ic_repeat),
                        contentDescription = "Repetir",
                        tint = if (playback.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (playback.repeatMode == Player.REPEAT_MODE_ONE) {
                    Text(
                        "1",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { showOutput = true }) {
                Icon(painterResource(R.drawable.ic_speaker), contentDescription = "Salida de audio")
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (playback.queueSize > 0) "${playback.queueIndex + 1} / ${playback.queueSize}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::openQueue) {
                Icon(painterResource(R.drawable.ic_queue), contentDescription = "Fila de reproducción")
            }
        }
    }

    if (showOutput) {
        AlertDialog(
            onDismissRequest = { showOutput = false },
            title = { Text("Salida de audio") },
            text = { Text("Reproduciendo en este dispositivo (${android.os.Build.MODEL}).") },
            confirmButton = { TextButton(onClick = { showOutput = false }) { Text("Listo") } },
        )
    }
}

