package com.resonar.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Identidad visual de Resonar: violeta musical + acento verde-azulado.
 * Paleta propia (no dinámica) para que la app se vea igual en cualquier Motorola,
 * tipografía con títulos más presentes y esquinas redondeadas en todo el sistema.
 */

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC4B5FD),
    onPrimary = Color(0xFF2E1064),
    primaryContainer = Color(0xFF7C3AED),
    onPrimaryContainer = Color(0xFFF5F3FF),
    secondary = Color(0xFF93C5FD),
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF1D4ED8),
    onSecondaryContainer = Color(0xFFE0F2FE),
    background = Color(0xFF14121A),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF14121A),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF3A3448),
    onSurfaceVariant = Color(0xFFE7E1F2),
    surfaceContainerLow = Color(0xFF16131C),
    surfaceContainer = Color(0xFF1C1824),
    surfaceContainerHigh = Color(0xFF24202E),
    surfaceContainerHighest = Color(0xFF2E2A3A),
    outline = Color(0xFF8D879C),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6B3FBF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADCFF),
    onPrimaryContainer = Color(0xFF270D51),
    secondary = Color(0xFF2E6C64),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB7E8DC),
    onSecondaryContainer = Color(0xFF00201B),
    background = Color(0xFFF7F4FB),
    onBackground = Color(0xFF1B1622),
    surface = Color(0xFFF7F4FB),
    onSurface = Color(0xFF1B1622),
    surfaceVariant = Color(0xFFE9E2F1),
    onSurfaceVariant = Color(0xFF4A444F),
    surfaceContainerLow = Color(0xFFF3EEF8),
    surfaceContainer = Color(0xFFEDE7F4),
    surfaceContainerHigh = Color(0xFFE7E0F0),
    surfaceContainerHighest = Color(0xFFDDD6E8),
    outline = Color(0xFF7C7487),
    error = Color(0xFFB3261E),
)

/** Tipografía propia: títulos más grandes y con presencia, cuerpo cómodo. */
private val ResonarTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium),
)

/** Esquinas redondeadas por defecto: todo el Material se curva (cards, botones, menús). */
private val ResonarShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ResonarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ResonarTypography,
        shapes = ResonarShapes,
        content = content,
    )
}
