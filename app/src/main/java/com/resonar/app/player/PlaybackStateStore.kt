package com.resonar.app.player

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Track serializable, para retomar la cola incluso si el sistema mata el proceso. */
@Serializable
data class SavedTrack(
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val artUri: String? = null, // carátula para la notificación y la UI (puede faltar)
)

/** Estado completo de la sesión de reproducción: canción, posición, cola, sonido. */
@Serializable
data class PlaybackSavedState(
    val playlist: List<SavedTrack> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val playWhenReady: Boolean = false,
)

/**
 * Posición de reproducción sola: es el dato que cambia TODO el tiempo (cada save),
 * mientras la cola casi nunca cambia. Por eso se guardan en claves separadas:
 * la cola (JSON grande) se escribe solo cuando cambia, la posición (JSON chico)
 * puede escribirse cada 15 s sin penalizar el cambio de tema.
 */
@Serializable
data class PlaybackPosition(
    val index: Int = 0,
    val positionMs: Long = 0L,
    val playWhenReady: Boolean = false,
)

/**
 * Persistencia liviana de sesión: SharedPreferences + JSON con kotlinx.serialization.
 * Elegida a propósito para quedarnos livianos (sin base de datos abierta ni procesos extra).
 * Room entra en las etapas de biblioteca/caché; esto es solo para estado del player.
 */
class PlaybackStateStore(context: Context) {

    private val prefs = context.getSharedPreferences("resonar_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun saveState(state: PlaybackSavedState) {
        // Formato completo, usado solo en migración desde versiones viejas.
        runCatching { prefs.edit().putString(KEY_SESSION, json.encodeToString(state)).apply() }
    }

    /** Cola: JSON grande, se escribe SOLO cuando cambia (otra playlist, otro tema en la cola). */
    fun savePlaylistJson(tracks: List<SavedTrack>) {
        runCatching { prefs.edit().putString(KEY_PLAYLIST, json.encodeToString(tracks)).apply() }
    }

    /** Posición: JSON chico (3 números), barato de escribir seguido. */
    fun savePosition(pos: PlaybackPosition) {
        runCatching { prefs.edit().putString(KEY_POSITION, json.encodeToString(pos)).apply() }
    }

    fun loadState(): PlaybackSavedState? {
        // Migración del formato viejo (todo en una clave): partimos y borramos.
        runCatching {
            prefs.getString(KEY_SESSION, null)?.let { legacyJson ->
                val legacy = json.decodeFromString<PlaybackSavedState>(legacyJson)
                prefs.edit()
                    .putString(KEY_PLAYLIST, json.encodeToString(legacy.playlist))
                    .putString(
                        KEY_POSITION,
                        json.encodeToString(
                            PlaybackPosition(legacy.currentIndex, legacy.positionMs, legacy.playWhenReady),
                        ),
                    )
                    .remove(KEY_SESSION)
                    .apply()
            }
        }
        val playlist = runCatching {
            prefs.getString(KEY_PLAYLIST, null)?.let { json.decodeFromString<List<SavedTrack>>(it) }
        }.getOrNull() ?: return null
        val pos = runCatching {
            prefs.getString(KEY_POSITION, null)?.let { json.decodeFromString<PlaybackPosition>(it) }
        }.getOrNull() ?: PlaybackPosition()
        return PlaybackSavedState(
            playlist = playlist,
            currentIndex = pos.index,
            positionMs = pos.positionMs,
            playWhenReady = pos.playWhenReady,
        )
    }

    /**
     * "Seguir sonando cuando otra app pide el audio".
     * ON por defecto (requisito de la etapa 0).
     */
    fun isKeepPlayingEnabled(): Boolean = prefs.getBoolean(KEY_KEEP_PLAYING, true)

