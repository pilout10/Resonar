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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resonar.app.R
import com.resonar.app.data.LocalLibraryRepository
import com.resonar.app.data.MusicCollections
import com.resonar.app.data.albumArtUri
import kotlin.math.abs

private val genrePalette = listOf(
    listOf(Color(0xFFEC4899), Color(0xFF7C3AED)),
    listOf(Color(0xFF2563EB), Color(0xFF1E3A8A)),
    listOf(Color(0xFF06B6D4), Color(0xFF1D4ED8)),
    listOf(Color(0xFFF59E0B), Color(0xFFEA580C)),
    listOf(Color(0xFF8B5CF6), Color(0xFF4C1D95)),
    listOf(Color(0xFFD97706), Color(0xFF7C2D12)),
    listOf(Color(0xFF0D9488), Color(0xFF134E4A)),
    listOf(Color(0xFFEF4444), Color(0xFF9D174D)),
)

private fun colorsFor(name: String): List<Color> {
    val known = mapOf(
        "Pop" to genrePalette[0],
        "Rock" to genrePalette[1],
        "Electrónica" to genrePalette[2],
        "Hip Hop / Rap" to genrePalette[3],
        "Dance" to genrePalette[4],
        "Clásica" to genrePalette[5],
        "Jazz" to genrePalette[6],
        "Latina" to genrePalette[7],
    )
    return known[name] ?: genrePalette[abs(name.hashCode()) % genrePalette.size]
}

