package com.resonar.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.StatFs
import android.util.Log
import android.provider.DocumentsContract
import com.resonar.app.online.OnlineTrack
import com.resonar.app.online.StreamHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * DESCARGADOR DE TEMAS EN LÍNEA.
 *
 * El botón de descarga en los resultados "En línea". DÓNDE SE GUARDA:
 *  - Si el usuario eligió una carpeta de música (Ajustes → Carpeta), la
 *    descarga se crea en una subcarpeta "Descargas" DENTRO de ese árbol,
 *    vía SAF. Así:
 *      1) aparece en "Tu música" (la carpeta se escanea entera, como todo
 *         lo demás de tu biblioteca),
 *      2) se sube a tu nube (si es Drive) y se ve también en tu otro
 *         teléfono eligiendo la misma carpeta,
 *      3) tu auto-descarga la procesa igual que a los demás archivos.
 *  - Si no hay carpeta elegida (o el árbol falla), cae a la carpeta privada
 *    de la app como hace siempre: nunca se pierde el intento.
 *
 * QUÉ OFRECE LA APP ASÍ: un tema descargado
 *  - suena DIRECTO de tu carpeta (sin resolver stream, sin internet),
 *  - sobrevive a reinicios y cortes de conexión,
 *  - muestra ✓ en su fila en vez del botón de descarga.
 *
 * Guardas (las mismas ideas del descargador automático):
 *  - UN archivo a la vez (Mutex): red y RAM contenidas en el Motorola.
 *  - espacio libre < 300 MB → se niega honestamente.
 *  - descarga en .parte y recién ahí pasa a la carpeta: nunca un archivo a la mitad.
 *  - avisa si está usando datos móviles (el usuario decide tocando el botón,
 *    la descarga no se frena a mitad de camino).
 */
class OnlineDownloader(private val app: Context) {

    /** Entrada del índice: lo mínimo para reproducir y redibujar la fila. */
    @Serializable
    data class Entry(
        val mediaId: String,
        val sourceId: String,
        val externalId: String,
        val title: String,
        val artist: String,
        val durationMs: Long?,
        val artUrl: String?,
        val fileName: String,
        val sizeBytes: Long,
        /** Uri del documento SAF (carpeta elegida). null = copia privada de la app. */
        val treeUri: String? = null,
    )

    private val dir: File =
        File(app.getExternalFilesDir(null) ?: app.filesDir, "descargas/online").apply { mkdirs() }

    /** La carpeta elegida por el usuario en Ajustes; destino preferido de las descargas. */
    private val folderStore = MusicFolderStore(app)

    /** Id del documento "Descargas" dentro del árbol SAF (se resuelve una vez por proceso). */
    @Volatile
    private var cachedDescargasDocId: String? = null

