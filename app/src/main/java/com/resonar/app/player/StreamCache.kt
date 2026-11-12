package com.resonar.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.resonar.app.data.MusicPlaces
import java.io.File

/**
 * CACHÉ DE STREAMING (el truco de las apps grandes).
 *
 * Los temas de una carpeta de Google Drive NO son archivos locales: cada reproducción
 * baja el audio por internet (por eso tardaban 30 s o se quedaban cargando).
 * Solución de Spotify/YouTube Music: guardar en disco lo que se baja (CacheDataSource).
 * Primera reproducción: descarga y suena; siguientes: instantáneas y sin red.
 *
 * Presupuesto: 256 MB en caché con desalojo LRU, en la carpeta de caché de la app
 * (el sistema la puede limpiar si falta espacio; se re-descarga solo, sin corromper nada).
 */

/**
 * true si la URI es un archivo local (MediaStore, árbol SAF del propio teléfono,
 * o copia ya descargada). false = nube (Drive, http…).
 *
 * Importante: una carpeta elegida en Ajustes puede ser local (DocumentsUI del
 * almacenamiento) o remota (Drive). Las locales NO se vuelven a copiar al teléfono
 * ni pasan por la caché de streaming: ya están en disco.
 */
fun isLocalMediaUri(uri: Uri?): Boolean {
    if (uri == null) return false
    val scheme = uri.scheme ?: return false
    if (scheme == "http" || scheme == "https") return false
    // "file://" = copia descargada por LibrarySyncer al almacenamiento de la app.
    if (scheme == "file") return true
    if (scheme != "content") return false
    // "media" = MediaStore; los proveedores SAF locales también son disco.
    return uri.authority == "media" || MusicPlaces.isLocalSaf(uri.authority)
}

/** Caché única para todo el proceso: NO crear más de una SimpleCache por carpeta. */
@UnstableApi
object StreamCacheHolder {

    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val dir = File(context.cacheDir, "stream_cache")
            val newCache = SimpleCache(
                dir,
                // LRU: lo menos usado se borra primero, techo de 256 MB.
                LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
                StandaloneDatabaseProvider(context),
            )
            cache = newCache
            return newCache
        }
    }

    /**
     * Borrado limpio: al eliminar una canción, su caché de streaming también
     * (si no, quedarían megas muertos de un archivo que ya no existe).
     * SimpleCache es bloqueante: el que llama debe estar fuera del hilo principal.
     */
    fun remove(context: Context, uri: String) {
        runCatching { get(context).removeResource(uri) }
    }
}

/**
 * Fábrica que ROUTEA cada apertura:
 *  - URI local (MediaStore) → lectura directa del archivo, sin caché (ya está en disco).
 *  - URI de nube (Drive, http) → CacheDataSource: baja por bloques, guarda y sirve.
 * Con "Descarga automática" OFF va directo a la red sin cachear (modo streaming puro).
 */
@UnstableApi
class SmartDataSourceFactory(
    context: Context,
    private val useCache: () -> Boolean,
) : DataSource.Factory {

    private val appContext = context.applicationContext
    /** Archivos locales: lectura directa, sin cabeceras HTTP. */
    private val localFactory = DefaultDataSource.Factory(appContext)
    /**
     * HTTP con User-Agent de navegador: algunos CDN (ccMixter, por ejemplo)
     * responden 403 al User-Agent por defecto de ExoPlayer ("ExoPlayerLib/1.5.1").
     * Se permiten redirecciones entre protocolos (http → https), que es lo que
     * hacen los CDN de Jamendo y de otros.
     */
    private val httpFactory: DataSource.Factory = DefaultDataSource.Factory(
        appContext,
        DefaultHttpDataSource.Factory()
            .setUserAgent(com.resonar.app.online.Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true),
    )

    /**
     * ccMixter (y algún CDN parecido) exige una cabecera "Referer" de su propio
     * sitio: sin eso responde 403 aunque el User-Agent sea de navegador.
     * Por eso hay una segunda fábrica, SOLO para esos hosts.
     */
    private val refererFactory: DataSource.Factory = DefaultDataSource.Factory(
        appContext,
        DefaultHttpDataSource.Factory()
            .setUserAgent(com.resonar.app.online.Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to "https://ccmixter.org/")),
    )

    /** Hosts que exigen su propio Referer. */
    private val needsReferer = setOf("ccmixter.org", "www.ccmixter.org")

    override fun createDataSource(): DataSource =
        RoutingDataSource(localFactory) { uri ->
            val host = uri.host?.lowercase().orEmpty()
            if (host in needsReferer) {
                // Con caché también: el archivo se guarda igual en el disco.
                if (useCache()) {
                    CacheDataSource.Factory()
                        .setCache(StreamCacheHolder.get(appContext))
                        .setUpstreamDataSourceFactory(refererFactory)
                        .createDataSource()
                } else {
                    refererFactory.createDataSource()
                }
            } else if (useCache()) {
                CacheDataSource.Factory()
                    .setCache(StreamCacheHolder.get(appContext))
                    .setUpstreamDataSourceFactory(httpFactory)
                    .createDataSource()
            } else {
                httpFactory.createDataSource()
            }
        }
}

/** DataSource que elige su origen al abrir, según la URI del archivo pedido. */
@UnstableApi
class RoutingDataSource(
    private val localFactory: DataSource.Factory,
    private val cloudFactory: (Uri) -> DataSource,
) : DataSource {

    private val listeners = mutableListOf<TransferListener>()
    private var child: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        child?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds = if (isLocalMediaUri(dataSpec.uri)) localFactory.createDataSource() else cloudFactory(dataSpec.uri)
        listeners.forEach { ds.addTransferListener(it) }
        child = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        child?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = child?.uri

    override fun getResponseHeaders(): MutableMap<String, List<String>> =
        child?.responseHeaders ?: mutableMapOf()

    override fun close() {
        child?.close()
        child = null
    }
}
