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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resonar.app.R
import com.resonar.app.data.MusicCollections
import com.resonar.app.data.albumArtUri

private val playlistColors = listOf(
    listOf(Color(0xFF6D28D9), Color(0xFFC084FC)),
    listOf(Color(0xFF0F766E), Color(0xFF2DD4BF)),
    listOf(Color(0xFF1D4ED8), Color(0xFF38BDF8)),
    listOf(Color(0xFF9D174D), Color(0xFFFB7185)),
    listOf(Color(0xFFB45309), Color(0xFFFBBF24)),
)

@Composable
fun LibraryTab(viewModel: ResonarViewModel, wide: Boolean) {
    val section by viewModel.librarySection.collectAsState()
    Column(Modifier.fillMaxSize()) {
        LibraryHeader(onSettings = viewModel::openSettings)
        ResonarTabs(listOf("Playlists", "Álbumes", "Artistas", "Canciones"), section, viewModel::setLibrarySection)
        Box(Modifier.weight(1f)) {
            when (section) {
                0 -> PlaylistsPage(viewModel)
                1 -> AlbumPage(viewModel, wide)
                2 -> ArtistPage(viewModel)
                else -> SongPage(viewModel)
            }
        }
    }
}

@Composable
fun PlaylistsTab(viewModel: ResonarViewModel) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Playlists",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        PlaylistsPage(viewModel)
    }
}

@Composable
private fun LibraryHeader(onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Tu biblioteca",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_person), contentDescription = "Configuración", modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun PlaylistsPage(viewModel: ResonarViewModel) {
    val library by viewModel.libraryState.collectAsState()
    val user by viewModel.userState.collectAsState()
    val playback by viewModel.playbackState.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val artPx = artDecodePx(64f, playback.audioQuality, playback.dataSaver)
    val byId = remember(library.tracks) { library.tracks.associateBy { it.id } }
    val total = library.tracks.size

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { creating = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                }
                Spacer(Modifier.width(12.dp))
                Text("Crear playlist", style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            PlaylistRow(
                title = "Me gusta",
                count = user.favoriteIds.count { byId[it] != null },
                colors = playlistColors[0],
                icon = R.drawable.ic_heart_fill,
                art = null,
                artPx = artPx,
                onClick = { viewModel.openCollection(MusicCollections.FAVORITES) },
                onPlay = { viewModel.playCollection(MusicCollections.FAVORITES) },
                onShuffle = { viewModel.playCollection(MusicCollections.FAVORITES, shuffle = true) },
            )
        }
        item {
            PlaylistRow(
                title = "Escuchado recientemente",
                count = user.recentIds.count { byId[it] != null },
                colors = playlistColors[1],
                icon = R.drawable.ic_music,
                art = null,
                artPx = artPx,
                onClick = { viewModel.openCollection(MusicCollections.RECENT) },
                onPlay = { viewModel.playCollection(MusicCollections.RECENT) },
                onShuffle = { viewModel.playCollection(MusicCollections.RECENT, true) },
            )
        }
        item {
            PlaylistRow(
                title = "Mix del día",
                count = total.coerceAtMost(30),
                colors = playlistColors[2],
                icon = R.drawable.ic_star,
                art = null,
                artPx = artPx,
                onClick = { viewModel.openCollection(MusicCollections.DAILY) },
                onPlay = { viewModel.playCollection(MusicCollections.DAILY) },
                onShuffle = { viewModel.playCollection(MusicCollections.DAILY, true) },
            )
        }
        item {
            PlaylistRow(
                title = "Tendencias",
                count = total.coerceAtMost(30),
                colors = playlistColors[3],
                icon = R.drawable.ic_flame,
                art = null,
                artPx = artPx,
                onClick = { viewModel.openCollection(MusicCollections.TRENDING) },
                onPlay = { viewModel.playCollection(MusicCollections.TRENDING) },
                onShuffle = { viewModel.playCollection(MusicCollections.TRENDING, true) },
            )
        }
        items(user.playlists, key = { it.id }) { playlist ->
            val first = playlist.trackIds.firstOrNull()?.let { byId[it] }
            PlaylistRow(
                title = playlist.name,
                // El contador honesto: temas locales VIVOS + temas online
                // guardados (los locales borrados del teléfono no se cuentan).
                count = playlist.trackIds.count { byId[it] != null } + playlist.onlineTracks.size,
                colors = playlistColors[absHash(playlist.id) % playlistColors.size],
                icon = R.drawable.ic_playlists,
                art = first?.let { it.artUrl ?: if (it.albumArtId > 0L) albumArtUri(it.albumArtId) else null },
                artPx = artPx,
                onClick = { viewModel.openCollection(playlist.id) },
                onPlay = { viewModel.playCollection(playlist.id) },
                onShuffle = { viewModel.playCollection(playlist.id, true) },
                onDelete = { viewModel.requestDeletePlaylist(playlist.id) },
            )
        }
    }

    if (creating) {
        PlaylistNameDialog(
            title = "Nueva playlist",
            name = name,
            onName = { name = it },
            confirmLabel = "Crear",
            onConfirm = {
                if (viewModel.createPlaylist(name) != null) {
                    name = ""
                    creating = false
                }
            },
            dismissLabel = "Cancelar",
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun PlaylistRow(
    title: String,
    count: Int,
    colors: List<Color>,
    icon: Int,
    art: Any?,
    artPx: Int,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (art != null) {
            TrackArt(art, 56.dp, 14.dp, artPx)
        } else {
            GradientTile(colors, icon, Modifier.size(56.dp), 14.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                trackCountLabel(count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more), contentDescription = "Opciones de $title")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Reproducir") }, onClick = { menu = false; onPlay() })
                DropdownMenuItem(text = { Text("Aleatorio") }, onClick = { menu = false; onShuffle() })
                if (onDelete != null) {
                    DropdownMenuItem(text = { Text("Eliminar playlist") }, onClick = { menu = false; onDelete?.invoke() })
                }
            }
        }
    }
}

