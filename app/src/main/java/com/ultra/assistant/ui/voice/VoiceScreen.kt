package com.ultra.assistant.ui.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ultra.assistant.R
import com.ultra.assistant.data.AiOperatingMode
import com.ultra.assistant.data.UltraSettings
import com.ultra.assistant.data.VoiceGender
import com.ultra.assistant.ui.components.HudCard
import com.ultra.assistant.ui.components.StatusPill
import com.ultra.assistant.ui.components.VoiceOrbCanvas
import com.ultra.assistant.ui.theme.AmberWarning
import com.ultra.assistant.ui.theme.ArcCyan
import com.ultra.assistant.ui.theme.CrimsonAlert
import com.ultra.assistant.ui.theme.EmeraldVerified

@Composable
fun VoiceScreen(
    isServiceRunning: Boolean,
    voiceState: String,
    voiceLevel: Float,
    partialTranscript: String,
    lastRecognizedText: String,
    lastAgentReply: String,
    streamingReply: String,
    isOnline: Boolean,
    isReconnecting: Boolean,
    settings: UltraSettings,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onInterruptVoice: () -> Unit,
    onStopSpeaking: () -> Unit,
    onEnableFloatingDot: () -> Unit,
    onTestVoice: () -> Unit,
    onSelectVoiceGender: (VoiceGender) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onUpdateWakePhrase: (String) -> Unit,
    onUpdateVoiceTuning: (Float, Float) -> Unit,
    onUpdateVoiceVolume: (Float) -> Unit,
    onToggleContinuousListening: (Boolean) -> Unit
) {
    var wakePhraseInput by remember(settings.wakePhrase) { mutableStateOf(settings.wakePhrase) }
    var speed by remember(settings.voiceSpeed) { mutableFloatStateOf(settings.voiceSpeed) }
    var pitch by remember(settings.voicePitch) { mutableFloatStateOf(settings.voicePitch) }
    var volume by remember(settings.voiceVolume) { mutableFloatStateOf(settings.voiceVolume) }

    val effectiveOffline = settings.operatingMode == AiOperatingMode.OFFLINE || !isOnline
    val speaking = voiceState.equals("SPEAKING", true)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 680.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            HudCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_ultra_avatar),
                            contentDescription = "ULTRA AI Voice Call",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                        )
                        StatusPill(
                            label = if (isServiceRunning) "LIVE CALL: $voiceState" else "VOICE READY",
                            color = if (isServiceRunning) EmeraldVerified else ArcCyan
                        )
                    }
                    StatusPill(
                        label = when {
                            isReconnecting -> "RECONNECTING"
                            effectiveOffline -> "OFFLINE VOICE"
                            else -> "ONLINE VOICE"
                        },
                        color = when {
                            isReconnecting -> ArcCyan
                            effectiveOffline -> AmberWarning
                            else -> EmeraldVerified
                        }
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    VoiceOrbCanvas(
                        voiceState = voiceState,
                        voiceLevel = voiceLevel,
                        isServiceActive = isServiceRunning || speaking,
                        modifier = Modifier
                            .size(205.dp)
                            .clickable {
                                if (speaking) onInterruptVoice()
                                else if (isServiceRunning) onStopVoice()
                                else onStartVoice()
                            }
                    )

                    Text(
                        text = when {
                            speaking -> "Speaking (${settings.voiceGender.label}) — tap orb to interrupt"
                            voiceState == "LISTENING" -> "Listening naturally — speak anytime"
                            voiceState == "PROCESSING" -> "Thinking about your request..."
                            isServiceRunning -> "Connected • Natural turn-taking active"
                            else -> "Start a live phone-style AI conversation"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (partialTranscript.isNotBlank() || lastRecognizedText.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = if (partialTranscript.isNotBlank()) "\"$partialTranscript\"" else "You: \"$lastRecognizedText\"",
                                style = MaterialTheme.typography.bodyLarge,
                                color = ArcCyan,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    val displayReply = streamingReply.ifBlank { lastAgentReply }
                    if (displayReply.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                            border = BorderStroke(1.dp, EmeraldVerified.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = displayReply,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = if (isServiceRunning) onStopVoice else onStartVoice,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isServiceRunning) CrimsonAlert else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("voice_start_stop_button")
                    ) {
                        Icon(
                            imageVector = if (isServiceRunning) Icons.Default.CallEnd else Icons.Default.Mic,
                            contentDescription = "Toggle voice conversation"
                        )
                        Text(
                            if (isServiceRunning) " End Voice Call" else " Start Voice Call",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (speaking) {
                        OutlinedButton(
                            onClick = onInterruptVoice,
                            modifier = Modifier
                                .height(50.dp)
                                .testTag("voice_interrupt_button")
                        ) {
                            Icon(Icons.Default.PanTool, contentDescription = "Interrupt speaking")
                            Text(" Interrupt")
                        }
                        OutlinedButton(
                            onClick = onStopSpeaking,
                            modifier = Modifier
                                .height(50.dp)
                                .testTag("voice_stop_speaking_button")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop speaking")
                        }
                    } else {
                        OutlinedButton(
                            onClick = onEnableFloatingDot,
                            modifier = Modifier
                                .height(50.dp)
                                .testTag("voice_floating_bubble_button")
                        ) {
                            Icon(Icons.Default.BubbleChart, contentDescription = "Floating bubble")
                            Text(" Bubble")
                        }
                    }
                }
            }
        }

        item {
            HudCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Human Voice & Language", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = onTestVoice,
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("test_voice_button")
                    ) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = "Preview voice")
                        Text(" Preview")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceGender.entries.forEach { gender ->
                        FilterChip(
                            selected = settings.voiceGender == gender,
                            onClick = { onSelectVoiceGender(gender) },
                            label = { Text(gender.label) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("English", "বাংলা", "हिन्दी").forEach { lang ->
                        FilterChip(
                            selected = settings.language == lang,
                            onClick = { onSelectLanguage(lang) },
                            label = { Text(lang) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Continuous Conversation", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Automatically listen after AI finishes speaking with natural turn-taking.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.continuousListening,
                        onCheckedChange = onToggleContinuousListening
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = wakePhraseInput,
                        onValueChange = { wakePhraseInput = it },
                        label = { Text("Wake Phrase") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("wake_phrase_input")
                    )
                    Button(
                        onClick = { onUpdateWakePhrase(wakePhraseInput) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("save_wake_phrase_button")
                    ) {
                        Text("Save")
                    }
                }

                Text("Speaking Speed: ${"%.2f".format(speed)}x", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = speed,
                    onValueChange = { speed = it },
                    valueRange = 0.75f..1.35f,
                    onValueChangeFinished = { onUpdateVoiceTuning(speed, pitch) }
                )

                Text("Voice Pitch Fine-Tune: ${"%.2f".format(pitch)}x", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = pitch,
                    onValueChange = { pitch = it },
                    valueRange = 0.75f..1.35f,
                    onValueChangeFinished = { onUpdateVoiceTuning(speed, pitch) }
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Voice volume", tint = ArcCyan)
                    Text("Voice Volume: ${(volume * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    valueRange = 0.1f..1.0f,
                    onValueChangeFinished = { onUpdateVoiceVolume(volume) }
                )
            }
        }
    }
}