    private val indexFile = File(app.filesDir, "descargas_online.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var index: List<Entry> = loadIndex()

    /**
     * Al arrancar: se purgan las entradas cuyo archivo ya no está. Si el usuario
     * borró el tema (o el sistema lo borró), el registro quedó huérfano y el
     * buscador lo seguía marcando como "descargado" sin dejar volver a bajarlo.
     */
    init {
        val alive = index.filter { entry ->
            when {
                entry.treeUri == null -> {
                    // Copia privada de la app: se comprueba el archivo en disco.
                    val file = File(dir, entry.fileName)
                    file.exists() && file.length() > 1024
                }
                else -> {
                    val uri = Uri.parse(entry.treeUri)
                    // Árbol LOCAL: se comprueba al instante, sin red.
                    // Nube (Drive): comprobarlo sería una llamada de red al arrancar,
                    // así que se deja pasar; se corrige al tocar "Descargar"
                    // (documentExists) y al borrar.
                    if (!MusicPlaces.isLocalSaf(uri.authority)) true else documentExists(uri)
                }
            }
        }
        if (alive.size != index.size) {
            Log.i("ResonarDown", "Índice purgado: ${index.size - alive.size} descarga(s) sin archivo")
            index = alive
            persist()
        }
    }

    /** Un archivo por vez: el descargador automático y este comparten red/disco. */
    private val mutex = Mutex()

    // ── Consultas (sin red) ────────────────────────────────────────────────

    fun downloadedIds(): Set<String> = index.map { it.mediaId }.toSet()

    /** Todas las descargas registradas (para que la biblioteca las muestre). */
    fun allEntries(): List<Entry> = index.toList()

    /** Archivo en disco de una entrada del índice. */
    fun fileForEntry(entry: Entry): File = File(dir, entry.fileName)

    /** Entrada por nombre de archivo (la biblioteca solo guarda el nombre). */
    fun entryByFileName(name: String): Entry? =
        index.firstOrNull { it.fileName.equals(name, ignoreCase = true) }

    /** ¿Carpeta elegida? Si sí, las descargas van ahí; si no, a la carpeta privada. */
    fun usingDriveFolder(): Boolean = folderStore.uri() != null

    /** Borra la descarga (archivo + índice). false si no estaba. Va a la papelera de Drive si aplica. */
    fun removeDownload(mediaId: String): Boolean {
        val entry = index.firstOrNull { it.mediaId == mediaId } ?: return false
        val treeUri = entry.treeUri
        if (treeUri != null) {
            runCatching {
                DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(treeUri))
            }.onFailure { Log.w("ResonarDown", "No pude borrar el documento: ${it.message}") }
        } else {
            runCatching { File(dir, entry.fileName).delete() }
                .onFailure { Log.w("ResonarDown", "No pude borrar el archivo: ${it.message}") }
        }
        index = index.filter { it.mediaId != mediaId }
        persist()
        Log.i("ResonarDown", "Descarga eliminada: ${entry.title}")
        return true
    }

    fun localUriFor(track: OnlineTrack): Uri? {
        val entry = index.firstOrNull { it.mediaId == track.mediaId } ?: return null
        entry.treeUri?.let { treeUri ->
            val uri = Uri.parse(treeUri)
            // El documento puede haber sido borrado (desde el sistema o desde Drive):
            // si ya no está, el tema NO está descargado y hay que volver a bajarlo.
            return if (documentExists(uri)) uri else null
        }
        val file = File(dir, entry.fileName)
        return if (file.exists() && file.length() > 1024) Uri.fromFile(file) else null
    }

    /**
     * ¿El documento SAF sigue existiendo?
     *
     * - Árbol LOCAL: se comprueba el archivo en disco (instantáneo y sin red).
     * - Nube (Drive): una consulta al proveedor. Si el proveedor falla por red se
     *   asume que el archivo sigue (preferimos no duplicar descargas).
     */
    private fun documentExists(uri: Uri): Boolean {
        localFileFor(uri)?.let { return it.exists() }
        return try {
            app.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { cursor -> cursor.moveToFirst() } == true
        } catch (e: java.io.FileNotFoundException) {
            false // el documento ya no está
        } catch (e: Exception) {
            true // error del proveedor: se supone que el archivo sigue
        }
    }

