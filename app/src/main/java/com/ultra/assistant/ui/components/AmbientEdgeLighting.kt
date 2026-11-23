package com.ultra.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ultra.assistant.R
import com.ultra.assistant.data.ChatBackgroundStyle
import com.ultra.assistant.ui.theme.AmbientCyanTeal
import com.ultra.assistant.ui.theme.AmbientLightBlue
import com.ultra.assistant.ui.theme.AmbientSoftGreen
import com.ultra.assistant.ui.theme.ObsidianBg

@Composable
fun ChatBackgroundLayer(
    backgroundStyle: ChatBackgroundStyle,
    modifier: Modifier = Modifier
) {
    val drawableRes = when (backgroundStyle) {
        ChatBackgroundStyle.NEURAL_COSMOS -> R.drawable.img_bg_neural_cosmos
        ChatBackgroundStyle.QUANTUM_GRID -> R.drawable.img_bg_quantum_grid
        ChatBackgroundStyle.AURORA_SILK -> R.drawable.img_bg_aurora_silk
        ChatBackgroundStyle.PURE_OBSIDIAN -> null
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (drawableRes != null) {
            Image(
                painter = painterResource(id = drawableRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                ObsidianBg.copy(alpha = 0.72f),
                                ObsidianBg.copy(alpha = 0.64f),
                                ObsidianBg.copy(alpha = 0.84f)
                            )
                        )
                    )
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                ObsidianBg,
                                Color(0xFF091526),
                                ObsidianBg
                            )
                        )
                    )
            )
        }
    }
}

@Composable
fun AmbientEdgeGlow(
    isActive: Boolean,
    voiceState: String,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val visibilityAlpha by animateFloatAsState(
        targetValue = if (enabled && isActive) 1f else 0f,
        animationSpec = tween(durationMillis = 650),
        label = "edgeGlowFade"
    )
    if (visibilityAlpha <= 0.01f) return

    val transition = rememberInfiniteTransition(label = "ambientEdge")
    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "edgeSweep"
    )
    val breathe by transition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "edgeBreathe"
    )

    val intensityMultiplier = when (voiceState) {
        "LISTENING" -> 1.0f
        "SPEAKING" -> 0.92f
        "PROCESSING" -> 0.85f
        else -> 0.68f
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val alpha = 0.34f * visibilityAlpha * breathe * intensityMultiplier
        val edgeColors = listOf(
            AmbientLightBlue.copy(alpha = alpha),
            AmbientCyanTeal.copy(alpha = alpha * 0.95f),
            AmbientSoftGreen.copy(alpha = alpha),
            AmbientCyanTeal.copy(alpha = alpha * 0.7f),
            AmbientLightBlue.copy(alpha = alpha)
        )
        val softHaloStroke = 14.dp.toPx()
        val crispRimStroke = 2.5.dp.toPx()
        val corner = CornerRadius(26.dp.toPx(), 26.dp.toPx())

        rotate(degrees = sweepAngle, pivot = Offset(size.width / 2f, size.height / 2f)) {
            val brush = Brush.sweepGradient(
                colors = edgeColors,
                center = Offset(size.width / 2f, size.height / 2f)
            )
            // Outer low-brightness atmospheric edge diffusion
            drawRoundRect(
                brush = brush,
                topLeft = Offset(2f, 2f),
                size = Size(size.width - 4f, size.height - 4f),
                cornerRadius = corner,
                style = Stroke(width = softHaloStroke)
            )
            // Subtle crisp perimeter rim
            drawRoundRect(
                brush = brush,
                topLeft = Offset(crispRimStroke / 2f, crispRimStroke / 2f),
                size = Size(size.width - crispRimStroke, size.height - crispRimStroke),
                cornerRadius = corner,
                style = Stroke(width = crispRimStroke)
            )
        }
    }
}
