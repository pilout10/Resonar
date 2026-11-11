package com.resonar.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.resonar.app.player.SampleCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.math.max

/**
 * Track de la biblioteca local. Elegimos un objeto compacto (nada de nullables
 * de más, nada de imágenes cargadas acá) porque un celu de gama de entrada con
 * 2.000 canciones va a mantener ~2.000 de estos en RAM y tiene que pesar nada.
 */
data class LocalTrack(
    val id: Long,           // id de MediaStore
    val uri: String,        // content://… del archivo (streaming local)
    val title: String,      // del tag ID3, o derivado del nombre de archivo
    val artist: String,
    val album: String,
    val albumId: Long,
    val artistId: Long,
    val durationMs: Long,
    val trackNo: Int,
    val folder: String,     // carpeta contenedora (para búsqueda por carpeta)
    val fileName: String,
    /** 0 Music/Resonar, 1 Music/, 2 el resto. Sirve para ordenar Inicio sin reescanear. */
    val sourceRank: Int = 2,
    val year: Int,
    /** Género de la etiqueta (puede venir vacío si el archivo no lo trae). */
    val genre: String,
    /** Todo concatenado en minúsculas y sin tildes: una sola búsqueda O(n) simple. */
    val searchBlob: String,
    /** true si es una descarga online (YouTube/IA/Audius) guardada en la app. */
    val fromDownloads: Boolean = false,
    /**
     * Carátula por URL (la de la fuente en línea: YouTube, Audius…). null si no la
     * hay. Es lo que permite mostrar la portada de una descarga sin tocar el disco.
     */
    val artUrl: String? = null,
)

/** Un artista con sus datos para la pestaña de artistas. */
data class ArtistInfo(val id: Long, val name: String, val trackCount: Int)

/** Un álbum para la pestaña de álbumes. */
data class AlbumInfo(val id: Long, val name: String, val artistName: String, val trackCount: Int)

/**
 * Estado entero de la biblioteca. Los mapas agrupados (artista/álbum) se
 * calculan UNA vez al escanear, no en cada recomposición de Compose.
 */
data class LibraryState(
    val hasPermission: Boolean = false,
    val loading: Boolean = false,
    val scannedOnce: Boolean = false,
    val tracks: List<LocalTrack> = emptyList(),
    val artists: List<ArtistInfo> = emptyList(),
    val albums: List<AlbumInfo> = emptyList(),
    /** Vacío = todo el teléfono. Si no, el nombre de la carpeta elegida en Configuración. */
    val musicFolderLabel: String = "",
)

/** URI de la carátula de un álbum en el ContentProvider de MediaStore. */
fun albumArtUri(albumId: Long): Uri =
    android.content.ContentUris.withAppendedId(
        Uri.parse("content://media/external/audio/albumart"),
        albumId,
    )

/**
 * ESCÁNER + ÍNDICE de la música local.
 *
 * Decisión de diseño (eficiencia sin perder catálogo): el índice vive en RAM a
 * partir de MediaStore (la base de datos que Android YA mantiene, así no
 * duplicamos trabajo ni la abrimos dos veces). Para el tamaño típico
 * (1.000–4.000 tracks) el índice entero ocupa ~1–2 MB con searchBlob incluido:
 * menos que una sola carátula cargada mal. Room entra en la Etapa 2, donde lo
 * que hay que cachear en el disco son resultados de fuentes remotas (TTL), no
 * esto.
 *
 * Tolerancia a etiquetas malas (requisito de la etapa): si el tag ID3 está
 * vacío, derivamos artista/título del NOMBRE DE ARCHIVO y la CARPETA, y la
 * búsqueda pega sobre todo eso (searchBlob).
 */
