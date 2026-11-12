package com.resonar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resonar.app.R
import com.resonar.app.data.MusicCollections
import com.resonar.app.data.albumArtUri

@Composable
fun DetailScreen(viewModel: ResonarViewModel, detail: LibraryDetail) {
    val library by viewModel.libraryState.collectAsState()
    val playback by viewModel.playbackState.collectAsState()
    val user by viewModel.userState.collectAsState()
    val tracks = remember(detail, library.tracks, user) { viewModel.tracksFor(detail) }
    val artPx = artDecodePx(96f, playback.audioQuality, playback.dataSaver)
    val title = when (detail) {
        is LibraryDetail.Artist -> library.artists.firstOrNull { it.id == detail.id }?.name ?: "Artista"
        is LibraryDetail.Album -> library.albums.firstOrNull { it.id == detail.id }?.name ?: "Álbum"
        is LibraryDetail.Genre -> detail.name
        is LibraryDetail.Collection -> viewModel.collectionTitle(detail.key)
    }
    val subtitle = when (detail) {
        is LibraryDetail.Album -> library.albums.firstOrNull { it.id == detail.id }?.artistName.orEmpty()
        is LibraryDetail.Genre -> "Género"
        else -> "${tracks.size} canciones"
    }
    val openCollection = detail as? LibraryDetail.Collection
    val canRemove = openCollection != null &&
        openCollection.key != MusicCollections.DAILY &&
        openCollection.key != MusicCollections.TRENDING
    val canDelete = openCollection != null &&
        openCollection.key !in setOf(
            MusicCollections.FAVORITES,
            MusicCollections.RECENT,
            MusicCollections.DAILY,
            MusicCollections.TRENDING,
        )

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::closeDetail) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Volver")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (detail) {
                is LibraryDetail.Album -> TrackArt(
                    library.albums.firstOrNull { it.id == detail.id }
                        ?.let { if (it.artId > 0L) albumArtUri(it.artId) else null },
                    84.dp,
                    18.dp,
                    artPx,
                )
                is LibraryDetail.Artist -> ArtistAvatar(title, 84.dp, 22.dp)
                is LibraryDetail.Genre -> GradientTile(
                    listOf(Color(0xFF6D28D9), Color(0xFFDB2777)),
                    R.drawable.ic_music,
                    Modifier.size(84.dp),
                    18.dp,
                )
                is LibraryDetail.Collection -> GradientTile(
                    listOf(Color(0xFF4C1D95), Color(0xFF818CF8)),
                    R.drawable.ic_playlists,
                    Modifier.size(84.dp),
                    18.dp,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { viewModel.playDetail() }, enabled = tracks.isNotEmpty(), shape = RoundedCornerShape(50)) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reproducir")
            }
            OutlinedButton(onClick = { viewModel.playDetail(true) }, enabled = tracks.isNotEmpty(), shape = RoundedCornerShape(50)) {
                Icon(painterResource(R.drawable.ic_shuffle), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Aleatorio")
            }
        }
        if (canDelete) {
            TextButton(
                onClick = { openCollection?.let { viewModel.deletePlaylist(it.key) } },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("Eliminar playlist") }
        }
        if (tracks.isEmpty()) {
            Text(
                "Esta lista todavía no tiene canciones.",
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                TrackRow(
                    track = track,
                    isCurrent = playback.currentMediaId == "local:${track.id}",
                    isPlaying = playback.isPlaying,
                    artPx = artPx,
                    favorite = track.id in user.favoriteIds,
                    onClick = { viewModel.playDetailAt(index) },
                    onFavorite = { viewModel.toggleFavorite(track.id) },
                    onAddToPlaylist = { viewModel.openPlaylistPicker(track.id) },
                    onRemove = if (canRemove) ({ viewModel.removeFromOpenCollection(track.id) }) else null,
                    // En una playlist abierta, deslizar quita DE LA LISTA; en los
                    // demás detalles (artista, álbum, género), borra la canción.
                    onDelete = if (canRemove) null else ({ viewModel.requestDeleteTrack(track) }),
                )
            }
        }
    }
}
