package com.resonar.app.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * Catálogo de prueba de la ETAPA 0: una lista de tracks por URL (streaming).
 * SoundHelix es música de ejemplo de uso libre, ideal para probar el servicio,
 * la notificación y el foco de audio sin pensar en fuentes todavía.
 * En la Etapa 2 esto se reemplaza por los catálogos reales (YouTube, SoundCloud, etc.).
 */
object SampleCatalog {

    data class SampleTrack(
        val id: String,
        val uri: String,
        val title: String,
        val artist: String,
    )

    val queue: List<SampleTrack> = listOf(
        SampleTrack(
            "sample-1",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
            "SoundHelix Song 1",
            "T. Schürger",
        ),
        SampleTrack(
            "sample-2",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
            "SoundHelix Song 2",
            "T. Schürger",
        ),
        SampleTrack(
            "sample-3",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3",
            "SoundHelix Song 3",
            "T. Schürger",
        ),
    )
}

/** Convierte un track del catálogo en un MediaItem de Media3, con metadata para la notificación. */
fun SampleCatalog.SampleTrack.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .build(),
        )
        .build()