class LocalLibraryRepository(
    context: Context,
    private val scope: CoroutineScope,
    /** Índice de descargas online; sus temas también son parte de "Tu música". */
    private val downloads: OnlineDownloader? = null,
) {

    private val app: Context = context.applicationContext
    private val folderStore = MusicFolderStore(app)

    private val _state = MutableStateFlow(LibraryState(musicFolderLabel = folderStore.label()))
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /** El usuario eligió una carpeta en el selector del sistema. */
    fun setMusicFolder(uri: Uri) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { app.contentResolver.takePersistableUriPermission(uri, read or write) }
            .onFailure { runCatching { app.contentResolver.takePersistableUriPermission(uri, read) } }
        val prefix = MusicPlaces.prefixFromTree(uri)
        val label = readTreeName(uri) ?: MusicPlaces.labelFor(prefix)
        folderStore.save(uri, prefix, label)
        _state.value = _state.value.copy(musicFolderLabel = label)
        refresh()
    }

    /** Vuelve a mostrar todo el teléfono, menos los audios de WhatsApp. */
    fun clearMusicFolder() {
        val uri = folderStore.uri()
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { app.contentResolver.releasePersistableUriPermission(uri, flags) }
        }
        folderStore.clear()
        _state.value = _state.value.copy(musicFolderLabel = "")
        refresh()
    }

    /** El sistema nos avisa de permisos (MainActivity); disparar escaneo al aceptar. */
    fun onPermissionChanged(granted: Boolean) {
        val current = _state.value
        // El permiso se guarda ANTES de escanear. refresh() sale si todavía figura denegado,
        // y si no, Inicio queda vacío aunque el teléfono tenga canciones.
        _state.value = current.copy(hasPermission = granted)
        if (granted && (!current.scannedOnce || current.tracks.isEmpty())) {
            refresh()
        }
    }

    /** Re-escanea la biblioteca (pull para refrescar o al abrir la app la primera vez). */
    fun refresh() {
        // Las descargas online se muestran SIEMPRE (no hace falta permiso de audio);
        // escanear MediaStore solo tiene sentido con el permiso concedido.
        val canScan = _state.value.hasPermission
        _state.value = _state.value.copy(loading = true)
        scope.launch { scanMediaStore(canScan) }
    }

    private suspend fun scanMediaStore(includePhone: Boolean = true) {
        // MediaStore + géneros pueden tardar: fuera del hilo de UI.
        val phone = if (includePhone) {
            withContext(Dispatchers.IO) {
                runCatching { queryAllTracks() }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
        // Descargas online: las de la carpeta privada se fusionan acá; las que
        // cayeron en el árbol elegido (Drive) las muestra el escáner de la carpeta.
        val downloaded = withContext(Dispatchers.IO) {
            downloads?.allEntries().orEmpty()
                .filter { it.treeUri == null }
                .mapNotNull(::entryToTrack)
        }
        val tracks = (phone + downloaded).sortedBy { it.title.lowercase() }

        // Agrupamos en una sola pasada: artistas y álbumes.
        val byArtist = tracks.groupBy { it.artistId }
        val byAlbum = tracks.groupBy { it.albumId }

        val artistList = byArtist.map { (id, list) ->
            val name = list.firstOrNull { it.artist.isNotBlank() }?.artist ?: "<Sin artista>"
            ArtistInfo(id, name, list.size)
        }.sortedBy { it.name.lowercase() }

        val albumList = byAlbum.map { (id, list) ->
            val name = list.firstOrNull { it.album.isNotBlank() }?.album ?: "<Sin álbum>"
            AlbumInfo(id, name, list.first().artist, list.size)
        }.sortedBy { it.name.lowercase() }

        _state.value = _state.value.copy(
            loading = false,
            scannedOnce = true,
            tracks = tracks,
            artists = artistList,
            albums = albumList,
            musicFolderLabel = folderStore.label(),
        )
    }

    /**
     * Entrada del índice de descargas ONLINE → LocalTrack (aparece en "Tu música").
     * Ids sintéticos negativos estables (no pisan ids de MediaStore). El título
     * y el artista son los del índice; el álbum = "Descargas".
     */
    private fun entryToTrack(entry: OnlineDownloader.Entry): LocalTrack? {
        val downloader = downloads ?: return null
        val file = downloader.fileForEntry(entry)
        if (!file.exists() || file.length() < 1024) return null
        val title = entry.title.trim().ifBlank { file.name }
        val artist = entry.artist.trim().ifBlank { "<Sin artista>" }
        return LocalTrack(
            id = stableNegativeId("online|${entry.mediaId}"),
            uri = android.net.Uri.fromFile(file).toString(),
            title = title,
            artist = artist,
            album = "Descargas",
            albumId = DOWNLOADS_ALBUM_ID,
            artistId = stableNegativeId("artist|$artist"),
            durationMs = entry.durationMs ?: 0L,
            trackNo = 0,
            folder = "Descargas",
            fileName = file.name,
            sourceRank = 0,
            year = 0,
            genre = "",
            searchBlob = normalizeForSearch("$title $artist ${file.name} Descargas"),
            fromDownloads = true,
            artUrl = entry.artUrl,
        )
    }

    /** Hash de 64 bits con signo negativo, para ids que no pisan MediaStore. */
    private fun stableNegativeId(key: String): Long {
        var h = 1125899906842597L
        for (c in key) h = 31L * h + c.code
        val positive = h and Long.MAX_VALUE
        return if (positive == 0L) -3L else -positive
    }

    /** Consulta MediaStore (primario + microSD en API 29+ vía VOLUME_EXTERNAL). */
    private fun queryAllTracks(): List<LocalTrack> {
        val chosen = folderStore.prefix()
        val keepChat = chosen.contains("whatsapp", ignoreCase = true)
        // VOLUME_EXTERNAL abarca todos los volúmenes montados (primario + SD)
        // con UNA sola query, en lugar de recorrer volúmenes a mano.
        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val relativeSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.ARTIST_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.DATA)      // ruta completa; en algunos celus viene vacía
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.IS_MUSIC)
            add(MediaStore.Audio.Media.IS_RINGTONE)
            add(MediaStore.Audio.Media.IS_ALARM)
            add(MediaStore.Audio.Media.IS_NOTIFICATION)
            if (relativeSupported) add(MediaStore.Audio.Media.RELATIVE_PATH)
        }.toTypedArray()
        // is_pending / is_trashed existen desde Q; antes no hace falta filtrar nada raro.
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "${MediaStore.Audio.Media.IS_PENDING}=0 AND ${MediaStore.Audio.Media.IS_TRASHED}=0"
        else null

        val genres = runCatching { loadGenreByTrackId() }.getOrDefault(emptyMap())
        val result = ArrayList<LocalTrack>(512)
        app.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            MediaStore.Audio.Media.TITLE + " COLLATE NOCASE",
        )?.use { cursor ->
            val colId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val colTitle = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val colArtist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val colAlbum = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val colAlbumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val colArtistId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val colDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val colTrack = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val colData = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val colDisplay = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val colYear = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val colMusic = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC)
            val colRingtone = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RINGTONE)
            val colAlarm = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_ALARM)
            val colNotification = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_NOTIFICATION)
            val colRelative = if (relativeSupported) {
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                -1
            }

            while (cursor.moveToNext()) {
                val durationMs = cursor.getLong(colDuration)
                // Duración 0 = el teléfono todavía no la calculó (pasa seguido con FLAC).
                // Solo se descartan los audios cortos de los que sí se conoce la duración.
                if (durationMs in 1..24_999) continue

                val id = cursor.getLong(colId)
                val dataPath = cursor.getString(colData).orEmpty()
                val relativePath = if (colRelative >= 0) cursor.getString(colRelative).orEmpty() else ""
                val displayName = cursor.getString(colDisplay).orEmpty()
                val fileName = displayName.ifBlank { dataPath.substringAfterLast('/') }
                if (fileName.isBlank()) continue
                val place = "$relativePath $dataPath"
                if (chosen.isNotEmpty() && !MusicPlaces.inChosenFolder(place, chosen)) continue
                if (!keepChat && MusicPlaces.reject(
                        path = place,
                        fileName = fileName,
                        isRingtone = cursor.getInt(colRingtone) == 1,
                        isAlarm = cursor.getInt(colAlarm) == 1,
                        isNotification = cursor.getInt(colNotification) == 1,
                        isMusic = cursor.getInt(colMusic) == 1,
                    )
                ) {
                    continue
                }
                val folder = relativePath.trim('/').substringAfterLast('/')
                    .ifBlank { dataPath.substringBeforeLast('/').substringAfterLast('/') }

                // Tags (MediaStore los lee de ID3). Si están sucios/vacíos, salvamos con
                // nombre de archivo y carpeta (música mal etiquetada también es buscable).
                val tagTitle = cursor.getString(colTitle).takeUnless { it.isNullOrBlank() } ?: ""
                val tagArtist = cursor.getString(colArtist).takeUnless {
                    it.isNullOrBlank() || it.equals("<unknown>", ignoreCase = true)
                } ?: ""
                val tagAlbum = cursor.getString(colAlbum).takeUnless { it.isNullOrBlank() } ?: ""

                val fileArtist = deriveArtistFromFileName(fileName)
                // El título que trae el sistema puede ser el nombre del archivo (Android
                // lo inventa cuando el audio no tiene tags). Para nuestras descargas eso
                // arrastra el sufijo interno "[youtube_xxxx]": hay que sacarlo y separar
                // bien "Artista - Título".
                val ownDownload = TrackNames.isOwnDownload(fileName)
                val rawTitle = tagTitle.ifBlank { deriveTitleFromFileName(fileName) }
                val title: String
                val artist: String
                if (ownDownload) {
                    val (ownArtist, ownTitle) = TrackNames.splitArtistTitle(TrackNames.stripMarker(rawTitle))
                    title = ownTitle.ifBlank { TrackNames.stripMarker(rawTitle) }
                    artist = tagArtist.ifBlank { ownArtist ?: "<Sin artista>" }
                } else {
                    title = TrackNames.stripMarker(rawTitle)
                    artist = tagArtist.ifBlank { fileArtist ?: "<Sin artista>" }
                }
                val album = tagAlbum.ifBlank { folder.ifBlank { "<Sin álbum>" } }
                val genre = cleanGenre(genres[id])

                val uriStr = collection.buildUpon()
                    .appendPath(java.lang.Long.toString(id))
                    .toString()

                result.add(
                    LocalTrack(
                        id = id,
                        uri = uriStr,
                        title = title,
                        artist = artist,
                        album = album,
                        albumId = cursor.getLong(colAlbumId),
                        artistId = cursor.getLong(colArtistId),
                        durationMs = durationMs,
                        trackNo = cursor.getInt(colTrack) % 1000, // el número real de pista (TRACK trae álbum*1000+n)
                        folder = folder,
                        fileName = fileName,
                        sourceRank = MusicPlaces.rank(place),
                        year = cursor.getInt(colYear),
                        genre = genre,
                        searchBlob = normalizeForSearch("$title $artist $album $folder $fileName $genre"),
                    ),
                )
            }
        }
        // Si el usuario eligió una carpeta, esa manda. MediaStore no ve Google Drive
        // ni varios FLAC descargados, así que se listan los archivos de la carpeta.
        val tree = folderStore.uri() ?: return result
        rememberTreeName()
        val fromTree = runCatching { FolderTreeScanner(app).scan(tree) }.getOrDefault(emptyList())
        if (fromTree.isEmpty()) {
            // Carpeta local: MediaStore ya filtró por la ruta. Drive sin archivos: no mostrar el resto del teléfono.
            return if (chosen.isNotEmpty()) result else emptyList()
        }
        val byName = result.associateBy { it.fileName.lowercase() }
        // Las descargas que caen en la carpeta elegida (Drive o local) las ve el
        // escáner de la carpeta, pero la PORTADA la tenemos en el índice de
        // descargas (la URL de la fuente): se la pegamos acá.
        val artByFile = downloads?.allEntries().orEmpty()
            .mapNotNull { entry -> entry.artUrl?.let { entry.fileName.lowercase() to it } }
            .toMap()
        return fromTree.map { item ->
            val known = byName[item.fileName.lowercase()]
            val base = if (known != null) known.copy(sourceRank = 0) else item
            // Prioridad: portada de la fuente en línea > carátula de carpeta > MediaStore.
            val art = base.artUrl
                ?: artByFile[item.fileName.lowercase()]
                ?: item.artUrl
            if (art != null) base.copy(artUrl = art) else base
        }.sortedBy { it.title.lowercase() }
    }

    /** Nombre visible de la carpeta elegida (Drive incluido, donde no hay ruta Music/). */
    private fun readTreeName(uri: Uri): String? {
        val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, docId)
        return app.contentResolver.query(
            doc,
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() }
    }

    private fun rememberTreeName() {
        val uri = folderStore.uri() ?: return
        val name = readTreeName(uri) ?: return
        if (name != folderStore.label()) folderStore.save(uri, folderStore.prefix(), name)
    }

    /**
     * Mapa id de audio → nombre de género. Una pasada por la tabla de géneros.
     * Si un archivo no tiene etiqueta, simplemente no aparece: la UI lo trata como sin género.
     */
    private fun loadGenreByTrackId(): Map<Long, String> {
        val map = HashMap<Long, String>()
        val genresUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Genres.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
        }
        app.contentResolver.query(
            genresUri,
            arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)
            while (cursor.moveToNext()) {
                val genreId = cursor.getLong(idCol)
                val name = cleanGenre(cursor.getString(nameCol))
                if (name.isEmpty()) continue
                val members = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
                runCatching {
                    app.contentResolver.query(
                        members,
                        arrayOf(MediaStore.Audio.Media._ID),
                        null,
                        null,
                        null,
                    )?.use { member ->
                        val trackCol = member.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        while (member.moveToNext()) {
                            map.putIfAbsent(member.getLong(trackCol), name)
                        }
                    }
                }
            }
        }
        return map
    }

    private fun cleanGenre(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return ""
        if (value.equals("<unknown>", true) || value.equals("unknown", true)) return ""
        return value
    }

    private fun deriveTitleFromFileName(fileName: String): String {
        var s = TrackNames.stripMarker(fileName.substringBeforeLast('.'))
        // Sufijo de pista: "01", "01.", "01 -", "01_"
        s = s.replaceFirst(Regex("""^\d{1,3}[\s._-]+"""), "")
        // Formato "Artista - Título": el título es lo de después
        val idx = s.indexOf(" - ")
        if (idx > 0 && idx < s.length - 3) s = s.substring(idx + 3)
        return s.trim()
    }

    private fun deriveArtistFromFileName(fileName: String): String? {
        val s = TrackNames.stripMarker(fileName.substringBeforeLast('.'))
            .replaceFirst(Regex("""^\d{1,3}[\s._-]+"""), "")
        val idx = s.indexOf(" - ")
        return if (idx > 0 && idx < s.length - 3) s.take(idx).trim() else null
    }

    companion object {
        /** AlbumId sintético compartido por las descargas online (agrupadas en "Descargas"). */
        const val DOWNLOADS_ALBUM_ID = -4242L
        private val combiningMarkRegex = Regex("\\p{M}+")

        /** Minúsculas + saca tildes/diacríticos: "Canción" busca igual que "cancion". */
        fun normalizeForSearch(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(combiningMarkRegex, "")
    }
}