@Composable
private fun AlbumPage(viewModel: ResonarViewModel, wide: Boolean) {
    val library by viewModel.libraryState.collectAsState()
    val playback by viewModel.playbackState.collectAsState()
    val artPx = artDecodePx(if (wide) 220f else 180f, playback.audioQuality, playback.dataSaver)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (wide) 180.dp else 148.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItems(library.albums, key = { it.id }) { album ->
            Column(Modifier.clickable { viewModel.openAlbum(album.id) }) {
                FillArt(
                    model = if (album.artId > 0L) albumArtUri(album.artId) else null,
                    modifier = Modifier.fillMaxWidth().height(if (wide) 180.dp else 148.dp),
                    corner = 16.dp,
                    maxDecodePx = artPx,
                )
                Spacer(Modifier.height(6.dp))
                Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(album.artistName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ArtistPage(viewModel: ResonarViewModel) {
    val library by viewModel.libraryState.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(library.artists, key = { it.id }) { artist ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.openArtist(artist.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtistAvatar(artist.name, 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(trackCountLabel(artist.trackCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SongPage(viewModel: ResonarViewModel) {
    val library by viewModel.libraryState.collectAsState()
    val playback by viewModel.playbackState.collectAsState()
    val user by viewModel.userState.collectAsState()
    val artPx = artDecodePx(52f, playback.audioQuality, playback.dataSaver)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        item {
            Text(
                if (library.musicFolderLabel.isBlank()) {
                    "Todo el teléfono, menos audios de WhatsApp."
                } else {
                    "Carpeta ${library.musicFolderLabel}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            TextButton(
                onClick = { viewModel.shufflePlay(library.tracks) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Icon(painterResource(R.drawable.ic_shuffle), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Aleatorio · ${trackCountLabel(library.tracks.size)}")
            }
        }
        items(library.tracks, key = { it.id }) { track ->
            TrackRow(
                track = track,
                isCurrent = playback.currentMediaId == "local:${track.id}",
                isPlaying = playback.isPlaying,
                artPx = artPx,
                favorite = track.id in user.favoriteIds,
                onClick = {
                    viewModel.playTracks(library.tracks, library.tracks.indexOf(track).coerceAtLeast(0))
                },
                onFavorite = { viewModel.toggleFavorite(track.id) },
                onAddToPlaylist = { viewModel.openPlaylistPicker(track.id) },
                onDelete = { viewModel.requestDeleteTrack(track) },
            )
        }
    }
}

@Composable
fun PlaylistPickerDialog(viewModel: ResonarViewModel, onDismiss: () -> Unit) {
    val user by viewModel.userState.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    PlaylistFormDialog(onDismiss = onDismiss) {
        Text(
            if (creating) "Nueva playlist" else "Agregar a playlist",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        if (creating) {
            TextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Nombre") })
        } else {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (user.playlists.isEmpty()) {
                    Text("Todavía no tenés playlists. Creá una y sumamos esta canción.")
                }
                user.playlists.forEach { playlist ->
                    Text(
                        playlist.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Sirve para el tema local (id) y el online (referencia):
                                // el ViewModel decide cuál de los dos está esperando.
                                viewModel.confirmPlaylistPick(playlist.id)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
            TextButton(
                onClick = {
                    if (creating) {
                        val id = viewModel.createPlaylist(name)
                        if (id != null) {
                            viewModel.confirmPlaylistPick(id)
                            onDismiss()
                        }
                    } else {
                        creating = true
                    }
                },
                enabled = !creating || name.isNotBlank(),
            ) { Text(if (creating) "Crear" else "Nueva") }
        }
    }
}

/** Nombre de playlist. El teclado empuja el cartel: Crear no queda tapado. */
@Composable
private fun PlaylistNameDialog(
    title: String,
    name: String,
    onName: (String) -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
) {
    PlaylistFormDialog(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        TextField(value = name, onValueChange = onName, singleLine = true, placeholder = { Text("Nombre") })
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
            TextButton(onClick = onConfirm, enabled = name.isNotBlank()) { Text(confirmLabel) }
        }
    }
}

@Composable
private fun PlaylistFormDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .imePadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) { content() }
            }
        }
    }
}

private fun absHash(value: String): Int = value.hashCode().let { if (it < 0) -it else it }
