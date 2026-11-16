package com.resonar.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonar.app.R

/**
 * Seis pantallas: Inicio, Buscar, Biblioteca, Reproductor, Fila y Configuración.
 * En teléfono van en pila, con mini reproductor fijo. En pantallas anchas el reproductor
 * queda al lado y la navegación pasa a un rail.
 */
@Composable
fun AppShell(viewModel: ResonarViewModel) {
    val tab by viewModel.tab.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val showPlayer by viewModel.showPlayer.collectAsState()
    val showQueue by viewModel.showQueue.collectAsState()
    val showSettings by viewModel.showSettings.collectAsState()
    val showEqualizer by viewModel.showEqualizer.collectAsState()
    val playback by viewModel.playbackState.collectAsState()
    val library by viewModel.libraryState.collectAsState()
    val pickerTrack by viewModel.playlistPickerTrackId.collectAsState()
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val expanded = maxWidth >= 960.dp
        val useRail = maxWidth >= 600.dp
        val dockPlayer = expanded && !playback.currentTitle.isNullOrBlank()
        val fullPlayer = showPlayer && !dockPlayer
        val overlay = fullPlayer || showQueue || showSettings || showEqualizer

        Row(Modifier.fillMaxSize()) {
            if (useRail && !overlay) {
                SideNav(tab, viewModel::openTab)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (useRail) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).statusBarsPadding()) {
                            MainContent(viewModel, tab, detail, library, wide = true)
                        }
                        if (!overlay && !dockPlayer) {
                            val downloadJobs by viewModel.downloadQueue.collectAsState()
                            DownloadStatusPill(
                                jobs = downloadJobs,
                                onClick = viewModel::openDownloadsSheet,
                            )
                        }
                        if (!overlay && !dockPlayer && playback.currentTitle != null) {
                            MiniPlayer(
                                playback,
                                onOpen = viewModel::openPlayer,
                                onToggle = viewModel::togglePlayback,
                                onNext = viewModel::next,
                                onPrevious = viewModel::previous,
                            )
                        }
                        Spacer(Modifier.navigationBarsPadding())
                    }
                } else {
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        bottomBar = {
                            if (!overlay && !keyboardOpen) {
                                Column(Modifier.navigationBarsPadding()) {
                                    // Píldora "Descargando ahora": visible en
                                    // todas las pestañas mientras baja algo.
                                    val downloadJobs by viewModel.downloadQueue.collectAsState()
                                    DownloadStatusPill(
                                        jobs = downloadJobs,
                                        onClick = viewModel::openDownloadsSheet,
                                    )
                                    if (playback.currentTitle != null) {
                                        MiniPlayer(
                                            playback,
                                            onOpen = viewModel::openPlayer,
                                            onToggle = viewModel::togglePlayback,
                                            onNext = viewModel::next,
                                            onPrevious = viewModel::previous,
                                        )
                                    }
                                    BottomNav(tab, viewModel::openTab)
                                }
                            }
                        },
                    ) { padding ->
                        Box(Modifier.padding(padding).statusBarsPadding()) {
                            MainContent(viewModel, tab, detail, library, wide = false)
                        }
                    }
                }
            }
            if (dockPlayer && !overlay) {
                Box(
                    Modifier
                        .width(400.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    PlayerScreen(viewModel, playback, inPane = true, onBack = {})
                }
            }
        }

        Overlay(showSettings, fromEnd = true) {
            SettingsScreen(viewModel, playback, viewModel::closeSettings)
        }
        Overlay(fullPlayer, fromEnd = false) {
            PlayerScreen(viewModel, playback, inPane = false, onBack = viewModel::closePlayer)
        }
        Overlay(showQueue, fromEnd = false) {
            QueueScreen(viewModel, playback, viewModel::closeQueue)
        }
        // El ecualizador se abre desde Configuración y desde la tarjeta.
        // Tiene que quedar último: si va debajo de la tarjeta, los toques no le llegan.
        Overlay(showEqualizer, fromEnd = true) {
            EqualizerScreen(viewModel, playback, viewModel::closeEqualizer)
        }

        val picked = pickerTrack
        if (picked != null) {
            PlaylistPickerDialog(viewModel, picked, viewModel::closePlaylistPicker)
        }

        val pendingDelete by viewModel.pendingDelete.collectAsState()
        if (pendingDelete != null) {
            DeleteConfirmDialog(viewModel, pendingDelete!!, viewModel::cancelDelete)
        }

        val pendingPlaylistId by viewModel.pendingPlaylistDelete.collectAsState()
        if (pendingPlaylistId != null) {
            val playlistTitle = viewModel.playlistName(pendingPlaylistId!!)
            AlertDialog(
                onDismissRequest = viewModel::cancelDeletePlaylist,
                title = { Text("Eliminar playlist") },
                text = { Text("¿Eliminar «$playlistTitle»? Las canciones no se borran del teléfono.") },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmDeletePlaylist) { Text("Eliminar") }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelDeletePlaylist) { Text("Cancelar") }
                },
            )
        }

        val versionSheet by viewModel.versionSheet.collectAsState()
        if (versionSheet != null) {
            OnlineVersionSheet(
                viewModel = viewModel,
                result = versionSheet!!,
                onPlay = {
                    viewModel.closeVersionSheet()
                    viewModel.playOnline(it)
                },
                onDismiss = viewModel::closeVersionSheet,
            )
        }

        // Hoja de calidades: solo aparece si la fuente ofrece más de una.
        val qualitySheet by viewModel.qualitySheet.collectAsState()
        if (qualitySheet != null) {
            OnlineQualitySheet(
                choice = qualitySheet!!,
                onPick = { viewModel.downloadWithQuality(qualitySheet!!.track, it) },
                onDismiss = viewModel::closeQualitySheet,
            )
        }

        // HOJA "DESCARGANDO AHORA": la cola completa, con cancelar por fila.
        // Va a nivel AppShell (patrón probado en el Motorola: los diálogos
        // dentro de capas animadas no reciben el toque).
        val showDownloadsSheet by viewModel.showDownloadsSheet.collectAsState()
        if (showDownloadsSheet) {
            val downloadJobs by viewModel.downloadQueue.collectAsState()
            DownloadsSheet(
                jobs = downloadJobs,
                onCancel = viewModel::cancelDownload,
                onDismissItem = viewModel::dismissDownload,
                onClose = viewModel::closeDownloadsSheet,
            )
        }

        // Fuera de la animación de Configuración: ahí el botón sí recibe el toque.
        val showLanguageNote by viewModel.showLanguageNote.collectAsState()
        if (showLanguageNote) {
            LanguageNote(onClose = viewModel::closeLanguageNote)
        }

        when {
            showDownloadsSheet -> BackHandler { viewModel.closeDownloadsSheet() }
            qualitySheet != null -> BackHandler { viewModel.closeQualitySheet() }
            versionSheet != null -> BackHandler { viewModel.closeVersionSheet() }
            pendingPlaylistId != null -> BackHandler { viewModel.cancelDeletePlaylist() }
            pendingDelete != null -> BackHandler { viewModel.cancelDelete() }
            showLanguageNote -> BackHandler { viewModel.closeLanguageNote() }
            showQueue -> BackHandler { viewModel.closeQueue() }
            showEqualizer -> BackHandler { viewModel.closeEqualizer() }
            fullPlayer -> BackHandler { viewModel.closePlayer() }
            // Ajustes cierra su hoja de calidad o la pantalla. Si acá también
            // hubiera un BackHandler, se registraría después y ganaría siempre.
            showSettings -> Unit
            detail != null -> BackHandler { viewModel.closeDetail() }
        }
    }
}