@Composable
fun SearchTab(viewModel: ResonarViewModel, wide: Boolean) {
    val library by viewModel.libraryState.collectAsState()
    val playback by viewModel.playbackState.collectAsState()
    val user by viewModel.userState.collectAsState()
    val query by viewModel.query.collectAsState()
    val searchMode by viewModel.searchMode.collectAsState()
    var expandGenres by remember { mutableStateOf(false) }
    val artPx = artDecodePx(120f, playback.audioQuality, playback.dataSaver)
    val card = if (wide) 150.dp else 132.dp

    val popular = remember(library.artists) { library.artists.sortedByDescending { it.trackCount }.take(6) }
    val genreNames = remember(library.tracks, expandGenres) {
        val curated = MusicCollections.curated.map { it.first }.filter { name ->
            MusicCollections.tracksForGenre(library.tracks, name).isNotEmpty()
        }
        val extra = MusicCollections.extraGenres(library.tracks)
        if (expandGenres) curated + extra else (curated + extra).take(6)
    }
    val normalized = remember(query) { LocalLibraryRepository.normalizeForSearch(query).trim() }
    val songs = remember(library.tracks, normalized) {
        if (normalized.isEmpty()) emptyList() else library.tracks.filter { it.searchBlob.contains(normalized) }.take(60)
    }
    val artists = remember(library.artists, normalized) {
        if (normalized.isEmpty()) emptyList()
        else library.artists.filter { LocalLibraryRepository.normalizeForSearch(it.name).contains(normalized) }.take(20)
    }
    val albums = remember(library.albums, normalized) {
        if (normalized.isEmpty()) emptyList()
        else library.albums.filter {
            LocalLibraryRepository.normalizeForSearch("${it.name} ${it.artistName}").contains(normalized)
        }.take(20)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Text(
            "Buscar",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
        )
        TextField(
            value = query,
            onValueChange = viewModel::setQuery,
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = { Text("Buscar canciones, artistas...") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setQuery("") }) {
                        Icon(painterResource(R.drawable.ic_clear), contentDescription = "Limpiar búsqueda")
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        // Pills de modo: "Tu música" (biblioteca local) vs "En línea" (federada).
        ResonarTabs(
            labels = listOf("Tu música", "En línea"),
            selected = searchMode,
            onSelect = viewModel::setSearchMode,
        )

        if (searchMode == 1) {
            // Búsqueda federada: resultados de las fuentes de la nube.
            OnlineSearchPanel(viewModel, playback, Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
            if (normalized.isEmpty()) {
                if (popular.isNotEmpty()) {
                    item { SectionHeader("Búsquedas populares") }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            popular.chunked(3).forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    row.forEach { artist ->
                                        Text(
                                            artist.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(50))
                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                                .clickable { viewModel.openArtist(artist.id) }
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
                item {
                    SectionHeader(
                        "Explorar por género",
                        if (expandGenres) "Ver menos" else "Ver todo",
                    ) { expandGenres = !expandGenres }
                }
                if (genreNames.isEmpty()) {
                    item {
                        Text(
                            "Ninguna canción tiene género en el archivo.",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                genreNames.chunked(2).forEach { row ->
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            row.forEach { name ->
                                val count = MusicCollections.tracksForGenre(library.tracks, name).size
                                GenreCard(name, count, Modifier.weight(1f)) { viewModel.openGenre(name) }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                item { SectionHeader("Playlists para vos", "Ver todo") { viewModel.openTab(MainTab.Playlists) } }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            MiniPlaylistCard("Me gusta", card, listOf(Color(0xFF6D28D9), Color(0xFFC084FC)), R.drawable.ic_heart_fill) {
                                viewModel.openCollection(MusicCollections.FAVORITES)
                            }
                        }
                        item {
                            MiniPlaylistCard("Mix del día", card, listOf(Color(0xFF7C3AED), Color(0xFF818CF8)), R.drawable.ic_star) {
                                viewModel.openCollection(MusicCollections.DAILY)
                            }
                        }
                        item {
                            MiniPlaylistCard("Tendencias", card, listOf(Color(0xFF1D4ED8), Color(0xFF38BDF8)), R.drawable.ic_flame) {
                                viewModel.openCollection(MusicCollections.TRENDING)
                            }
                        }
                    }
                }
            } else if (songs.isEmpty() && artists.isEmpty() && albums.isEmpty()) {
                item {
                    Text(
                        "Nada coincide con \"$query\"",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                if (artists.isNotEmpty()) {
                    item { SectionHeader("Artistas") }
                    items(artists, key = { "ar-${it.id}" }) { artist ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.openArtist(artist.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ArtistAvatar(artist.name, 48.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text(trackCountLabel(artist.trackCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (albums.isNotEmpty()) {
                    item { SectionHeader("Álbumes") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(albums, key = { it.id }) { album ->
                                Column(Modifier.width(card).clickable { viewModel.openAlbum(album.id) }) {
                                    TrackArt(
                                        if (album.artId > 0L) albumArtUri(album.artId) else null,
                                        card,
                                        16.dp,
                                        artPx,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                    Text(album.artistName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (songs.isNotEmpty()) {
                    item { SectionHeader("Canciones") }
                    items(songs, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            isCurrent = playback.currentMediaId == "local:${track.id}",
                            isPlaying = playback.isPlaying,
                            artPx = artPx,
                            onClick = { viewModel.playTracks(songs, songs.indexOf(track).coerceAtLeast(0)) },
                            favorite = track.id in user.favoriteIds,
                            onFavorite = { viewModel.toggleFavorite(track.id) },
                            onAddToPlaylist = { viewModel.openPlaylistPicker(track.id) },
                            onDelete = { viewModel.requestDeleteTrack(track) },
                        )
                    }
                }
            }
        }
        } // fin del modo "Tu música"
    }
}

@Composable
private fun GenreCard(name: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(108.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(colorsFor(name)))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                trackCountLabel(count),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MiniPlaylistCard(title: String, size: androidx.compose.ui.unit.Dp, colors: List<Color>, icon: Int, onClick: () -> Unit) {
    Column(Modifier.width(size).clickable(onClick = onClick)) {
        GradientTile(colors, icon, Modifier.fillMaxWidth().height(size), 16.dp)
        Spacer(Modifier.height(6.dp))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}
