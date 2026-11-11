package com.resonar.app.di

import android.content.Context
import com.resonar.app.data.LibrarySyncer
import com.resonar.app.data.LocalLibraryRepository
import com.resonar.app.data.OnlineDownloader
import com.resonar.app.data.TrackDeleter
import com.resonar.app.data.UserLibraryStore
import com.resonar.app.online.OnlineRepository
import com.resonar.app.player.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Inyección de dependencias MANUAL (sin Hilt): un contenedor con lo que vive
 * a nivel de app, no a nivel de pantalla. Menos magia, menos RAM, control total.
 *
 * ¿Por qué a nivel de Application? Porque el MediaController del player y la
 * biblioteca escaneada sobreviven a rotaciones de pantalla y cambios de pestaña
 * sin re-crear nada (y sin fugas: se liberan cuando muere el proceso).
 */
class AppContainer(appContext: Context) {

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Descarga de temas online al teléfono (botón en los resultados). */
    val onlineDownloader: OnlineDownloader = OnlineDownloader(appContext)

    /**
     * Escáner/índice de la música local. Las descargas online son parte
     * de la biblioteca: se fusionan en cada escaneo.
     */
    val libraryRepository: LocalLibraryRepository =
        LocalLibraryRepository(appContext, scope, onlineDownloader)

    /** Favoritos, playlists y escuchados hace poco. Sobrevive al cierre de la app. */
    val userLibrary: UserLibraryStore = UserLibraryStore(appContext)

    /** Puente con el servicio de audio: un solo MediaController compartido por toda la UI. */
    val playerConnection: PlayerConnection = PlayerConnection(appContext, scope)

    /**
     * Descarga automática de los temas de nube (Drive) al teléfono. Necesita la
     * biblioteca ya creada: mira su estado para saber qué falta bajar.
     */
    val librarySyncer: LibrarySyncer = LibrarySyncer(appContext, scope, libraryRepository)

    /** Borrado limpio de canciones (deslizar → confirmar → papelera de Drive). */
    val trackDeleter: TrackDeleter =
        TrackDeleter(appContext, librarySyncer, userLibrary, libraryRepository, onlineDownloader)

    /** Búsqueda federada en línea (Audius, Internet Archive; YouTube entra en 2B). */
    val onlineRepository: OnlineRepository = OnlineRepository(appContext)
}