@Composable
private fun MainContent(
    viewModel: ResonarViewModel,
    tab: MainTab,
    detail: LibraryDetail?,
    library: com.resonar.app.data.LibraryState,
    wide: Boolean,
) {
    var held by remember { mutableStateOf<LibraryDetail?>(null) }
    if (detail != null) held = detail

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(resonarScreenGradient()),
        )
        when {
            !library.hasPermission -> PermissionPrompt(viewModel::requestPermissions, viewModel::openSettings)
            else -> {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                        (slideInHorizontally { it / 5 * dir } + fadeIn())
                            .togetherWith(slideOutHorizontally { -it / 5 * dir } + fadeOut())
                    },
                    label = "pestanas",
                ) { destination ->
                    when (destination) {
                        // "Sin música" SOLO tapa Inicio. Las demás pestañas siempre
                        // sirven: buscar en línea, ver la biblioteca o las playlists
                        // tienen que funcionar aunque la carpeta esté vacía.
                        MainTab.Home -> HomeContent(viewModel, wide, library)
                        MainTab.Search -> SearchTab(viewModel, wide)
                        MainTab.Library -> LibraryTab(viewModel, wide)
                        MainTab.Playlists -> PlaylistsTab(viewModel)
                    }
                }
                AnimatedVisibility(
                    visible = detail != null,
                    enter = slideInHorizontally { it } + fadeIn(),
                    exit = slideOutHorizontally { it } + fadeOut(),
                ) {
                    held?.let { DetailScreen(viewModel, it) }
                }
            }
        }
    }
}

