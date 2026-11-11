package com.resonar.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.resonar.app.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * KIT DE DISEÑO compartido por todas las pantallas.
 * Piezas chiquitas y sin dependencias nuevas: píldoras de navegación y selección,
 * barras de "equalizador" animadas (3 paths, costo casi cero) y el fondo degradado.
 */

/** Brillo violeta arriba de la pantalla. El resto queda en el negro del tema. */
@Composable
fun resonarScreenGradient(): Brush = Brush.verticalGradient(
    listOf(
        Color(0xFF241338),
        MaterialTheme.colorScheme.background,
    ),
)

/** Pestañas tipo píldora (Canciones / Artistas / Álbumes), sin TabRow plano. */
@Composable
fun ResonarTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val selectedNow = i == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selectedNow) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selectedNow) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (selectedNow) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

/** Chip de selección (tema claro/oscuro/sistema en Ajustes). */
@Composable
fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * Barras de equalizador que bailan mientras suena el tema actual.
 * Animación propia con Canvas: 3 rectángulos redondeados, nada de Lottie ni capas extra.
 */
@Composable
fun EqualizerBars(
    playing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (playing) {
        val t = rememberInfiniteTransition(label = "eq")
        val p0 by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(950, easing = LinearEasing)), label = "p0")
        val p1 by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1250, easing = LinearEasing)), label = "p1")
        val p2 by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(760, easing = LinearEasing)), label = "p2")
        BarsCanvas(modifier, color, Heights(
            0.3f + 0.6f * abs(sin(p0)),
            0.38f + 0.58f * abs(sin(p1 + 0.7f)),
            0.34f + 0.6f * abs(sin(p2 + 1.2f)),
        ))
    } else {
        BarsCanvas(modifier, color, Heights(0.35f, 0.62f, 0.45f))
    }
}

@JvmInline
private value class Heights(val values: List<Float>) {
    constructor(a: Float, b: Float, c: Float) : this(listOf(a, b, c))
}

@Composable
private fun BarsCanvas(modifier: Modifier, color: Color, heights: Heights) {
    Canvas(modifier = modifier.size(width = 15.dp, height = 15.dp)) {
        val hs = heights.values
        val n = hs.size
        val gap = size.width * 0.16f
        val barW = (size.width - gap * (n - 1)) / n
        hs.forEachIndexed { i, h ->
            val bh = size.height * h.coerceIn(0.12f, 1f)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barW + gap), size.height - bh),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}
