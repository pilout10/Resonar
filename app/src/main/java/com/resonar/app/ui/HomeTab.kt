package com.resonar.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.resonar.app.R
import com.resonar.app.data.MusicCollections
import com.resonar.app.data.albumArtUri
import kotlinx.coroutines.delay

@Composable
fun HomeTab(viewModel: ResonarViewModel, wide: Boolean) {
    val library by viewModel.libraryState.collectAsState()
    val user by viewModel.userState.collectAsState()
    val playback by viewModel.playbackState.collectAsState()
    val byId = remember(library.tracks) { library.tracks.associateBy { it.id } }
    val recent = remember(user.recentIds, byId) { user.recentIds.mapNotNull { byId[it] }.take(12) }
    val favorites = remember(user.favoriteIds, byId) { user.favoriteIds.mapNotNull { byId[it] } }
    val daily = remember(library.tracks) { MusicCollections.dailyMix(library.tracks, salt = 1) }
    val trending = remember(library.tracks) { MusicCollections.dailyMix(library.tracks, salt = 99) }
    val daySeed = System.currentTimeMillis() / 86_400_000L
    val recommended = remember(library.albums, daySeed) {
        if (library.albums.isEmpty()) emptyList()
        else library.albums.shuffled(kotlin.random.Random(daySeed)).take(12)
    }
    val orderedTracks = remember(library.tracks) {
        library.tracks.sortedWith(compareBy({ it.sourceRank }, { it.title.lowercase() }))
    }
    val onPhone = remember(orderedTracks) { orderedTracks.take(12) }
    val artPx = artDecodePx(if (wide) 160f else 132f, playback.audioQuality, playback.dataSaver)
    val cardWidth = if (wide) 156.dp else 128.dp
    val heroArt = playback.currentArtUri
        ?: recent.firstOrNull()?.let { albumArtUri(it.albumId) }
        ?: library.albums.firstOrNull()?.let { albumArtUri(it.id) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_music),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Resonar",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.openTab(MainTab.Search) }) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = "Buscar")
            }
            IconButton(onClick = viewModel::openSettings) {
                Icon(painterResource(R.drawable.ic_gear), contentDescription = "Configuración")
            }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                HeroBanner(
                    art = heroArt,
                    artPx = if (playback.dataSaver) 480 else 900,
                    slides = heroSlides(playback.currentTitle, recent.size, daily.size),
                    onPlay = { slide ->
                        when (slide) {
                            HeroSlide.Continue -> if (recent.isNotEmpty()) viewModel.playAll(recent) else viewModel.togglePlayback()
                            HeroSlide.Daily -> viewModel.playAll(daily)
                            HeroSlide.Promo -> when {
                                playback.currentTitle != null && !playback.isPlaying -> viewModel.togglePlayback()
                                playback.currentTitle != null -> Unit
                                recent.isNotEmpty() -> viewModel.playAll(recent)
                                else -> viewModel.shufflePlay(library.tracks)
                            }
                        }
                        viewModel.openPlayer()
                    },
                )
            }
            item {
                SectionHeader("Tu música", if (library.tracks.isNotEmpty()) "Ver todo" else null) {
                    viewModel.openSongs()
                }
            }
            item {
                Text(
                    if (library.musicFolderLabel.isBlank()) {
                        "Todo el teléfono, menos audios de WhatsApp. La carpeta se elige en Configuración."
                    } else {
                        "Solo ${library.musicFolderLabel}."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            if (library.loading && library.tracks.isEmpty()) {
                item {
                    Text(
                        "Buscando canciones…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else if (library.scannedOnce && onPhone.isEmpty()) {
                item {
                    Text(
                        "Todavía no hay canciones. Copialas a Music o a Music/Resonar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(onPhone, key = { "home-${it.id}" }) { track ->
                    TrackRow(
                        track = track,
                        isCurrent = playback.currentMediaId == "local:${track.id}",
                        isPlaying = playback.isPlaying,
                        artPx = artPx,
                        onClick = {
                            val index = orderedTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                            viewModel.playTracks(orderedTracks, index)
                        },
                        onDelete = { viewModel.requestDeleteTrack(track) },
                    )
                }
            }
            if (recent.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Escuchado recientemente",
                    action = "Ver todo",
                    onAction = { viewModel.openCollection(MusicCollections.RECENT) },
                )
            }
            item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(recent, key = { it.id }) { track ->
                            Column(
                                modifier = Modifier
                                    .width(cardWidth)
                                    .clickable {
                                        viewModel.playTracks(recent, recent.indexOf(track).coerceAtLeast(0))
                                    },
                            ) {
                                TrackArt(albumArtUri(track.albumId), cardWidth, 16.dp, artPx)
                                Spacer(Modifier.height(8.dp))
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
            }
            }
            item {
                SectionHeader("Playlists destacadas", "Ver todo") { viewModel.openTab(MainTab.Playlists) }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FeatureCard(
                        title = "Mix Favoritos",
                        count = favorites.size,
                        colors = listOf(Color(0xFF6D28D9), Color(0xFFC084FC)),
                        icon = R.drawable.ic_star,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openCollection(MusicCollections.FAVORITES) },
                    )
                    FeatureCard(
                        title = "Tendencias",
                        count = trending.size,
                        colors = listOf(Color(0xFF1D4ED8), Color(0xFF38BDF8)),
                        icon = R.drawable.ic_flame,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openCollection(MusicCollections.TRENDING) },
                    )
                }
            }
            if (recommended.isNotEmpty()) {
                item { SectionHeader("Recomendado para ti") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(recommended, key = { it.id }) { album ->
                            Column(
                                modifier = Modifier.width(cardWidth).clickable { viewModel.openAlbum(album.id) },
                            ) {
                                TrackArt(albumArtUri(album.id), cardWidth, 16.dp, artPx)
                                Spacer(Modifier.height(8.dp))
                                Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text(album.artistName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class HeroSlide { Promo, Continue, Daily }

private fun heroSlides(current: String?, recent: Int, daily: Int): List<HeroSlide> = buildList {
    add(HeroSlide.Promo)
    if (current != null || recent > 0) add(HeroSlide.Continue)
    if (daily > 0) add(HeroSlide.Daily)
}

@Composable
private fun HeroBanner(
    art: Any?,
    artPx: Int,
    slides: List<HeroSlide>,
    onPlay: (HeroSlide) -> Unit,
) {
    var index by remember { mutableIntStateOf(0) }
    var drag by remember { mutableFloatStateOf(0f) }
    val count = slides.size.coerceAtLeast(1)
    val shown = index.coerceIn(0, count - 1)

    LaunchedEffect(count) {
        while (true) {
            delay(7000)
            index = (index + 1) % count
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(214.dp)
                .clip(RoundedCornerShape(28.dp))
                .pointerInput(shown, count) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when {
                                drag < -48f -> index = (shown + 1) % count
                                drag > 48f -> index = (shown - 1 + count) % count
                            }
                            drag = 0f
                        },
                        onHorizontalDrag = { _, amount -> drag += amount },
                    )
                },
        ) {
            if (art != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(art).crossfade(true).size(artPx).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(Color(0xF01A0A2E), Color(0xCC5B21B6), Color(0x66312E81), Color(0x332563EB)),
                    ),
                ),
            )
            AnimatedContent(
                targetState = slides[shown],
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "hero",
                modifier = Modifier.align(Alignment.BottomStart).padding(18.dp),
            ) { slide ->
                val (title, subtitle, button) = when (slide) {
                    HeroSlide.Continue -> Triple("Seguí\nescuchando", "Retomá donde lo dejaste", "Reproducir ahora")
                    HeroSlide.Daily -> Triple("Mix del día", "Una selección nueva cada día", "Reproducir ahora")
                    HeroSlide.Promo -> Triple("Tu música,\nen otro nivel", "Descubre · Escucha · Disfruta", "Reproducir ahora")
                }
                Column {
                    Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 32.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, color = Color.White.copy(alpha = 0.86f), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White)
                            .clickable(role = Role.Button) { onPlay(slide) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = Color(0xFF1A1028), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(button, color = Color(0xFF1A1028), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(count) { dot ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (dot == shown) 7.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (dot == shown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { index = dot },
                )
            }
        }
    }
}

@Composable
private fun FeatureCard(
    title: String,
    count: Int,
    colors: List<Color>,
    icon: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .height(118.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(colors))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$count canciones",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
