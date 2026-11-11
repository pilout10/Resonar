package com.resonar.app.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.resonar.app.player.StreamCacheHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * BORRADO LIMPIO de una canción (deslizar hacia la izquierda → confirmar).
 *
 * "Limpio" significa que el tema NO vuelve a aparecer ni deja restos:
 *  1. El archivo ORIGINAL sale de la carpeta de Drive (va a la papelera de Drive,
 *     donde se puede recuperar desde la app de Drive). Si no se puede borrar
 *     (sin conexión, permiso), NO se limpia nada: se informa y no hay engaños.
 *  2. La copia descargada por LibrarySyncer se borra del teléfono.
 *  3. La caché de streaming del tema se vacía.
 *  4. Desaparece de favoritos, "escuchadas hace poco" y todas las playlists.
 *  5. Si estaba sonando o en la cola, se saca de ahí.
 *  6. La biblioteca se re-escanea para que desaparezca de las listas.
 *
 * Las descargas ONLINE (fromDownloads): el archivo + su entrada del índice se
 * borran directo (no pasan por Drive ni MediaStore) y corren los puntos 3–6.
 *
 * Para temas del TELÉFONO (MediaStore, id positivo): en Android 11+ el borrado
 * lo confirma el sistema con SU cartel (createDeleteRequest), así que la app no
 * repite la pregunta; la limpieza de los puntos 2–6 corre igual.
 */
@UnstableApi
class TrackDeleter(
    private val app: Context,
    private val syncer: LibrarySyncer,
    private val user: UserLibraryStore,
    private val library: LocalLibraryRepository,
    private val downloads: OnlineDownloader,
) {

    /**
     * Borra el archivo ORIGINAL. Devuelve false si no pudo (y entonces no se
     * limpia nada, para no dejar el tema "medio borrado").
     * Los temas de MediaStore en Android 11+ NO pasan por acá: el sistema los
     * borra tras su propio diálogo, y recién entonces se llama a cleanAppSide.
     */
    suspend fun deleteSource(track: LocalTrack): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (track.fromDownloads) {
                // Descarga online: borrar archivo + índice propio. No hay papelera.
                val entry = downloads.entryByFileName(track.fileName)
                // Si la entrada ya no está pero el tema está listado, devolver true
                // para que la limpieza (favoritos/playlists) corra igual.
                entry?.let { downloads.removeDownload(it.mediaId) } ?: true
            } else if (track.id < 0) {
                // Tema de carpeta (Drive): deleteDocument lo manda a la papelera.
                // Si además era una DESCARGA de Resonar, limpiamos su entrada del índice
                // para que el resultado en línea vuelva a mostrar el botón de descarga.
                val ok = DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(track.uri))
                if (ok) {
                    downloads.entryByFileName(track.fileName)?.let { downloads.removeDownload(it.mediaId) }
                }
                ok
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                // Tema del teléfono, Android ≤ 10: el permiso WRITE lo permite directo.
                app.contentResolver.delete(Uri.parse(track.uri), null, null) > 0
            } else {
                // Android 11+: el sistema ya borró (createDeleteRequest) antes de llegar acá.
                true
            }
        }.getOrElse { e ->
            Log.w("ResonarDelete", "No se pudo borrar ${track.title}: ${e.message}")
            false
        }
    }

    /** Los puntos 2, 3 y 4 de la limpieza: copia local, caché y listas de usuario. */
    suspend fun cleanAppSide(track: LocalTrack) = withContext(Dispatchers.IO) {
        syncer.deleteDownloadedCopy(track)
        StreamCacheHolder.remove(app, track.uri)
        user.removeEverywhere(track.id)
        Log.i("ResonarDelete", "Limpieza completa de: ${track.title}")
    }

    /** El punto 6: re-escanear para que el tema salga de todas las listas. */
    fun rescan() = library.refresh()
}
