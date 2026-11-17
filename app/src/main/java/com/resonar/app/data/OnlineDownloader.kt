package com.resonar.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.util.Log
import android.provider.DocumentsContract
import android.provider.MediaStore
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
 *  - Si el usuario prendió "Guardar descargas en Music/" (Ajustes) y el
 *    Android es 10 o más nuevo, la descarga va a Music/Resonar en el
 *    almacenamiento COMPARTIDO vía MediaStore: la ven otros reproductores
 *    (YouTube Music, VLC…) y sobrevive a desinstalar la app.
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
/**
 * Señal de pausa pedida por el usuario: corta la descarga por el `.parte`
 * pero NO es un error — el servicio lo sabe y marca el trabajo PAUSED.
 */
class DownloadPausedException : Exception("Pausada")

class OnlineDownloader(
    private val app: Context,
    /** ¿El usuario pidió destino público Music/Resonar? Se lee en cada descarga (cambia desde Ajustes). */
    private val saveToPublicMusic: () -> Boolean = { false },
) {

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
        /**
         * Uri de MediaStore (content://media/...) cuando el destino es la
         * carpeta pública Music/Resonar (Etapa 3 parte 2b). Sobrevive a la
         * desinstalación y la ven otros reproductores.
         */
        val mediaStoreUri: String? = null,
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
                entry.mediaStoreUri != null -> {
                    // Fila de MediaStore (Music/Resonar): se pregunta al ContentProvider.
                    mediaStoreRowExists(Uri.parse(entry.mediaStoreUri))
                }
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
        if (entry.mediaStoreUri != null) {
            // Music/Resonar: la app creó la fila, puede borrarla directa
            // (borrar la PROPIA contribución no pide permiso ni diálogo).
            runCatching {
                app.contentResolver.delete(Uri.parse(entry.mediaStoreUri), null, null)
            }.onFailure { Log.w("ResonarDown", "No pude borrar de MediaStore: ${it.message}") }
        } else if (treeUri != null) {
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
        // Destino público (Music/Resonar): la uri de MediaStore ES el archivo.
        entry.mediaStoreUri?.let { msUri ->
            val uri = Uri.parse(msUri)
            // Si la fila ya no está (la borró otro reproductor o el usuario),
            // el tema NO está descargado: hay que volver a bajarlo.
            return if (mediaStoreRowExists(uri)) uri else null
        }
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

    /** La extensión sale de la URL si el servidor no dice el tipo MIME. */
    private fun guessExtFromUrl(url: String): String {
        val path = android.net.Uri.parse(url).path?.lowercase().orEmpty()
        return when {
            path.endsWith(".mp3") -> "mp3"
            path.endsWith(".m4a") -> "m4a"
            path.endsWith(".webm") -> "webm"
            path.endsWith(".ogg") || path.endsWith(".opus") -> "ogg"
            path.endsWith(".flac") -> "flac"
            path.endsWith(".wav") -> "wav"
            else -> "mp3"
        }
    }

    // ── Descarga ───────────────────────────────────────────────────────────

    /**
     * Baja el tema y lo suma al índice. onProgress recibe el porcentaje 0-100,
     * o -1 si el servidor no informa el tamaño total (indeterminado).
     *
     * [choice] es la calidad que eligió el usuario (si la fuente ofrece más de
     * una). Si viene, se baja ESA URL directamente; si no, se resuelve el
     * stream como siempre.
     * Lanza excepción con mensaje claro si algo falla (la UI la muestra).
     */
    suspend fun download(
        track: OnlineTrack,
        resolve: suspend (OnlineTrack) -> StreamHandle,
        onProgress: (Int) -> Unit,
        choice: com.resonar.app.online.AudioOption? = null,
        shouldCancel: () -> Boolean = { false },
        shouldPause: () -> Boolean = { false },
    ): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            // ¿Ya está? No duplicar (el botón igual se deshabilita, esto es defensa).
            if (localUriFor(track) != null) return@withContext

            val handle = choice?.let {
                StreamHandle(android.net.Uri.parse(it.url), it.mimeType)
            } ?: resolve(track)
            val ext = when (handle.mimeType) {
                "audio/mpeg" -> "mp3"
                "audio/mp4" -> "m4a"
                "audio/webm" -> "webm"
                "audio/ogg" -> "ogg"
                "audio/flac" -> "flac"
                "audio/x-flac" -> "flac"
                else -> guessExtFromUrl(handle.uri.toString())
            }
            val safeBase = "${track.artist} - ${track.title}"
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(120)
            val unique = "${track.sourceId}_${track.externalId.take(12)}"
            val fileName = "$safeBase [$unique].$ext"
            val temp = File(dir, "$fileName.parte")

            // ETAPA 3 (parte 2b): con destino público prendido, el archivo puede
            // YA estar en Music/Resonar de una descarga anterior — por ejemplo
            // tras REINSTALAR la app: el índice vive en datos privados y se
            // pierde, pero la carpeta pública sobrevive. Reutilizar esa fila
            // en vez de bajar todo el tema de nuevo.
            val public = saveToPublicMusic() &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            if (public) {
                reuseExistingInPublicMusic(fileName)?.let { uri ->
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
                            sizeBytes = sizeOfMediaStoreRow(uri),
                            mediaStoreUri = uri.toString(),
                        ),
                    )
                    Log.i("ResonarDown", "Ya estaba en Music/Resonar: ${track.title}")
                    return@withContext
                }
            }

            try {
                val conn = URL(handle.uri.toString()).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", com.resonar.app.online.Http.USER_AGENT)
                    // ccMixter exige un Referer de su propio sitio (si no, 403).
                    if (conn.url.host?.lowercase()?.endsWith("ccmixter.org") == true) {
                        conn.setRequestProperty("Referer", "https://ccmixter.org/")
                    }
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
                                // CANCELACIÓN y PAUSA cooperativas (Etapa 3):
                                // HttpURLConnection no responde a interrupciones
                                // de hilo, así que la única forma limpia es revisar
                                // entre trozos. En ambos casos se conserva el .parte
                                // para reanudar en el próximo intento. La diferencia:
                                // cancelar saca el trabajo de la cola, pausar lo deja.
                                if (shouldPause()) throw DownloadPausedException()
                                if (shouldCancel()) throw IllegalStateException("Cancelada")
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
                    // Destino final (Etapa 3 parte 2b), en orden de preferencia:
                    // 1) Music/Resonar público vía MediaStore (si el usuario lo
                    //    pidió en Ajustes y el Android lo soporta: API 29+),
                    // 2) subcarpeta "Descargas" del árbol SAF elegido,
                    // 3) la carpeta privada como última red.
                    val tree = folderStore.uri()
                    val mime = handle.mimeType ?: mimeForExt(ext)
                    var created: Uri? = null
                    var toPublic = false
                    if (public) {
                        created = moveToPublicMusic(fileName, mime, temp, track)
                        if (created != null) toPublic = true
                    }
                    if (created == null && tree != null) {
                        created = moveToDriveFolder(tree, fileName, mime, temp)
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
                                treeUri = if (toPublic) null else created.toString(),
                                mediaStoreUri = if (toPublic) created.toString() else null,
                            ),
                        )
                        Log.i(
                            "ResonarDown",
                            if (toPublic) "Descargado a Music/Resonar (público): ${track.title}"
                            else "Descargado a tu carpeta: ${track.title}",
                        )
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
     * ETAPA 3 (parte 2b): mueve el temporal ya descargado a la carpeta pública
     * `Music/Resonar` vía MediaStore (Android 10+). Difiere del camino SAF:
     * acá se INSERTA una fila (IS_PENDING mientras se escribe) y el sistema
     * la escanea recién cuando se marca terminada — el extractor llena
     * álbum, duración y demás etiquetas. Hasta ese momento ningún otro
     * reproductor ve un archivo a la mitad.
     * Devuelve la Uri de la fila creada, o null si algo falló (el llamador
     * cae al destino SAF o privado). El temporal se borra solo si salió bien.
     */
    private fun moveToPublicMusic(fileName: String, mime: String, temp: File, track: OnlineTrack): Uri? {
        val cr = app.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            // Metadatos escritos por si el archivo no trae etiquetas (YouTube
            // m4a suele venir pelado): que otro reproductor muestre algo útil.
            put(MediaStore.Audio.Media.TITLE, track.title)
            put(MediaStore.Audio.Media.ARTIST, track.artist.trim().ifBlank { "<Sin artista>" })
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Resonar")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val created = runCatching { cr.insert(collection, values) }.getOrNull() ?: return null
        return try {
            cr.openOutputStream(created)?.use { output ->
                temp.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
            } ?: throw IllegalStateException("No se pudo abrir la salida de MediaStore")
            // Terminada: se marca no-pendiente y el sistema escanea el archivo.
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            cr.update(created, values, null, null)
            temp.delete()
            created
        } catch (e: Exception) {
            Log.w("ResonarDown", "Fallo copiando a Music/Resonar: ${e.message}; cae a otro destino")
            runCatching { cr.delete(created, null, null) }
            null
        }
    }

    /**
     * ¿Hay una fila de MediaStore con ESTE nombre exacto en Music/Resonar?
     * Sirve para no duplicar tras reinstalar (el índice se pierde, la carpeta
     * pública no). Devuelve su Uri si existe y está completa; si hay una fila
     * muerta (crash a mitad de escritura: casi vacía), la limpia y devuelve null.
     */
    private fun reuseExistingInPublicMusic(fileName: String): Uri? {
        val cr = app.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val row = runCatching {
            cr.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.SIZE),
                "${MediaStore.Audio.Media.DISPLAY_NAME}=? AND ${MediaStore.Audio.Media.RELATIVE_PATH}=?",
                arrayOf(fileName, "Music/Resonar/"),
                null,
            )?.use { c ->
                if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null
            }
        }.getOrNull() ?: return null
        val (id, size) = row
        val uri = ContentUris.withAppendedId(collection, id)
        return if (size > 1024) {
            uri
        } else {
            // Fila muerta de un intento anterior: se borra y se baja de nuevo.
            runCatching { cr.delete(uri, null, null) }
            null
        }
    }

    /** Tamaño registrado de una fila de MediaStore (0 si ya no está). */
    private fun sizeOfMediaStoreRow(uri: Uri): Long = runCatching {
        app.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.SIZE),
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)

    /** ¿La fila de MediaStore sigue existiendo? (para el índice y para saber si hay que re-bajar) */
    fun isMediaStoreEntryAlive(entry: Entry): Boolean =
        entry.mediaStoreUri?.let { mediaStoreRowExists(Uri.parse(it)) } ?: false

    private fun mediaStoreRowExists(uri: Uri): Boolean = runCatching {
        app.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media._ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

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
