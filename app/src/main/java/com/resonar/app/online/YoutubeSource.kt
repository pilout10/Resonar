package com.resonar.app.online

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit

/**
 * FUENTE YOUTUBE — vía NewPipeExtractor (ETAPA 2B).
 *
 * Sin API key de YouTube y sin cuentas. La librería extrae la búsqueda y los
 * streams de audio de la web de YouTube como lo hace NewPipe. LICENCIA: GPLv3
 * (usarla hace GPLv3 a Resonar — está aclarado en Ajustes).
 *
 * Dos modos que el usuario elige con las pills "Canciones / Videos":
 *  - Canciones → filtro MUSIC_SONGS: resultados de YouTube Music, sin "basura"
 *    (directos mal grabados, compilados, versiones de estudio raros).
 *  - Videos → filtro VIDEOS: todo lo demás.
 * En ambos se filtra duración: 10 s a 15 MIN (lo pide el plan; un tema no es
 * un podcast ni un short).
 *
 * QUÉ SE ROMPE SI EL SERVICIO CAMBIA: YouTube cambia su web TODO el tiempo y
 * a veces antepone verificaciones (poToken) que los clientes web sin cookie
 * no pueden pasar. Cuando pase, esta fuente va a fallar honestamente y el
 * resto de las fuentes siguen funcionando. NO es un bug de la app.
 *
 * Stream URLs de YouTube VENCEN (~6 h): por eso el mediaId es
 * "online:youtube:{videoId}" y el MusicService puede RE-RESOLVER con solo el
 * id (resolveStream usa únicamente externalId, como el resto de las fuentes).
 */
class YoutubeSource : MusicSource {

    override val id = "youtube"
    override val displayName = "YouTube"
    override val requiresKey = false

    /** 0 = canciones (YT Music), 1 = videos (YouTube). Lo setea el ViewModel. */
    @Volatile
    var mode = 0

    override suspend fun search(query: String, page: Int): List<OnlineTrack> = withContext(Dispatchers.IO) {
        ensureInit()
        val filter = if (mode == 0) {
            YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        } else {
            YoutubeSearchQueryHandlerFactory.VIDEOS
        }
        val handler = ServiceList.YouTube.searchQHFactory
            .fromQuery(query, listOf(filter), "")
        val cacheKey = "${query.trim().lowercase()}|$mode"

        val items: List<InfoItem> = if (page <= 0) {
            val info = SearchInfo.getInfo(ServiceList.YouTube, handler)
            synchronized(nextPageTokens) { nextPageTokens[cacheKey] = info.nextPage }
            info.relatedItems
        } else {
            val token = synchronized(nextPageTokens) { nextPageTokens[cacheKey] }
            if (token == null) return@withContext emptyList()
            val more = SearchInfo.getMoreItems(ServiceList.YouTube, handler, token)
            synchronized(nextPageTokens) { nextPageTokens[cacheKey] = more.nextPage }
            more.items
        }

        val out = ArrayList<OnlineTrack>(items.size)
        for (item in items) {
            if (item.infoType != InfoItem.InfoType.STREAM) continue
            val video = item as StreamInfoItem
            val seconds = video.duration
            // Duración obligatoria y razonable: sin duración no confiamos (shorts,
            // directos) y más de 15 min no es "un tema".
            if (seconds <= 0 || seconds * 1_000L > MAX_DURATION_MS) continue
            // InfoItem no expone getId(): el id de video sale de la URL del item.
            val videoId = runCatching {
                YoutubeStreamLinkHandlerFactory.getInstance().getId(video.url)
            }.getOrNull().orEmpty()
            if (videoId.isBlank()) continue
            out.add(
                OnlineTrack(
                    sourceId = id,
                    externalId = videoId,
                    title = video.name.orEmpty().trim().ifBlank { videoId },
                    artist = video.uploaderName.orEmpty().ifBlank { "YouTube" },
                    durationMs = seconds * 1_000L,
                    // Miniatura estable de YouTube: mqdefault (320×180), liviana.
                    artUrl = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
                ),
            )
        }
        Log.i("ResonarOnline", "YouTube search '$query' (pág. $page, filtro=$filter) → ${out.size} resultados")
        out
    }