/**
 * Inicio: escaneando, biblioteca vacía o el panel de siempre.
 * El aviso de "sin música" vive acá y no en el contenedor de las pestañas:
 * si no, dejaba al usuario encerrado sin poder ir a Buscar.
 */
@Composable
private fun HomeContent(
    viewModel: ResonarViewModel,
    wide: Boolean,
    library: com.resonar.app.data.LibraryState,
) {
    when {
        !library.scannedOnce && library.loading -> ScanningLibrary()
        library.scannedOnce && library.tracks.isEmpty() ->
            EmptyLibrary(
                onRefresh = viewModel::refreshLibrary,
                onSettings = viewModel::openSettings,
                onSearchOnline = {
                    viewModel.setSearchMode(1)
                    viewModel.openTab(MainTab.Search)
                },
            )
        else -> HomeTab(viewModel, wide)
    }
}

@Composable
private fun Overlay(visible: Boolean, fromEnd: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = if (fromEnd) slideInHorizontally { it } + fadeIn() else slideInVertically { it } + fadeIn(),
        exit = if (fromEnd) slideOutHorizontally { it } + fadeOut() else slideOutVertically { it } + fadeOut(),
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

private data class NavItem(val tab: MainTab, val label: String, val icon: Int)

/**
 * Cartel "¿Seguro que querés eliminarlo?" tras deslizar una canción.
 * Vive a nivel AppShell (fuera de las animaciones de pantalla): los diálogos de
 * Material dentro de capas animadas no reciben el toque en el Motorola.
 */
@Composable
fun DeleteConfirmDialog(
    viewModel: ResonarViewModel,
    track: com.resonar.app.data.LocalTrack,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Eliminar \"${track.title}\"?") },
        text = {
            Text(
                if (track.fromDownloads || track.id >= 0) {
                    // Las descargas online viven en la app: se borran del teléfono, sin papelera.
                    "El audio se borra del teléfono y desaparece de Resonar (favoritos y playlists incluidas). Esta acción no tiene papelera."
                } else {
                    "El audio se manda a la papelera de tu carpeta de Drive y desaparece de Resonar (favoritos y playlists incluidas). Si te arrepentís, lo recuperás desde la app de Drive."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { viewModel.confirmDelete() }) {
                Text("Sí, eliminar", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("No") } },
    )
}

private val navItems = listOf(
    NavItem(MainTab.Home, "Inicio", R.drawable.ic_home),
    NavItem(MainTab.Search, "Buscar", R.drawable.ic_search),
    NavItem(MainTab.Library, "Tu biblioteca", R.drawable.ic_library),
    NavItem(MainTab.Playlists, "Playlists", R.drawable.ic_playlists),
)

@Composable
private fun BottomNav(selected: MainTab, onSelect: (MainTab) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
            navItems.forEach { item ->
                NavButton(item, item.tab == selected, Modifier.weight(1f)) { onSelect(item.tab) }
            }
        }
    }
}

@Composable
private fun SideNav(selected: MainTab, onSelect: (MainTab) -> Unit) {
    Column(
        Modifier
            .width(92.dp)
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        navItems.forEach { item ->
            NavButton(item, item.tab == selected, Modifier.padding(vertical = 6.dp)) { onSelect(item.tab) }
        }
    }
}

@Composable
private fun NavButton(item: NavItem, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(item.icon), contentDescription = item.label, tint = color, modifier = Modifier.size(24.dp))
        Text(
            item.label,
            color = color,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
