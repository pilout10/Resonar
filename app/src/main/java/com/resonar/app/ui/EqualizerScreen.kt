package com.resonar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.resonar.app.R
import com.resonar.app.player.EqBandUi
import com.resonar.app.player.PlaybackUiState

@Composable
fun EqualizerScreen(viewModel: ResonarViewModel, playback: PlaybackUiState, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Volver")
            }
            Text("Ecualizador", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = playback.eqEnabled,
                onCheckedChange = viewModel::setEqEnabled,
                enabled = playback.eqAvailable,
            )
        }
        Text(
            if (playback.eqAvailable) {
                "Ajusta el sonido de lo que está sonando. Los presets salen del teléfono."
            } else {
                "Reproducí una canción para activar el ecualizador. Si este teléfono no lo permite, el audio sigue igual."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        if (playback.eqAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                PresetChip("Apagado", playback.eqPresetName == "Apagado" || !playback.eqEnabled) {
                    viewModel.setEqPreset("Apagado")
                }
                playback.eqPresets.forEach { name ->
                    PresetChip(name, playback.eqEnabled && playback.eqPresetName == name) {
                        viewModel.setEqPreset(name)
                    }
                }
                PresetChip("Personalizado", playback.eqEnabled && playback.eqPresetName == "Personalizado") {
                    viewModel.setEqPreset("Personalizado")
                }
            }
            Spacer(Modifier.height(18.dp))
            playback.eqBands.forEach { band ->
                BandSlider(band, viewModel)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun BandSlider(band: EqBandUi, viewModel: ResonarViewModel) {
    var dragging by remember(band.index) { mutableStateOf(false) }
    var local by remember(band.index, band.levelMb) { mutableFloatStateOf(band.levelMb.toFloat()) }
    val shown = if (dragging) local else band.levelMb.toFloat()
    val min = band.minMb.toFloat()
    val max = band.maxMb.toFloat().coerceAtLeast(min + 1f)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(band.label, modifier = Modifier.weight(0.28f), style = MaterialTheme.typography.bodyMedium)
        Text(
            "%+.1f dB".format(shown / 100f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        value = shown.coerceIn(min, max),
        onValueChange = {
            dragging = true
            local = it
            viewModel.previewEqBand(band.index, it.toInt())
        },
        onValueChangeFinished = {
            viewModel.commitEqBand(band.index, local.toInt())
            dragging = false
        },
        valueRange = min..max,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
        ),
    )
}
