package com.ultra.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ultra.assistant.ui.theme.AmberWarning
import com.ultra.assistant.ui.theme.ArcCyan
import com.ultra.assistant.ui.theme.EmeraldVerified
import com.ultra.assistant.ui.theme.NeuralViolet
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun VoiceOrbCanvas(
    voiceState: String,
    voiceLevel: Float,
    isServiceActive: Boolean,
    modifier: Modifier = Modifier.size(180.dp)
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbAngle"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbPulse"
    )

    val (primary, secondary) = when {
        !isServiceActive || voiceState in setOf("IDLE", "STOPPED") -> Color(0xFF4B5C73) to Color(0xFF283445)
        voiceState == "USER_SPEAKING" -> Color(0xFF5CF4FF) to ArcCyan
        voiceState in setOf("LISTENING", "CONNECTING") -> ArcCyan to Color(0xFF3A7DFF)
        voiceState in setOf("PROCESSING", "THINKING") -> NeuralViolet to Color(0xFFFF4FD8)
        voiceState in setOf("SPEAKING", "EXECUTING_ACTION", "VERIFYING") -> EmeraldVerified to ArcCyan
        voiceState in setOf("OFFLINE", "RECONNECTING", "INTERRUPTED") -> AmberWarning to Color(0xFFFF6B6B)
        voiceState == "ERROR" -> Color(0xFFFF4D6D) to Color(0xFFFF8FA3)
        else -> ArcCyan to NeuralViolet
    }

    val rmsBoost = if (isServiceActive) (voiceLevel.coerceIn(0f, 12f) / 40f) else 0f

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseRadius = (minOf(size.width, size.height) * 0.24f) * pulse * (1f + rmsBoost)

        // Outer ambient halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = 0.28f), Color.Transparent),
                center = Offset(cx, cy),
                radius = baseRadius * 2.1f
            ),
            radius = baseRadius * 2.1f,
            center = Offset(cx, cy)
        )

        // Concentric HUD rings
        drawCircle(
            color = primary.copy(alpha = 0.45f),
            radius = baseRadius * 1.48f,
            center = Offset(cx, cy),
            style = Stroke(width = 2.5f)
        )
        drawCircle(
            color = secondary.copy(alpha = 0.4f),
            radius = baseRadius * 1.72f,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5f)
        )

        // Orbiting neural particles
        if (isServiceActive) {
            for (i in 0 until 8) {
                val rad = Math.toRadians((angle + i * 45f).toDouble())
                val dist = baseRadius * (1.48f + (i % 2) * 0.24f)
                val px = cx + (cos(rad) * dist).toFloat()
                val py = cy + (sin(rad) * dist).toFloat()
                drawCircle(
                    color = if (i % 2 == 0) primary else secondary,
                    radius = 4f + (i % 3) * 1.5f,
                    center = Offset(px, py)
                )
            }
        }

        // Core sphere
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, primary, secondary),
                center = Offset(cx - baseRadius * 0.2f, cy - baseRadius * 0.2f),
                radius = baseRadius * 1.15f
            ),
            radius = baseRadius,
            center = Offset(cx, cy)
        )
    }
}
