package com.ultra.assistant.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ultra.assistant.data.UltraSettings
import com.ultra.assistant.ui.components.HudCard
import com.ultra.assistant.ui.components.StatusPill
import com.ultra.assistant.ui.components.VoiceOrbCanvas
import com.ultra.assistant.ui.theme.AmberWarning
import com.ultra.assistant.ui.theme.ArcCyan
import com.ultra.assistant.ui.theme.EmeraldVerified

@Composable
fun VoiceScreen(
    isServiceRunning: Boolean,
    voiceState: String,
    voiceLevel: Float,
    partialTranscript: String,
    lastRecognizedText: String,
    lastAgentReply: String,
    settings: UltraSettings,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onInterruptVoice: () -> Unit,
    onSelectLanguage: (String) -> Unit,
    onUpdateWakePhrase: (String) -> Unit,
    onUpdateVoiceTuning: (Float, Float) -> Unit
) {
    var wakePhraseInput by remember(settings.wakePhrase) { mutableStateOf(settings.wakePhrase) }
    var speed by remember(settings.voiceSpeed) { mutableStateOf(settings.voiceSpeed) }
    var pitch by remember(settings.voicePitch) { mutableStateOf(settings.voicePitch) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 640.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            HudCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatusPill(
                        label = if (isServiceRunning) "VOICE ENGINE: $voiceState" else "VOICE ENGINE: OFFLINE",
                        color = if (isServiceRunning) EmeraldVerified else AmberWarning
                    )
                    VoiceOrbCanvas(
                        voiceState = voiceState,
                        voiceLevel = voiceLevel,
                        isServiceActive = isServiceRunning,
                        modifier = Modifier.size(185.dp)
                    )
                    if (partialTranscript.isNotBlank()) {
                        Text(
                            text = "\"$partialTranscript\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ArcCyan
                        )
                    } else if (lastRecognizedText.isNotBlank()) {
                        Text(
                            text = "Heard: \"$lastRecognizedText\"",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (lastAgentReply.isNotBlank()) {
                        Text(
                            text = lastAgentReply,
                            style = MaterialTheme.typography.bodyMedium,
                            color = EmeraldVerified
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = if (isServiceRunning) onStopVoice else onStartVoice,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("voice_start_stop_button")
                    ) {
                        Icon(
                            imageVector = if (isServiceRunning) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Toggle voice service"
                        )
                        Text(if (isServiceRunning) " Stop Service" else " Start Voice")
                    }
                    OutlinedButton(
                        onClick = onInterruptVoice,
                        enabled = isServiceRunning,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("voice_interrupt_button")
                    ) {
                        Icon(Icons.Default.PanTool, contentDescription = "Interrupt speech")
                        Text(" Interrupt")
                    }
                }
            }
        }

        item {
            HudCard {
                Text("Multilingual Speech & TTS", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("বাংলা", "English", "हिन्दी").forEach { lang ->
                        FilterChip(
                            selected = settings.language == lang,
                            onClick = { onSelectLanguage(lang) },
                            label = { Text(lang) }
                        )
                    }
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

                Text("Speech Rate: ${"%.2f".format(speed)}x", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = speed,
                    onValueChange = { speed = it },
                    valueRange = 0.75f..1.35f,
                    onValueChangeFinished = { onUpdateVoiceTuning(speed, pitch) }
                )

                Text("Voice Pitch: ${"%.2f".format(pitch)}x", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = pitch,
                    onValueChange = { pitch = it },
                    valueRange = 0.75f..1.35f,
                    onValueChangeFinished = { onUpdateVoiceTuning(speed, pitch) }
                )
            }
        }

        item {
            HudCard(borderColor = AmberWarning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.GraphicEq, contentDescription = "Voice notice", tint = AmberWarning)
                    Text("Battery & Android Speech Notice", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Wake phrase detection uses Android SpeechRecognizer transcript matching inside a visible foreground microphone service. Always-on 24/7 listening cannot be guaranteed across OEM battery policies.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