    override suspend fun resolveStream(track: OnlineTrack): StreamHandle = withContext(Dispatchers.IO) {
        ensureInit()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=${track.externalId}")
        val audio = info.audioStreams
        // M4A (AAC) primero: formato que ExoPlayer traga siempre; después el
        // resto por bitrate (opus incluido, que también es universal acá).
        val best = audio.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: audio.maxByOrNull { it.averageBitrate }
            ?: throw IllegalStateException("YouTube: sin stream de audio para ${track.externalId}")
        val url = best.content ?: throw IllegalStateException("YouTube: stream sin URL")
        Log.i("ResonarOnline", "YouTube stream: ${track.externalId} (${best.format?.name} ${best.averageBitrate}kbps)")
        StreamHandle(
            uri = Uri.parse(url),
            mimeType = when (best.format) {
                MediaFormat.M4A -> "audio/mp4"
                MediaFormat.WEBMA_OPUS, MediaFormat.WEBMA -> "audio/webm"
                else -> null
            },
        )
    }

    /** Pegado de URL: youtube.com/watch?v=…, youtu.be/…, music.youtube.com/… */
    suspend fun resolveUrl(url: String): OnlineTrack? {
        val videoId = videoIdFromUrl(url) ?: return null
        ensureInit()
        return withContext(Dispatchers.IO) {
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            OnlineTrack(
                sourceId = id,
                externalId = videoId,
                title = info.name.orEmpty().trim().ifBlank { videoId },
                artist = info.uploaderName.orEmpty().ifBlank { "YouTube" },
                durationMs = if (info.duration > 0) info.duration * 1_000L else null,
                artUrl = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            )
        }
    }

    fun canResolveUrl(url: String): Boolean = videoIdFromUrl(url) != null

    /** Saca el id de video de las formas comunes de URL de YouTube. */
    private fun videoIdFromUrl(url: String): String? {
        val parsed = Uri.parse(url)
        val host = parsed.host.orEmpty().lowercase()
        if (!host.contains("youtube.com") && !host.contains("youtu.be") && !host.contains("ytimg.com")) return null
        val v = parsed.getQueryParameter("v")
        if (!v.isNullOrBlank()) return v
        // youtu.be/{id} o youtube.com/shorts/{id}
        val segment = parsed.pathSegments?.firstOrNull { it !in listOf("shorts", "embed", "live", "watch") }
        return segment?.takeIf { it.length in 8..16 && !it.contains('.') }
    }

    companion object {
        /** El plan manda: un "tema" dura hasta 15 minutos. */
        private const val MAX_DURATION_MS = 900_000L
        private const val MIN_DURATION_MS = 10_000L

        /** Tokens de paginación por "consulta|modo": 1 entrada por búsqueda. */
        private val nextPageTokens = HashMap<String, Page?>()

        @Volatile
        private var inited = false

        /** NewPipe.init es global y una sola vez: downloader propio, 10 s de timeout. */
        fun ensureInit() {
            if (inited) return
            synchronized(this) {
                if (inited) return
                NewPipe.init(NpeHttpDownloader())
                inited = true
                Log.i("ResonarOnline", "NewPipeExtractor inicializado (YouTube)")
            }
        }
    }
}

/**
 * Cliente HTTP que le da la librería: okhttp ya viene con el extractor como
 * dependencia. Timeout 10 s (la web de YouTube puede ser pesada; el resto de
 * las fuentes usan 8 s en su propio Http).
 */
class NpeHttpDownloader : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
        val body: ByteArray? = request.dataToSend()
        when (request.httpMethod().uppercase()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> builder.post(okhttp3.RequestBody.create(null, body ?: ByteArray(0)))
            else -> builder.method(
                request.httpMethod(),
                body?.let { okhttp3.RequestBody.create(null, it) },
            )
        }
        request.headers().forEach { (name, values) -> values.forEach { builder.header(name, it) } }
        client.newCall(builder.build()).execute().use { resp ->
            // TOPE DE TAMAÑO (auditoría): el extractor pide páginas web y JSON;
            // ninguna supera 2 MB. Sin tope, `body.string()` cargaría en RAM
            // lo que el servidor mande — un teléfono de 2 GB no se lo banca.
            val text = resp.body?.source()?.use { src ->
                src.request(MAX_RESPONSE_BYTES + 1L)
                if (src.buffer.size > MAX_RESPONSE_BYTES) {
                    throw IllegalStateException("Respuesta de YouTube demasiado grande (> 2 MB)")
                }
                String(src.buffer.readByteArray(), Charsets.UTF_8)
            } ?: ""
            return Response(
                resp.code,
                resp.message.orEmpty(),
                resp.headers.toMultimap(),
                text,
                resp.request.url.toString(),
            )
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 2_000_000L
    }
}
