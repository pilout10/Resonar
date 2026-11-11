package com.resonar.app.data

/**
 * Colecciones que la app arma sola a partir de la biblioteca local:
 * géneros (por etiqueta) y mixes del día (estables durante 24 h, sin red).
 */
object MusicCollections {

    const val FAVORITES = "favorites"
    const val RECENT = "recent"
    const val DAILY = "daily"
    const val TRENDING = "trending"

    /** Nombre visible → palabras que matchean el género de la etiqueta. */
    val curated: List<Pair<String, List<String>>> = listOf(
        "Pop" to listOf("pop"),
        "Rock" to listOf("rock", "metal", "punk"),
        "Electrónica" to listOf("electro", "edm", "house", "techno", "trance", "dubstep"),
        "Hip Hop / Rap" to listOf("hip", "rap", "trap"),
        "Dance" to listOf("dance"),
        "Clásica" to listOf("clasic", "classic", "baroque", "opera", "orchestra"),
        "Jazz" to listOf("jazz", "blues", "swing"),
        "Latina" to listOf("latin", "salsa", "cumbia", "bachata", "reggaeton", "urbano"),
    )

    fun matchesGenre(track: LocalTrack, name: String): Boolean {
        val genre = LocalLibraryRepository.normalizeForSearch(track.genre)
        if (genre.isBlank()) return false
        val keywords = curated.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
        if (keywords == null) {
            return genre == LocalLibraryRepository.normalizeForSearch(name)
        }
        return keywords.any { genre.contains(it) }
    }

    fun tracksForGenre(tracks: List<LocalTrack>, name: String): List<LocalTrack> =
        tracks.filter { matchesGenre(it, name) }

    /** Géneros reales de la biblioteca que no entraron en las tarjetas fijas. */
    fun extraGenres(tracks: List<LocalTrack>): List<String> {
        val covered = curated.flatMap { it.second }
        return tracks.map { it.genre.trim() }
            .filter { it.isNotBlank() }
            .filter { genre ->
                val normalized = LocalLibraryRepository.normalizeForSearch(genre)
                covered.none { normalized.contains(it) }
            }
            .distinctBy { LocalLibraryRepository.normalizeForSearch(it) }
            .sortedBy { it.lowercase() }
    }

    /**
     * Mix estable por día. [salt] separa "Mix del día" de "Tendencias"
     * sin guardar otra lista en disco.
     */
    fun dailyMix(tracks: List<LocalTrack>, salt: Int, take: Int = 30): List<LocalTrack> {
        if (tracks.isEmpty()) return emptyList()
        val day = System.currentTimeMillis() / 86_400_000L
        val random = kotlin.random.Random(day xor salt.toLong())
        return tracks.shuffled(random).take(take.coerceAtMost(tracks.size))
    }
}
