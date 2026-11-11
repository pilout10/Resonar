package com.resonar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.resonar.app.BuildConfig
import com.resonar.app.R
import com.resonar.app.data.LibrarySyncer
import com.resonar.app.player.PlaybackUiState

@Composable
fun SettingsScreen(viewModel: ResonarViewModel, playback: PlaybackUiState, onBack: () -> Unit) {
    val library by viewModel.libraryState.collectAsState()
    val sync by viewModel.syncProgress.collectAsState()
    val enabledSources by viewModel.enabledSources.collectAsState()
    val sourceToggles = listOf(
        "audius" to "Audius",
        "archive" to "Internet Archive",
        "youtube" to "YouTube",
    )
    var qualitySheet by remember { mutableStateOf(false) }
    var libraryDialog by remember { mutableStateOf(false) }
    val systemTheme = playback.themeMode == "system"

    // Los diálogos de Material, puestos dentro de la animación de esta pantalla,
    // se ven pero no reciben el toque en el Motorola. El cartel va en la misma capa.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Volver")
            }
            Text("Configuración", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        SectionLabel("Cuenta")
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().clickable { libraryDialog = true },
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                BoxIcon(R.drawable.ic_person)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Tu biblioteca", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${library.tracks.size} canciones en este dispositivo",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionLabel("Música")
        PrefRow(
            R.drawable.ic_library,
            "Carpeta de música",
            if (library.musicFolderLabel.isBlank()) "Todo el teléfono" else library.musicFolderLabel,
            onClick = viewModel::chooseMusicFolder,
        )
        Text(
            if (library.musicFolderLabel.isBlank()) {
                "Elegí la carpeta donde están, o van a estar, las canciones. Mientras no elijas una, se muestra todo el teléfono menos WhatsApp."
            } else {
                "Solo esta carpeta y sus subcarpetas. FLAC entra. Si es Google Drive, hace falta internet y Actualizar."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 52.dp, end = 8.dp, bottom = 4.dp),
        )
        if (library.musicFolderLabel.isNotBlank()) {
            TextButton(onClick = viewModel::clearMusicFolder) { Text("Usar todo el teléfono") }
        }

        SectionLabel("Fuentes en línea")
        Text(
            "De dónde sale la música cuando buscás en la pestaña \"En línea\". Si una fuente da problemas, se puede apagar acá.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 52.dp, end = 8.dp, bottom = 8.dp),
        )
        sourceToggles.forEach { (id, name) ->
            PrefRow(R.drawable.ic_music, name, trailing = {
                Switch(
                    checked = enabledSources[id] ?: true,
                    onCheckedChange = { viewModel.setSourceEnabled(id, it) },
                )
            })
        }
        if (enabledSources["youtube"] == true) {
            Text(
                // Licencia y honestidad al usuario: NewPipeExtractor es GPLv3 y
                // YouTube puede fallar sin previo aviso (poToken, cambios de la web).
                "YouTube entra vía NewPipeExtractor (GPLv3: por usarla, Resonar también es GPLv3). " +
                    "YouTube cambia su web seguido: si la fuente falla, es el servicio, no la app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, end = 8.dp, bottom = 8.dp),
            )
        }

        SectionLabel("Reproducción")
        PrefRow(
            R.drawable.ic_eq,
            "Calidad de audio",
            qualityLabel(playback.audioQuality),
            onClick = { qualitySheet = true },
        )
        PrefRow(R.drawable.ic_download, "Descarga automática", trailing = {
            Switch(checked = playback.autoDownload, onCheckedChange = viewModel::setAutoDownload)
        })
        Text(
            "Con WiFi, baja los temas de la nube (Drive) al teléfono para que suenen al toque y sin internet. " +
                "Si tu carpeta es del propio teléfono, no hace nada: esos archivos ya están en el disco.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 52.dp, end = 8.dp, bottom = 8.dp),
        )
        PrefRow(R.drawable.ic_chart, "Caché de streaming", trailing = {
            Switch(checked = playback.streamCache, onCheckedChange = viewModel::setStreamCache)
        })
        Text(
            "Guarda en el teléfono lo que vas escuchando de la nube (hasta 256 MB; lo viejo se borra solo). " +
                "Con esto apagado, Drive vuelve a tardar en cada tema. Es independiente de la descarga automática.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 52.dp, end = 8.dp, bottom = 8.dp),
        )
        if (sync.total > 0) {
            SyncProgressCard(
                sync = sync,
                onDownloadNow = viewModel::startSyncNow,
                onPause = viewModel::pauseSync,
                onResume = viewModel::resumeSync,
            )
        }
        PrefRow(R.drawable.ic_chart, "Modo ahorro de datos", trailing = {
            Switch(checked = playback.dataSaver, onCheckedChange = viewModel::setDataSaver)
        })
        Text(
            "Pide carátulas más chicas para usar menos memoria.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 52.dp, end = 8.dp, bottom = 8.dp),
        )
        PrefRow(
            R.drawable.ic_eq,
            "Ecualizador",
            if (!playback.eqAvailable) "Al reproducir" else playback.eqPresetName,
            onClick = viewModel::openEqualizer,
        )
        PrefRow(R.drawable.ic_speaker, "Seguir sonando si otra app pide el audio", trailing = {
            Switch(checked = playback.keepPlaying, onCheckedChange = viewModel::setKeepPlaying)
        })

        SectionLabel("Apariencia")
        PrefRow(R.drawable.ic_theme, "Tema del sistema", trailing = {
            Switch(
                checked = systemTheme,
                onCheckedChange = { viewModel.setThemeMode(if (it) "system" else "dark") },
            )
        })
        PrefRow(R.drawable.ic_theme, "Tema oscuro", trailing = {
            Switch(
                // Solo "dark" está prendido: con "Tema del sistema" el color lo
                // manda Android, así que el interruptor no debe mentir.
                checked = playback.themeMode == "dark",
                enabled = !systemTheme,
                onCheckedChange = { viewModel.setThemeMode(if (it) "dark" else "light") },
            )
        })
        PrefRow(R.drawable.ic_language, "Idioma", "Español", onClick = viewModel::openLanguageNote)

        SectionLabel("Dispositivo")
        PrefRow(
            R.drawable.ic_battery,
            "Batería",
            if (playback.batteryExempt) "Sin restricciones" else "Restringida",
            onClick = viewModel::openBatterySettings,
        )
        PrefRow(R.drawable.ic_battery, "Pedir sin restricciones", onClick = viewModel::requestBatteryExemption)
        PrefRow(R.drawable.ic_library, "Actualizar biblioteca", if (library.loading) "Escaneando…" else null, onClick = viewModel::refreshLibrary)

        SectionLabel("Acerca de")
        PrefRow(R.drawable.ic_music, "Versión", BuildConfig.VERSION_NAME)
        if (playback.ramMb >= 0) {
            Text(
                "RAM del proceso: ${playback.ramMb} MB",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
        }
    }

        if (qualitySheet) {
            BottomChoice(
                onDismiss = { qualitySheet = false },
            ) {
                Text("Calidad de audio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Los archivos locales se escuchan en su calidad original. La opción cambia el tamaño de las carátulas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                listOf("alta" to "Alta", "estandar" to "Estándar", "ahorro" to "Ahorro").forEach { (value, label) ->
                    val selected = playback.audioQuality == value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                viewModel.setAudioQuality(value)
                                qualitySheet = false
                            }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, modifier = Modifier.weight(1f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        if (selected) {
                            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        if (libraryDialog) {
            CenterCard(onDismiss = { libraryDialog = false }) {
                Text("Tu biblioteca", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${library.tracks.size} canciones · ${library.artists.size} artistas · ${library.albums.size} álbumes. Nada sale de este dispositivo.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { libraryDialog = false }) { Text("Cerrar") }
                    TextButton(onClick = {
                        viewModel.refreshLibrary()
                        libraryDialog = false
                    }) { Text("Actualizar") }
                }
            }
        }
    }
}

/**
 * Cartel de idioma, dibujado encima de toda la app.
 * El fondo y el botón son hermanos: si el fondo envuelve al botón, el toque no llega a «Listo».
 */
@Composable
fun LanguageNote(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Scrim(onClose)
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 28.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Idioma", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Resonar está en español.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Listo")
                }
            }
        }
    }
}

