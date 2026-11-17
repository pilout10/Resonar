package com.resonar.app.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player

/**
 * Manejo PROPIO del foco. ExoPlayer no lo toca (handleAudioFocus = false).
 *
 * Con "Seguir sonando" ON (el caso de Mobile Legends):
 *  - no pausamos nunca por pérdida de foco
 *  - no volvemos a pedir AUDIOFOCUS_GAIN: pelear el foco exclusivo es lo que
 *    cortaba la música después de las actualizaciones
 *  - no bajamos el volumen: un duck a 0.2 se oye como un corte
 *
 * Con la opción OFF: pausa normal cuando otra app pide el audio.
 */
class AudioFocusHandler(
    context: Context,
    private val player: Player,
    private val keepPlaying: () -> Boolean,
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    @Volatile
    var lostToOtherApp: Boolean = false
        private set

    private val duckVolume = 0.2f

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "foco=$change keep=${keepPlaying()}")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                lostToOtherApp = true
                if (!keepPlaying() && player.playWhenReady) player.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!keepPlaying()) player.volume = duckVolume
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                lostToOtherApp = false
                player.volume = 1f
            }
        }
    }

    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttrs)
                .setWillPauseWhenDucked(false)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusListener, mainHandler)
                .build()
        } else {
            null
        }

    fun requestAudioFocus(): Boolean {
        player.volume = 1f
        if (keepPlaying() && lostToOtherApp) {
            Log.i(TAG, "no peleo el foco: otra app lo tiene y hay que seguir sonando")
            return false
        }
        lostToOtherApp = false
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

    fun abandonAudioFocus() {
        lostToOtherApp = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private companion object {
        const val TAG = "ResonarFocus"
    }
}
