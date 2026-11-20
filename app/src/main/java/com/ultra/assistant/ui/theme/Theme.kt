package com.ultra.assistant.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val UltraDarkColorScheme = darkColorScheme(
    primary = ArcCyan,
    onPrimary = ObsidianBg,
    primaryContainer = SlateSurfaceElevated,
    onPrimaryContainer = ArcCyan,
    secondary = NeuralViolet,
    onSecondary = TextPrimary,
    secondaryContainer = SlateSurfaceElevated,
    onSecondaryContainer = TextPrimary,
    tertiary = EmeraldVerified,
    onTertiary = ObsidianBg,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder,
    error = CrimsonAlert,
    onError = ObsidianBg
)

val UltraShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun UltraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UltraDarkColorScheme,
        typography = UltraTypography,
        shapes = UltraShapes,
        content = content
    )
}
