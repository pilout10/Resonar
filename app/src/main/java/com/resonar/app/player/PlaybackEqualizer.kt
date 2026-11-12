package com.resonar.app.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log
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
    /** Graves extra, de 0 a 1000. */
    val bass: Int = 0,
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
    private var bass: BassBoost? = null
    private var sessionId: Int = 0
    private var applying = false
    private var reportedMissing = false

    fun start() {
        store.setOnPreferenceChanged { key ->
            if (key == PlaybackStateStore.KEY_BASS) applyBass() else apply(publish = true)
        }
        refreshSession()
    }

    /** La sesión de audio aparece cuando el player prepara el primer buffer. */
    fun refreshSession() {
        val id = runCatching { player.audioSessionId }.getOrDefault(0)
        if (effect != null && (id <= 0 || id == sessionId)) return
        val attached = if (id > 0) openEffect(id) else null
        val chosen = attached ?: if (effect == null) openEffect(0) else null
        if (chosen == null) {
            if (effect == null && !reportedMissing) {
                reportedMissing = true
                publish(available = false)
            }
            return
        }
        reportedMissing = false
        runCatching { effect?.release() }
        runCatching { bass?.release() }
        effect = chosen
        sessionId = if (attached != null) id else 0
        bass = runCatching { BassBoost(0, sessionId) }
            .onFailure { Log.w(TAG, "sin graves extra: ${it.message}") }
            .getOrNull()
        Log.i(TAG, "ecualizador en sesión $sessionId")
        apply(publish = true)
    }

    private fun openEffect(id: Int): Equalizer? =
        runCatching { Equalizer(0, id) }
            .onFailure { Log.w(TAG, "no se pudo crear en la sesión $id: ${it.message}") }
            .getOrNull()

    fun release() {
        runCatching { effect?.release() }
        runCatching { bass?.release() }
        effect = null
        bass = null
        sessionId = 0
    }

    private fun apply(publish: Boolean) {
        if (applying) return
        val eq = effect ?: return
        applying = true
        try {
            val storedName = store.getEqPresetName()
            // El interruptor en ON con preset "Apagado" volvía a apagarse solo.
            val name = if (store.isEqEnabled() && storedName == "Apagado") {
                store.setEqPresetName("Personalizado")
                "Personalizado"
            } else {
                storedName
            }
            val enabled = store.isEqEnabled() && name != "Apagado"
            runCatching {
                // En este teléfono setBandLevel antes de enabled se pierde:
                // el personalizado volvía a cero al soltar la barra.
                eq.enabled = enabled
                if (enabled) {
                    when {
                        name == "Personalizado" -> applyLevels(eq)
                        name in CURVES -> {
                            store.setEqLevels(curveLevels(eq, name))
                            applyLevels(eq)
                        }
                        else -> {
                            val index = (0 until eq.numberOfPresets.toInt()).firstOrNull {
                                eq.getPresetName(it.toShort()) == name
                            }
                            if (index != null) eq.usePreset(index.toShort()) else applyLevels(eq)
                        }
                    }
                }
            }
            applyBass()
            if (publish || (name == "Personalizado" && store.getEqLevels().isEmpty())) {
                // Tras un preset del sistema guardamos las bandas para poder volver a "Personalizado".
                if (name != "Personalizado" && name != "Apagado" && name !in CURVES) {
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

    /** Curvas nuestras. No dependen de que el preset del teléfono haga algo. */
    private fun curveLevels(eq: Equalizer, name: String): List<Int> {
        val count = eq.numberOfBands.toInt()
        val range = eq.bandLevelRange
        val min = range[0].toInt()
        val max = range[1].toInt()
        val shape = when (name) {
            "Más graves" -> floatArrayOf(0.75f, 0.4f, 0f, -0.2f, -0.25f)
            "Voces" -> floatArrayOf(-0.25f, -0.05f, 0.55f, 0.4f, 0f)
            "Brillo" -> floatArrayOf(-0.2f, -0.05f, 0.1f, 0.45f, 0.7f)
            else -> FloatArray(count)
        }
        return List(count) { index ->
            val fraction = shape.getOrElse(index) { 0f }
            val level = if (fraction >= 0f) (fraction * max).toInt() else (fraction * -min).toInt()
            level.coerceIn(min, max)
        }
    }

    private fun applyBass() {
        val boost = bass ?: return
        val strength = store.getBassStrength()
        runCatching {
            boost.enabled = strength > 0
            boost.setStrength(strength.toShort())
        }
    }

    private fun readLevels(eq: Equalizer): List<Int> =
        (0 until eq.numberOfBands.toInt()).map { eq.getBandLevel(it.toShort()).toInt() }

    private fun publish(available: Boolean) {
        val eq = effect
        val snapshot = if (!available || eq == null) {
            EqSnapshot(
                available = false,
                presetName = store.getEqPresetName(),
                enabled = store.isEqEnabled(),
            )
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
                bass = store.getBassStrength(),
            )
        }
        store.setEqSnapshotJson(json.encodeToString(snapshot))
    }

    private companion object {
        const val TAG = "ResonarEq"
        val CURVES = setOf("Plano", "Más graves", "Voces", "Brillo")
    }
}
