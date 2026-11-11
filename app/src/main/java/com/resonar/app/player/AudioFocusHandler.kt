package com.resonar.app.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player

/**
 * Manejo PROPIO del foco de audio. NO usamos el automático de ExoPlayer
 * (setAudioAttributes(attrs, handleAudioFocus = false)) porque el comportamiento
 * por defecto de ExoPlayer pausa la música apenas otra app pide el foco, y eso es
 * exactamente lo que queremos evitar cuando se abre un juego.
 *
 * Opción "Seguir sonando cuando otra app pide el audio" (keepPlaying, ON por defecto):
 *  - AUDIOFOCUS_LOSS_TRANSIENT (otra app pide foco por un rato): SEGUIMOS SONANDO. Sin pausa.
 *  - AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK (ej. notificación hablada): seguimos sonando bajando el volumen.
 *  - AUDIOFOCUS_LOSS (pérdida PERMANENTE): CON LA OPCIÓN ON TAMPOCO PAUSAMOS.
 *    Motivo real, confirmado con pruebas: los juegos (Mobile Legends p.ej.) piden foco permanente
 *    y generan AUDIOFOCUS_LOSS a cada rato; pausar por eso hacía que el ▶ del usuario muriera en <1 s.
 *    Trade-off conocido y asumido: si se abre OTRO reproductor de música pueden sonar los dos;
 *    en ese caso el usuario pausa Resonar a mano (el foco no puede distinguirlos).
 *  - Con la opción OFF: comportamiento estándar (pausar en transitoria y en permanente).
 *
 * CASOS QUE NO SE PUEDEN FORZAR (aclaración honesta, no simulamos lo imposible):
 *  1) Si el sistema MATA el proceso por falta de RAM, no hay callback donde defendernos:
 *     las defensas son ser livianos (por eso todo el diseño apunta a eso) y el Playback resumption
 *     (reanudar desde la notificación / botón del auricular vía onPlaybackResumption).
 *  2) AUDIOFOCUS_LOSS permanente es el límite de la opción: si otro app (otro reproductor,
 *     o un juego que pide AUDIOFOCUS_GAIN permanente) toma el foco, el sistema nos deja sin foco
 *     y EN ESE CASO pausamos. En Android no existe forma de distinguir "juego" de "otro reproductor"
 *     de forma confiable; si en tus pruebas un juego te corta por esta vía, avisame y cambiamos la
 *     política para ignorar también la pérdida permanente (riesgo: dos audios a la vez, que no se puede resolver).
 *  3) Doze y las restricciones de fabricante (Motorola/Moto Actions) limitan CPU/red en fondo sin permiso:
 *     no se puede garantizar desde la app, por eso incluimos el acceso directo a la config de batería.
 */
class AudioFocusHandler(
    context: Context,
    private val player: Player,
    // Lee la preferencia fresca en cada evento: así el toggle de Ajustes actúa sin reiniciar nada.
    private val keepPlaying: () -> Boolean,
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** Volumen al que bajamos cuando nos piden "duck" (seguir sonando, más suave). */
    private val duckVolume = 0.2f

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // CAMBIO DE POLÍTICA (confirmado con tests reales): juegos como Mobile Legends
                // piden foco PERMANENTE (AUDIOFOCUS_GAIN), lo que genera AUDIOFOCUS_LOSS para
                // nosotros. Si pausáramos acá, el play del usuario moría en <1 s (CASO REPORTADO:
                // "se pausa solo al arrancar el partido y cada tanto"). Con "seguir sonando" ON:
                // NO pausamos NUNCA por foco. Trade-off conocido: si se abría OTRO reproductor
                // de música, pueden sonar los dos; en ese caso se pausa Resonar a mano.
                if (!keepPlaying() && player.playWhenReady) player.pause()
                player.volume = 1f
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Pedido temporario (típico de juegos). Con "seguir sonando" activo NO pausamos.
                if (!keepPlaying() && player.playWhenReady) player.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Bajamos el volumen, seguimos sonando.
                player.volume = duckVolume
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Recuperamos el foco: restauramos el volumen. Si pausamos por pérdida transitoria
                // (con la opción OFF), NO reanudamos automáticamente, el usuario decide.
                player.volume = 1f
            }
        }
    }

    // AudioFocusRequest existe desde API 26. En API 24/25 usamos la variante clásica.
    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttrs)
                // Importante: el "duck" lo manejamos bajando volumen, no avisando que pausamos.
                .setWillPauseWhenDucked(false)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusListener, mainHandler)
                .build()
        } else {
            null
        }

    /**
     * Pide el foco antes de empezar a sonar. Devuelve true si lo consiguió (o quedó en cola),
     * false si lo rechazaron. IMPORTANTE: aunque lo rechacen, el audio SIGUE SONANDO — el foco
     * de audio en Android no se puede forzar y NO lo usamos como "permiso" para mutear:
     * el volumen siempre lo controlamos nosotros con player.volume.
     */
    fun requestAudioFocus(): Boolean {
        // Arrancar a sonar SIEMPRE resetea el volumen a tope: evita el bug de quedarse con el
        // volumen bajado de un "duck" anterior cuando la app estuvo pausada entre medio.
        player.volume = 1f
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    /** Suelta el foco cuando paramos de sonar. */
    fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }
}
