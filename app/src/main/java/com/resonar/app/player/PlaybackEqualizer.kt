package com.resonar.app.player

import android.media.audiofx.Equalizer
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Foto del ecualizador para la UI. La escribe el servicio, que es dueño del efecto. */
@Serializable
data class EqSnapshot(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val presetName: String = "Apagado",
    val presets: List<String> = emptyList(),
    val bands: List<EqBandUi> = emptyList(),
)

/**
 * Ecualizador de Android enganchado a la sesión del ExoPlayer.
 * Vive en el servicio: ahí está el audio de verdad. La pantalla solo guarda la preferencia
 * y este objeto la aplica. Si el teléfono no tiene el efecto, el audio sigue igual.
 */
class PlaybackEqualizer(
    private val store: PlaybackStateStore,
    private val player: ExoPlayer,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var effect: Equalizer? = null
    private var sessionId: Int = 0
    private var applying = false

    fun start() {
        store.setOnPreferenceChanged { key ->
            if (key == PlaybackStateStore.KEY_EQ_LEVELS) apply(publish = false)
            else apply(publish = true)
        }
        refreshSession()
    }

    /** La sesión de audio aparece cuando el player prepara el primer buffer. */
    fun refreshSession() {
        val id = runCatching { player.audioSessionId }.getOrDefault(0)
        if (id <= 0 || id == sessionId) return
        runCatching { effect?.release() }
        effect = null
        sessionId = id
        effect = runCatching { Equalizer(0, id) }.getOrNull()
        if (effect == null) {
            publish(available = false)
            return
        }
        apply(publish = true)
    }

    fun release() {
        runCatching { effect?.release() }
        effect = null
        sessionId = 0
    }

    private fun apply(publish: Boolean) {
        if (applying) return
        val eq = effect ?: return
        applying = true
        try {
            val name = store.getEqPresetName()
            val enabled = store.isEqEnabled() && name != "Apagado"
            runCatching {
                when {
                    name == "Personalizado" -> applyLevels(eq)
                    name != "Apagado" -> {
                        val index = (0 until eq.numberOfPresets.toInt()).firstOrNull {
                            eq.getPresetName(it.toShort()) == name
                        }
                        if (index != null) eq.usePreset(index.toShort()) else applyLevels(eq)
                    }
                }
                eq.enabled = enabled
            }
            if (publish || (name == "Personalizado" && store.getEqLevels().isEmpty())) {
                // Tras un preset del sistema guardamos las bandas para poder volver a "Personalizado".
                if (name != "Personalizado" && name != "Apagado") {
                    store.setEqLevels(readLevels(eq))
                }
                publish(available = true)
            }
        } finally {
            applying = false
        }
    }

    private fun applyLevels(eq: Equalizer) {
        val range = eq.bandLevelRange
        store.getEqLevels().forEachIndexed { index, level ->
            if (index < eq.numberOfBands.toInt()) {
                val coerced = level.coerceIn(range[0].toInt(), range[1].toInt())
                runCatching { eq.setBandLevel(index.toShort(), coerced.toShort()) }
            }
        }
    }

    private fun readLevels(eq: Equalizer): List<Int> =
        (0 until eq.numberOfBands.toInt()).map { eq.getBandLevel(it.toShort()).toInt() }

    private fun publish(available: Boolean) {
        val eq = effect
        val snapshot = if (!available || eq == null) {
            EqSnapshot(available = false, presetName = store.getEqPresetName(), enabled = false)
        } else {
            val range = eq.bandLevelRange
            val bands = (0 until eq.numberOfBands.toInt()).map { index ->
                val hz = eq.getCenterFreq(index.toShort()) / 1000
                EqBandUi(
                    index = index,
                    label = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz",
                    levelMb = eq.getBandLevel(index.toShort()).toInt(),
                    minMb = range[0].toInt(),
                    maxMb = range[1].toInt(),
                )
            }
            val presets = (0 until eq.numberOfPresets.toInt()).map { eq.getPresetName(it.toShort()) }
            EqSnapshot(
                available = true,
                enabled = eq.enabled,
                presetName = store.getEqPresetName(),
                presets = presets,
                bands = bands,
            )
        }
        store.setEqSnapshotJson(json.encodeToString(snapshot))
    }
}
