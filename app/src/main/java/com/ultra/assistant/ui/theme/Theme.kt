package com.ultra.assistant.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.ultra.assistant.data.UltraThemeStyle

private val CyberCyanColorScheme = darkColorScheme(
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

private val EmeraldPulseColorScheme = darkColorScheme(
    primary = AmbientSoftGreen,
    onPrimary = EmeraldBg,
    primaryContainer = EmeraldSurfaceElevated,
    onPrimaryContainer = AmbientSoftGreen,
    secondary = AmbientLightBlue,
    onSecondary = EmeraldBg,
    secondaryContainer = EmeraldSurfaceElevated,
    onSecondaryContainer = TextPrimary,
    tertiary = ArcCyan,
    onTertiary = EmeraldBg,
    background = EmeraldBg,
    onBackground = TextPrimary,
    surface = EmeraldSurface,
    onSurface = TextPrimary,
    surfaceVariant = EmeraldSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = EmeraldBorder,
    error = CrimsonAlert,
    onError = EmeraldBg
)

private val AuroraVioletColorScheme = darkColorScheme(
    primary = NeuralViolet,
    onPrimary = TextPrimary,
    primaryContainer = AuroraSurfaceElevated,
    onPrimaryContainer = TextPrimary,
    secondary = AmbientLightBlue,
    onSecondary = AuroraBg,
    secondaryContainer = AuroraSurfaceElevated,
    onSecondaryContainer = TextPrimary,
    tertiary = AmbientSoftGreen,
    onTertiary = AuroraBg,
    background = AuroraBg,
    onBackground = TextPrimary,
    surface = AuroraSurface,
    onSurface = TextPrimary,
    surfaceVariant = AuroraSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = AuroraBorder,
    error = CrimsonAlert,
    onError = AuroraBg
)

val UltraShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun UltraTheme(
    themeStyle: UltraThemeStyle = UltraThemeStyle.CYBER_CYAN,
    content: @Composable () -> Unit
) {
    val scheme = when (themeStyle) {
        UltraThemeStyle.CYBER_CYAN -> CyberCyanColorScheme
        UltraThemeStyle.EMERALD_PULSE -> EmeraldPulseColorScheme
        UltraThemeStyle.AURORA_VIOLET -> AuroraVioletColorScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = UltraTypography,
        shapes = UltraShapes,
        content = content
    )
}