    /**
     * Archivo en disco de un documento del almacenamiento interno ("primary:carpeta/x.mp3").
     * null si no es de ese proveedor (o sea, si es Drive u otra nube).
     */
    private fun localFileFor(uri: Uri): File? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        if (!docId.startsWith("primary:")) return null
        return File("/storage/emulated/0", docId.removePrefix("primary:"))
    }

    // ── Descarga ───────────────────────────────────────────────────────────

    /**
     * Baja el tema y lo suma al índice. onProgress recibe el porcentaje 0-100,
     * o -1 si el servidor no informa el tamaño total (indeterminado).
     * Lanza excepción con mensaje claro si algo falla (la UI la muestra).
     */
    suspend fun download(
        track: OnlineTrack,
        resolve: suspend (OnlineTrack) -> StreamHandle,
        onProgress: (Int) -> Unit,
    ): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            // ¿Ya está? No duplicar (el botón igual se deshabilita, esto es defensa).
            if (localUriFor(track) != null) return@withContext

            val handle = resolve(track)
            val ext = when (handle.mimeType) {
                "audio/mpeg" -> "mp3"
                "audio/mp4" -> "m4a"
                "audio/webm" -> "webm"
                "audio/ogg" -> "ogg"
                "audio/flac" -> "flac"
                else -> "mp3"
            }
            val safeBase = "${track.artist} - ${track.title}"
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(120)
            val unique = "${track.sourceId}_${track.externalId.take(12)}"
            val fileName = "$safeBase [$unique].$ext"
            val temp = File(dir, "$fileName.parte")

            try {
                val conn = URL(handle.uri.toString()).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", com.resonar.app.online.Http.USER_AGENT)
                    // REANUDACIÓN: si quedó un .parte de un intento anterior (la red
                    // corta de este teléfono lo hace habitual), pedimos desde donde
                    // quedó con Range. El CDN de YouTube e IA aceptan 206.
                    val already = if (temp.exists() && temp.length() > 0) temp.length() else 0L
                    if (already > 0) conn.setRequestProperty("Range", "bytes=$already-")
                    var offset = already
                    var code = conn.responseCode
                    if (already > 0 && code != 206) {
                        // No soporta reanudar (o el .parte ya no sirve): arrancar de cero.
                        temp.delete()
                        offset = 0L
                        code = conn.responseCode
                    }
                    if (code !in 200..299) throw IllegalStateException("HTTP $code al descargar")
                    val total = conn.contentLengthLong.takeIf { it > 0 }?.let { it + offset }
                    var copied = offset
                    var lastPercent = -1
                    // Append SOLO si estamos reanudando de verdad (206).
                    val append = offset > 0 && code == 206
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(temp, append).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (total != null) {
                                    val percent = ((copied * 100) / total).toInt()
                                    if (percent != lastPercent && percent in 0..100) {
                                        lastPercent = percent
                                        onProgress(percent)
                                    }
                                } else if (copied / (512 * 1024) != (copied - read) / (512 * 1024)) {
                                    onProgress(-1) // aviso cada 512 KB: sigue vivo
                                }
                            }
                        }
                    }
                    if (temp.length() < 1024) throw IllegalStateException("Descarga vacía")
                    val size = temp.length()
                    // Mover a su carpeta final: subcarpeta "Descargas" del árbol SAF
                    // elegido, o la carpeta privada como fallback.
                    val tree = folderStore.uri()
                    val mime = handle.mimeType ?: mimeForExt(ext)
                    val created = if (tree != null) {
                        moveToDriveFolder(tree, fileName, mime, temp)
                    } else {
                        null
                    }
                    if (created != null) {
                        addToIndex(
                            Entry(
                                mediaId = track.mediaId,
                                sourceId = track.sourceId,
                                externalId = track.externalId,
                                title = track.title,
                                artist = track.artist,
                                durationMs = track.durationMs,
                                artUrl = track.artUrl,
                                fileName = fileName,
                                sizeBytes = size,
                                treeUri = created.toString(),
                            ),
                        )
                        Log.i("ResonarDown", "Descargado a tu carpeta: ${track.title}")
                    } else {
                        val file = File(dir, fileName)
                        if (file.exists()) file.delete()
                        if (!temp.renameTo(file)) {
                            temp.copyTo(file, overwrite = true)
                            temp.delete()
                        }
                        addToIndex(
                            Entry(
                                mediaId = track.mediaId,
                                sourceId = track.sourceId,
                                externalId = track.externalId,
                                title = track.title,
                                artist = track.artist,
                                durationMs = track.durationMs,
                                artUrl = track.artUrl,
                                fileName = file.name,
                                sizeBytes = file.length(),
                            ),
                        )
                        Log.i("ResonarDown", "Descargado (privada): ${track.title} (${file.length() / 1024} KB)")
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                // NO borramos el .parte: queda para reanudar en el próximo intento.
                throw e
            }
        }
    }

    /**
     * Copia el temporal ya descargado a la subcarpeta "Descargas" del árbol SAF elegido.
     * Devuelve la Uri del documento creado, o null si algo falló (el llamador usa la
     * carpeta privada como fallback). El temporal se borra solo en el caso exitoso.
     */
    private fun moveToDriveFolder(tree: Uri, fileName: String, mime: String, temp: File): Uri? {
        val cr = app.contentResolver
        val dirId = ensureDescargasDoc(tree) ?: return null
        val parentUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        val created = runCatching {
            DocumentsContract.createDocument(cr, parentUri, mime, fileName)
        }.getOrNull() ?: return null
        return try {
            val out = cr.openOutputStream(created)
                ?: throw IllegalStateException("No se pudo abrir el documento creado")
            out.use { output ->
                temp.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
            }
            temp.delete()
            created
        } catch (e: Exception) {
            Log.w("ResonarDown", "Fallo copiando a Drive: ${e.message}; cae a carpeta privada")
            runCatching { DocumentsContract.deleteDocument(cr, created) }
            null
        }
    }

    /** Crea (una vez) o encuentra la subcarpeta "Descargas" dentro del árbol SAF. */
    private fun ensureDescargasDoc(tree: Uri): String? {
        cachedDescargasDocId?.let { return it }
        val cr = app.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val parentUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        cr.query(parentUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR &&
                    cursor.getString(nameCol).equals("Descargas", ignoreCase = true)
                ) {
                    cachedDescargasDocId = cursor.getString(idCol)
                    return cachedDescargasDocId
                }
            }
        }
        // No existe: crearla.
        val created = runCatching {
            DocumentsContract.createDocument(
                cr,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                "Descargas",
            )
        }.getOrNull() ?: return null
        return runCatching { DocumentsContract.getDocumentId(created) }
            .getOrNull()
            ?.also { cachedDescargasDocId = it }
    }

    private fun mimeForExt(ext: String): String = when (ext) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "webm" -> "audio/webm"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    // ── Guardas e índice ───────────────────────────────────────────────────

    /** ¿Está usando datos móviles? La UI avisa antes de arrancar. */
    fun isOnMeteredNetwork(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Espacio libre insuficiente → null si está bien, mensaje si no. */
    fun spaceBlocker(): String? {
        val stat = StatFs(dir.absolutePath)
        return if (stat.availableBytes < 300L * 1024 * 1024) {
            "Poco espacio en el teléfono (300 MB libres)"
        } else {
            null
        }
    }

    /**
     * Saca una entrada del índice SIN tocar el archivo.
     * Para cuando el archivo YA fue borrado (por el sistema o por Drive) y solo
     * queda el registro: si no, el tema seguiría apareciendo como "descargado"
     * en el buscador y no se podría volver a bajar.
     */
    fun forgetDownload(mediaId: String): Boolean {
        val entry = index.firstOrNull { it.mediaId == mediaId } ?: return false
        index = index.filter { it.mediaId != mediaId }
        persist()
        Log.i("ResonarDown", "Registro de descarga olvidado: ${entry.title}")
        return true
    }

    private fun addToIndex(entry: Entry) {
        index = index.filter { it.mediaId != entry.mediaId } + entry
        persist()
    }

    private fun persist() {
        runCatching {
            indexFile.writeText(json.encodeToString(index))
        }.onFailure { Log.w("ResonarDown", "No pude guardar el índice: ${it.message}") }
    }

    private fun loadIndex(): List<Entry> = runCatching {
        if (!indexFile.exists()) return emptyList()
        json.decodeFromString<List<Entry>>(indexFile.readText())
    }.getOrDefault(emptyList())
}
