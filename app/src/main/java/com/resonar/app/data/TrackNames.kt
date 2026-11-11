package com.resonar.app.data

/**
 * Nombres de archivo de las canciones: una sola regla para todos.
 *
 * Resonar descarga archivos con un sufijo interno para poder reconocerlos:
 * "PALO - HACELA CORTA MOROCHO [youtube__0lGeXKAdFc].m4a". Ese sufijo NO es
 * música: es bookkeeping de la app y nunca debe mostrarse en la biblioteca.
 *
 * OJO con el patrón: el ID de YouTube puede empezar con guion bajo
 * ("_0lGeXKAdFc"), así que el sufijo llega con DOS guiones bajos
 * ("[youtube__0lGeXKAdFc]"). Un regex que no lo admita deja de detectar
 * nuestras propias descargas y el título sale con el feo sufijo pegado.
 */
object TrackNames {

    /** Sufijo interno: "[youtube_xxxx]", "[audius_xxxx]", "[archive_xxxx]"… */
    private val ownMarker = Regex("""\s*\[[a-z]+_[A-Za-z0-9_-]{6,}\]""")

    /** ¿Este archivo es una descarga hecha por Resonar? */
    fun isOwnDownload(fileName: String): Boolean = ownMarker.containsMatchIn(fileName)

    /** Le saca el sufijo interno (y deja el nombre limpio). */
    fun stripMarker(name: String): String = name.replace(ownMarker, "").trim()

    /**
     * Separa "Artista - Título" (el formato que usa el descargador de Resonar).
     * Devuelve (artista, título). Si no hay guion, el artista queda null.
     */
    fun splitArtistTitle(name: String): Pair<String?, String> {
        val at = name.indexOf(" - ")
        if (at <= 0 || at > name.length - 3) return null to name.trim()
        return name.take(at).trim() to name.substring(at + 3).trim()
    }
}
