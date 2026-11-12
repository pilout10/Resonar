package com.resonar.app.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.util.ArrayDeque

/**
 * Lee una carpeta elegida con el selector del sistema cuando MediaStore no la indexó.
 * No abre los archivos: solo nombre y tipo. Tope de 1500 temas para no llenar la RAM.
 */
class FolderTreeScanner(private val app: Context) {

    fun scan(tree: Uri): List<LocalTrack> {
        val root = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return emptyList()
        val prefix = MusicPlaces.prefixFromTree(tree)
        val keepChat = prefix.contains("whatsapp", ignoreCase = true)
        val albumName = readName(tree) ?: prefix.substringAfterLast('/').ifBlank { "Carpeta" }
        val out = ArrayList<LocalTrack>(64)
        val stack = ArrayDeque<Pair<String, Int>>()
        stack.add(root to 0)
        while (stack.isNotEmpty() && out.size < MAX_TRACKS) {
            val (docId, depth) = stack.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            // Drive a veces rechaza el tipo, o devuelve la carpeta vacía mientras carga.
            val cursor = queryChildren(children) ?: continue
            cursor.use { rows ->
                val idCol = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = rows.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idCol < 0 || nameCol < 0) return@use

                // UNA sola pasada: se listan los hijos en memoria y se usan dos
                // veces (carátula de la carpeta y audios). No se vuelve a recorrer
                // el cursor: algunos proveedores no lo reponen y se perdían los
                // archivos de la carpeta.
                val entries = ArrayList<Entry>(32)
                while (rows.moveToNext() && entries.size < MAX_ENTRIES) {
                    val id = rows.getString(idCol) ?: continue
                    val name = rows.getString(nameCol).orEmpty()
                    val mime = if (mimeCol >= 0) rows.getString(mimeCol).orEmpty() else ""
                    entries.add(Entry(id, name, mime))
                }

                // Carátula de la carpeta: es el estándar de la industria (cover.jpg,
                // folder.jpg, front.jpg al lado de los temas). Sale del MISMO listado,
                // así que no cuesta nada (ni una consulta extra).
                val folderArtId = entries.firstOrNull { !isDirectory(it.mime) && isFolderArt(it.name, it.mime) }?.id
                val folderArtUri = folderArtId?.let {
                    DocumentsContract.buildDocumentUriUsingTree(tree, it).toString()
                }

                for (entry in entries) {
                    if (out.size >= MAX_TRACKS) break
                    val childId = entry.id
                    val name = entry.name
                    val mime = entry.mime
                    if (isDirectoryEntry(tree, childId, name, mime)) {
                        if (depth < MAX_DEPTH && !name.startsWith(".")) stack.add(childId to depth + 1)
                        continue
                    }
                    val fromId = childId.substringAfterLast('/').substringAfterLast(':')
                    if (!isAudio(mime, name) && !isAudio("", fromId)) continue
                    if (!keepChat && MusicPlaces.reject(childId, name, false, false, false, true)) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                    val fileLabel = name.ifBlank { fromId }
                    // "Artista - Tema [youtube_xxxxxxxxxxxx].ext" (descargas de Resonar)
                    // o "Tema - Artista.ext" (biblioteca típica de tu carpeta, como tu Drive):
                    // el sufijo interno marca nuestras descargas; los demás nombres se
                    // separan a la inversa porque TU biblioteca usa "Tema - Artista".
                    val baseLabel = fileLabel.substringBeforeLast('.')
                    val isOwnDownload = TrackNames.isOwnDownload(baseLabel)
                    val pretty = TrackNames.stripMarker(baseLabel)
                    val splitAt = pretty.indexOf(" - ")
                    val title: String
                    val artist: String
                    if (splitAt > 0 && splitAt < pretty.length - 3) {
                        if (isOwnDownload) {
                            artist = pretty.take(splitAt).trim()
                            title = pretty.substring(splitAt + 3).trim().ifBlank { pretty }
                        } else {
                            // "Tema - Artista": el artista va después del guion.
                            title = pretty.take(splitAt).trim()
                            artist = pretty.substring(splitAt + 3).trim().ifBlank { "<Sin artista>" }
                        }
                    } else {
                        title = pretty.ifBlank { baseLabel.ifBlank { fileLabel } }
                        artist = "<Sin artista>"
                    }
                    // Etiquetas reales solo en árboles LOCALES (disco): en Drive cada
                    // archivo se transmitiría por red. En remoto usamos la deriva de nombre.
                    val readTags = isLocalSafFolder(tree)
                    val tags = if (readTags) readTagsSafe(uri) else AudioTags()
                    val finalTitle = tags.title?.takeIf { it.isNotBlank() } ?: title
                    val finalArtist = tags.artist
                        ?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
                        ?: artist
                    val finalAlbum = tags.album?.takeIf { it.isNotBlank() } ?: albumName
                    out.add(
                        LocalTrack(
                            id = stableId(childId),
                            uri = uri.toString(),
                            title = finalTitle,
                            artist = finalArtist,
                            album = finalAlbum,
                            albumId = GroupIds.album(finalAlbum),
                            artistId = GroupIds.artist(finalArtist),
                            durationMs = tags.durationMs,
                            trackNo = 0,
                            folder = albumName,
                            fileName = fileLabel,
                            sourceRank = 0,
                            year = 0,
                            genre = "",
                            searchBlob = LocalLibraryRepository.normalizeForSearch("$finalTitle $finalArtist $finalAlbum $name"),
                            artUrl = folderArtUri,
                        ),
                    )
                }
            }
        }
        Log.i(TAG, "canciones=${out.size} carpeta=$albumName")
        return out.sortedBy { it.title.lowercase() }
    }

    /**
     * ¿El árbol SAF elegido apunta al almacenamiento LOCAL del teléfono?
     * Los documentos locales (proveedor Media/External Storage) se leen en disco y
     * las etiquetas son baratas; los remotos (Drive y otras nubes por SAF) transmiten
     * cada archivo por red y leer tags sería carísimo.
     */
    private fun isLocalSafFolder(tree: Uri): Boolean = MusicPlaces.isLocalSaf(tree.authority)

    /**
     * Lee las etiquetas del audio SIN decodificar audio: MediaMetadataRetriever nativo.
     * Si no hay tags (nuestras descargas), si el formato no se entiende, o si el
     * proveedor falla, devuelve todo vacío y se usa el nombre de archivo.
     * También trae la DURACIÓN: en carpetas locales se lee del mismo archivo, así
     * que las canciones muestran su tiempo real en vez de 0:00.
     */
    private fun readTagsSafe(uri: Uri): AudioTags {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            app.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                AudioTags(
                    title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    durationMs = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
                    )?.toLongOrNull()?.takeIf { it > 0 } ?: 0L,
                )
            } ?: AudioTags()
        } catch (e: Exception) {
            // No es un error: archivos sin tag / formatos raros / proveedor lento → fallback al nombre.
            Log.d(TAG, "Sin tags legibles: ${e.message}")
            AudioTags()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Drive suele contestar vacío la primera vez (está bajando el listado) o rechazar
     * la columna del tipo. Se reintenta y, si hace falta, se pide sin esa columna.
     */
    private fun queryChildren(children: Uri): Cursor? {
        var pending: Cursor? = null
        repeat(4) { attempt ->
            pending?.close()
            pending = openChildren(children) ?: return null
            val cursor = pending ?: return null
            val loading = runCatching {
                cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)
            }.getOrDefault(false)
            val count = runCatching { cursor.count }.getOrDefault(-1)
            if (count != 0) return cursor
            if (!loading && attempt >= 1) return cursor
            try {
                Thread.sleep(400L)
            } catch (_: InterruptedException) {
                return cursor
            }
        }
        return pending
    }

    private fun openChildren(children: Uri): Cursor? {
        val full = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val namesOnly = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        return runCatching { app.contentResolver.query(children, full, null, null, null) }.getOrNull()
            ?: runCatching { app.contentResolver.query(children, namesOnly, null, null, null) }.getOrNull()
            ?: runCatching { app.contentResolver.query(children, null, null, null, null) }.getOrNull()
    }

    private fun isDirectory(mime: String): Boolean {
        val value = mime.lowercase()
        return value == DocumentsContract.Document.MIME_TYPE_DIR ||
            value.endsWith("directory") ||
            "folder" in value
    }

    /**
     * ¿Es esta entrada una carpeta?
     *
     * El proveedor de Drive devuelve la columna de tipo, pero el del almacenamiento
     * interno del Android NO la devuelve en las consultas por árbol: ahí el tipo
     * viene vacío y, si se lo toma literal, NUNCA se entra a una subcarpeta (y el
     * escaneo se queda solo con la primera carpeta). Cuando falta el tipo, se
     * pregunta al proveedor: si el documento tiene hijos, es una carpeta.
     */
    private fun isDirectoryEntry(tree: Uri, childId: String, name: String, mime: String): Boolean {
        if (mime.isNotBlank()) return isDirectory(mime)
        // Sin tipo: un nombre con extensión es un archivo; sin extensión, se pregunta.
        if (name.contains('.')) return false
        return runCatching {
            app.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, childId),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { true } ?: false
        }.getOrDefault(false)
    }

    /**
     * FLAC en Drive suele llegar como audio/flac, application/x-flac o solo con la extensión.
     * No alcanza con confiar en MediaStore: muchos no quedan marcados como música.
     */
    private fun isAudio(mime: String, name: String): Boolean {
        val value = mime.lowercase()
        if (value.startsWith("audio/")) return true
        if ("flac" in value || "alac" in value || "ogg" in value || "opus" in value) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXT
    }

    private fun readName(tree: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        return app.contentResolver.query(
            doc,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }?.takeIf { it.isNotBlank() }
    }

    /** Id estable y negativo para no pisar un id de MediaStore. */
    private fun stableId(docId: String): Long {
        var hash = 1125899906842597L
        for (char in docId) hash = 31L * hash + char.code
        val positive = hash and Long.MAX_VALUE
        return if (positive == 0L) -1L else -positive
    }

    private companion object {
        const val TAG = "ResonarFolder"
        const val MAX_TRACKS = 1500
        const val MAX_DEPTH = 8
        /** Tope de hijos leídos por carpeta (para no inflar la RAM con una lista gigante). */
        const val MAX_ENTRIES = 4000
        val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "alac", "aiff", "aif")

        /** Nombres que la industria usa para la carátula de una carpeta. */
        val FOLDER_ART_NAMES = setOf(
            "cover", "folder", "front", "album", "albumart", "albumartsmall", "thumb", "artwork",
        )
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp")

        /** ¿Este archivo de la carpeta es la carátula? */
        fun isFolderArt(name: String, mime: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (mime.startsWith("image/")) return FOLDER_ART_NAMES.contains(name.substringBeforeLast('.').trim().lowercase())
            if (!IMAGE_EXT.contains(ext)) return false
            return FOLDER_ART_NAMES.contains(name.substringBeforeLast('.').trim().lowercase())
        }
    }
}

/** Etiquetas leídas del archivo (todo vacío si el archivo no las tiene). */
private data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
)

/** Un hijo de una carpeta (documento del árbol SAF). */
private data class Entry(val id: String, val name: String, val mime: String)