/** Fondo que cierra al tocar afuera. No envuelve al cartel, para no robarle el toque. */
@Composable
private fun Scrim(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    )
}

@Composable
private fun CenterCard(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Scrim(onDismiss)
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 28.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(start = 20.dp, top = 20.dp, end = 12.dp, bottom = 12.dp)) {
                content()
            }
        }
    }
}

/** Panel inferior para elegir una opción, con el mismo arreglo que el cartel. */
@Composable
private fun BottomChoice(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Scrim(onDismiss)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                content()
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun BoxIcon(icon: Int) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

@Composable
/**
 * onClick va al final a propósito. Si queda antes de trailing, una lambda
 * `{ abrir() }` se ejecuta al dibujar la fila y el cartel se vuelve a abrir solo:
 * «Listo» parece que no responde.
 */
private fun PrefRow(
    icon: Int,
    title: String,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxIcon(icon)
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        if (value != null) {
            Text(
                value,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun qualityLabel(value: String): String = when (value) {
    "estandar" -> "Estándar"
    "ahorro" -> "Ahorro"
    else -> "Alta"
}

/**
 * Tarjeta de la descarga automática de la biblioteca de nube (Drive).
 * Muestra cuánto falta, qué está bajando ahora, y permite pausar o forzar
 * la descarga aunque no haya WiFi ("Descargar todo ahora" usa datos móviles).
 */
@Composable
private fun SyncProgressCard(
    sync: LibrarySyncer.SyncProgress,
    onDownloadNow: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val complete = sync.done >= sync.total && !sync.running
    val title = when {
        sync.running -> "Descargando de la nube"
        !sync.reason.isBlank() -> sync.reason
        complete -> "Biblioteca en el teléfono ✓"
        else -> "Descarga pendiente"
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoxIcon(R.drawable.ic_download)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append("${sync.done} de ${sync.total} temas en el teléfono")
                            if (sync.failed > 0) append(" · ${sync.failed} fallaron (reintentan)")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (!complete) {
                LinearProgressIndicator(
                    progress = {
                        if (sync.total > 0) sync.done.toFloat() / sync.total else 0f
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
            if (sync.running && sync.currentTitle.isNotBlank()) {
                Text(
                    "Bajando: ${sync.currentTitle}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            TextButton(
                onClick = if (sync.running) onPause else if (sync.paused) onResume else onDownloadNow,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    when {
                        sync.running -> "Pausar"
                        sync.paused -> "Continuar"
                        complete -> "Descargar todo otra vez"
                        else -> "Descargar todo ahora"
                    },
                )
            }
        }
    }
}
