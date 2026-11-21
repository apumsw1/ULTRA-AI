package com.ultra.assistant.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultra.assistant.brain.AgentExecutionReport
import com.ultra.assistant.data.UltraSettings
import com.ultra.assistant.memory.ConversationMessageEntity
import com.ultra.assistant.permissions.PermissionAuditSnapshot
import com.ultra.assistant.tools.PermissionRequirement
import com.ultra.assistant.ui.theme.ArcCyan
import com.ultra.assistant.ui.theme.NeuralViolet
import com.ultra.assistant.ui.theme.ObsidianBg
import com.ultra.assistant.ui.theme.TextPrimary
import com.ultra.assistant.ui.theme.TextSecondary
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HomeScreen(
    isServiceRunning: Boolean,
    voiceState: String,
    settings: UltraSettings,
    permissions: PermissionAuditSnapshot,
    isBusy: Boolean,
    latestReport: AgentExecutionReport?,
    conversations: List<ConversationMessageEntity>,
    onStartAssistant: () -> Unit,
    onStopAssistant: () -> Unit,
    onEnableFloatingDot: () -> Unit,
    onSubmitCommand: (String) -> Unit,
    onFixPermission: (PermissionRequirement) -> Unit
) {
    var commandText by rememberSaveable { mutableStateOf("") }
    val listening = isServiceRunning && voiceState.equals("LISTENING", true)
    val speaking = isServiceRunning && voiceState.equals("SPEAKING", true)
    val status = when {
        speaking -> "SPEAKING"
        listening -> "LISTENING"
        isBusy || voiceState.equals("PROCESSING", true) -> "THINKING"
        isServiceRunning -> "VOICE SESSION ACTIVE"
        else -> "TAP TO CONNECT"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "ULTRA",
                    color = TextPrimary,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 5.sp
                )
                Text(
                    "PERSONAL AI SYSTEM",
                    color = TextSecondary,
                    fontSize = 9.sp,
                    letterSpacing = 2.5.sp
                )
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF102A3B))
                    .padding(horizontal = 13.dp, vertical = 8.dp)
            ) {
                Text(
                    if (isServiceRunning) "ONLINE SESSION" else "STANDBY",
                    color = if (isServiceRunning) ArcCyan else TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            JarvisCore(
                active = isServiceRunning,
                listening = listening,
                speaking = speaking,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(330.dp)
            )
            Text(
                status,
                color = if (isServiceRunning) ArcCyan else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    speaking -> "ULTRA is responding"
                    listening -> "I'm listening. Go ahead."
                    isBusy -> "Processing your request"
                    isServiceRunning -> "Speak naturally. I'm here."
                    else -> "Your voice-first assistant"
                },
                color = TextSecondary,
                fontSize = 14.sp
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = if (isServiceRunning) onStopAssistant else onStartAssistant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) Color(0xFF17364A) else Color(0xFF007F9B),
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = null
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (isServiceRunning) "END CONVERSATION" else "START CONVERSATION",
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp
                )
                if (isBusy) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Or type a command...", color = TextSecondary) },
                trailingIcon = {
                    androidx.compose.material3.IconButton(
                        onClick = {
                            val command = commandText.trim()
                            if (command.isNotEmpty() && !isBusy) {
                                commandText = ""
                                onSubmitCommand(command)
                            }
                        },
                        enabled = commandText.isNotBlank() && !isBusy
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send command",
                            tint = ArcCyan
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        val command = commandText.trim()
                        if (command.isNotEmpty() && !isBusy) {
                            commandText = ""
                            onSubmitCommand(command)
                        }
                    }
                ),
                shape = CircleShape
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun JarvisCore(
    active: Boolean,
    listening: Boolean,
    speaking: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "jarvis_core")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "core_rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = if (active) 1.045f else 1.015f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (speaking) 650 else 1900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core_pulse"
    )

    Canvas(modifier = modifier) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) * 0.37f
        val accent = if (speaking) NeuralViolet else ArcCyan
        val glowAlpha = if (active) 0.24f else 0.12f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = glowAlpha),
                    ArcCyan.copy(alpha = 0.07f),
                    Color.Transparent
                ),
                center = center,
                radius = radius * 1.5f
            ),
            center = center,
            radius = radius * 1.5f
        )

        drawCircle(
            color = accent.copy(alpha = 0.18f),
            center = center,
            radius = radius * pulse,
            style = Stroke(width = 1.4.dp.toPx())
        )
        drawCircle(
            color = ArcCyan.copy(alpha = 0.28f),
            center = center,
            radius = radius * 0.82f,
            style = Stroke(width = 1.dp.toPx())
        )

        rotate(rotation, pivot = center) {
            drawArc(
                color = accent.copy(alpha = 0.95f),
                startAngle = 12f,
                sweepAngle = 92f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = ArcCyan.copy(alpha = 0.7f),
                startAngle = 195f,
                sweepAngle = 58f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(center.x - radius * 0.82f, center.y - radius * 0.82f),
                size = androidx.compose.ui.geometry.Size(radius * 1.64f, radius * 1.64f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        for (index in 0 until 48) {
            val angle = Math.toRadians(index * 7.5)
            val inner = radius * 1.12f
            val outer = radius * if (index % 4 == 0) 1.23f else 1.17f
            val start = androidx.compose.ui.geometry.Offset(
                center.x + cos(angle).toFloat() * inner,
                center.y + sin(angle).toFloat() * inner
            )
            val end = androidx.compose.ui.geometry.Offset(
                center.x + cos(angle).toFloat() * outer,
                center.y + sin(angle).toFloat() * outer
            )
            drawLine(
                color = accent.copy(alpha = if (index % 4 == 0) 0.75f else 0.28f),
                start = start,
                end = end,
                strokeWidth = if (index % 4 == 0) 2.dp.toPx() else 1.dp.toPx()
            )
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFB9F8FF), accent, Color(0xFF07516D)),
                center = center,
                radius = radius * 0.55f
            ),
            center = center,
            radius = radius * 0.48f * pulse
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.82f),
            center = center,
            radius = radius * 0.055f
        )
    }
}