    fun setKeepPlayingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_PLAYING, enabled).apply()
    }

    /**
     * Tema de la app: "dark" (por defecto), "light" o "system".
     * La app de música arranca en oscuro: se ve mejor y cansa menos la vista de noche.
     */
    fun getThemeMode(): String = prefs.getString(KEY_THEME, "dark") ?: "dark"

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME, mode).apply()
    }

    /** "alta", "estandar" o "ahorro". En archivos locales no recodifica: ajusta carátulas. */
    fun getAudioQuality(): String = prefs.getString(KEY_QUALITY, "alta") ?: "alta"

    fun setAudioQuality(value: String) {
        prefs.edit().putString(KEY_QUALITY, value).apply()
    }

    fun isAutoDownload(): Boolean = prefs.getBoolean(KEY_AUTO_DOWNLOAD, true)

    fun setAutoDownload(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, enabled).apply()
    }

    /**
     * Caché de streaming (256 MB), SEPARADA de la descarga automática.
     * Antes compartían interruptor y apagar la descarga volvía lento Drive:
     * ahora se puede tener caché sin copiar toda la biblioteca.
     */
    fun isStreamCacheEnabled(): Boolean = prefs.getBoolean(KEY_STREAM_CACHE, true)

    fun setStreamCacheEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STREAM_CACHE, enabled).apply()
    }

    /** Carátulas más chicas: menos RAM en el Motorola de gama de entrada. */
    fun isDataSaver(): Boolean = prefs.getBoolean(KEY_DATA_SAVER, false)

    fun setDataSaver(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DATA_SAVER, enabled).apply()
    }

    /**
     * ETAPA 3 (parte 2): si está prendido, las descargas de la búsqueda online
     * NO arrancan con datos móviles. Se avisa en el momento, sin simular nada.
     */
    fun isWifiOnlyDownloads(): Boolean = prefs.getBoolean(KEY_WIFI_ONLY, false)

    fun setWifiOnlyDownloads(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
    }

    /**
     * Si está prendido (por defecto), cada descarga con más de una calidad
     * abre la hoja para elegir. Si se apaga, se toma la mejor disponible
     * (la primera, que es la que la fuente prioriza) sin preguntar.
     */
    fun isAskDownloadQuality(): Boolean = prefs.getBoolean(KEY_ASK_QUALITY, true)

    fun setAskDownloadQuality(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ASK_QUALITY, enabled).apply()
    }

    /**
     * ETAPA 3 (parte 2b): si está prendido, las descargas de la búsqueda online
     * van a `Music/Resonar` en el almacenamiento COMPARTIDO (MediaStore) en vez
     * de a la carpeta privada. Ahí las ven otros reproductores y sobreviven a
     * la desinstalación de la app. Requiere Android 10 (MediaStore con
     * RELATIVE_PATH); en Android más viejo la app baja igual a su carpeta
     * privada, y se lo decimos en Ajustes.
     */
    fun isSaveToPublicMusic(): Boolean = prefs.getBoolean(KEY_PUBLIC_MUSIC, false)

    fun setSaveToPublicMusic(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PUBLIC_MUSIC, enabled).apply()
    }

    /**
     * RADIO AUTOMÁTICA (Etapa 4): cuando la cola termina, sumar sola más
     * canciones parecidas. Persistente: es una preferencia de uso, no de sesión.
     */
    fun isAutoRadio(): Boolean = prefs.getBoolean(KEY_AUTO_RADIO, false)

    fun setAutoRadio(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RADIO, enabled).apply()
    }

    fun isEqEnabled(): Boolean = prefs.getBoolean(KEY_EQ_ENABLED, false)

    fun setEqEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EQ_ENABLED, enabled).apply()
    }

    fun getEqPresetName(): String = prefs.getString(KEY_EQ_PRESET, "Apagado") ?: "Apagado"

    fun setEqPresetName(name: String) {
        prefs.edit().putString(KEY_EQ_PRESET, name).apply()
    }

    fun getEqLevels(): List<Int> =
        prefs.getString(KEY_EQ_LEVELS, null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()

    fun setEqLevels(levels: List<Int>) {
        prefs.edit().putString(KEY_EQ_LEVELS, levels.joinToString(",")).apply()
    }

    /** 0 a 1000. Graves extra del teléfono, aparte de las bandas. */
    fun getBassStrength(): Int = prefs.getInt(KEY_BASS, 0).coerceIn(0, 1000)

    fun setBassStrength(strength: Int) {
        prefs.edit().putInt(KEY_BASS, strength.coerceIn(0, 1000)).apply()
    }

    fun getEqSnapshotJson(): String? = prefs.getString(KEY_EQ_SNAPSHOT, null)

    fun setEqSnapshotJson(json: String) {
        prefs.edit().putString(KEY_EQ_SNAPSHOT, json).apply()
    }

    /**
     * El listener de SharedPreferences es débil: hay que guardar la referencia
     * o el sistema lo tira y el ecualizador deja de enterarse de los cambios.
     */
    fun setOnPreferenceChanged(block: (String?) -> Unit) {
        preferenceListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != KEY_EQ_SNAPSHOT) block(key)
        }
        preferenceListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    companion object {
        const val KEY_SESSION = "playback_session"
        const val KEY_PLAYLIST = "playback_playlist"
        const val KEY_POSITION = "playback_position"
        const val KEY_KEEP_PLAYING = "keep_playing_on_audio_focus_loss"
        const val KEY_THEME = "theme_mode"
        const val KEY_QUALITY = "audio_quality"
        const val KEY_AUTO_DOWNLOAD = "auto_download"
        const val KEY_STREAM_CACHE = "stream_cache"
        const val KEY_DATA_SAVER = "data_saver"
        const val KEY_EQ_ENABLED = "eq_enabled"
        const val KEY_EQ_PRESET = "eq_preset"
        const val KEY_EQ_LEVELS = "eq_levels"
        const val KEY_EQ_SNAPSHOT = "eq_snapshot"
        const val KEY_BASS = "eq_bass"
        const val KEY_WIFI_ONLY = "downloads_wifi_only"
        const val KEY_ASK_QUALITY = "downloads_ask_quality"
        const val KEY_PUBLIC_MUSIC = "downloads_public_music"

        const val KEY_AUTO_RADIO = "auto_radio"
    }
}
