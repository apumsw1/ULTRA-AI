package com.ultra.assistant.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ultra.assistant.brain.AgentExecutionReport
import com.ultra.assistant.data.UltraSettings
import com.ultra.assistant.memory.ConversationMessageEntity
import com.ultra.assistant.permissions.PermissionAuditSnapshot
import com.ultra.assistant.tools.PermissionRequirement
import com.ultra.assistant.ui.components.HudCard
import com.ultra.assistant.ui.components.StatusPill
import com.ultra.assistant.ui.theme.AmberWarning
import com.ultra.assistant.ui.theme.ArcCyan
import com.ultra.assistant.ui.theme.CrimsonAlert
import com.ultra.assistant.ui.theme.EmeraldVerified
import com.ultra.assistant.ui.theme.NeuralViolet

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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 640.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            HudCard(borderColor = if (isServiceRunning) EmeraldVerified else ArcCyan) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Command Center", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Say '${settings.wakePhrase}' or type a multi-step command.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(
                        label = if (isServiceRunning) voiceState else "STANDBY",
                        color = if (isServiceRunning) EmeraldVerified else AmberWarning
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = if (isServiceRunning) onStopAssistant else onStartAssistant,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("toggle_assistant_button")
                    ) {
                        Icon(
                            imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isServiceRunning) "Stop Assistant" else "Start Assistant"
                        )
                        Text(if (isServiceRunning) " Stop Voice" else " Start Voice")
                    }
                    OutlinedButton(
                        onClick = onEnableFloatingDot,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("floating_dot_button")
                    ) {
                        Icon(Icons.Default.BubbleChart, contentDescription = "Floating U Overlay")
                        Text(" Floating U")
                    }
                }
            }
        }

        item {
            HudCard {
                Text("AI Agent Command Bar", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = commandText,
                        onValueChange = { commandText = it },
                        placeholder = { Text("Ask ULTRA AI or run a tool...") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("command_input")
                    )
                    IconButton(
                        onClick = {
                            if (commandText.isNotBlank()) {
                                val cmd = commandText
                                commandText = ""
                                onSubmitCommand(cmd)
                            }
                        },
                        enabled = !isBusy && commandText.isNotBlank(),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("send_command_button")
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send command", tint = ArcCyan)
                        }
                    }
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "Device info",
                        "Read notifications",
                        "Inspect screen",
                        "Open Chrome",
                        "Search weather"
                    ).forEach { quick ->
                        AssistChip(
                            onClick = { onSubmitCommand(quick) },
                            label = { Text(quick) }
                        )
                    }
                }
            }
        }

        if (latestReport != null) {
            item {
                HudCard(borderColor = NeuralViolet) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Agent Execution Trace", style = MaterialTheme.typography.titleMedium)
                        StatusPill(label = latestReport.providerUsed, color = NeuralViolet)
                    }
                    Text(
                        latestReport.finalReply,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    latestReport.steps.forEach { step ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (step.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                contentDescription = step.stage,
                                tint = if (step.success) EmeraldVerified else AmberWarning,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "[${step.stage}] ${step.detail}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (latestReport.missingPermission != null) {
                        Button(
                            onClick = { onFixPermission(latestReport.missingPermission) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("fix_missing_permission_button")
                        ) {
                            Text("Grant ${latestReport.missingPermission.displayName}")
                        }
                    }
                }
            }
        }

        item {
            HudCard {
                Text("System Readiness", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusPill("Mic: ${if (permissions.microphoneGranted) "OK" else "NEED"}", if (permissions.microphoneGranted) EmeraldVerified else CrimsonAlert)
                    StatusPill("Accessibility: ${if (permissions.accessibilityEnabled) "ON" else "OFF"}", if (permissions.accessibilityEnabled) EmeraldVerified else AmberWarning)
                    StatusPill("Notifications: ${if (permissions.notificationListenerEnabled) "ON" else "OFF"}", if (permissions.notificationListenerEnabled) EmeraldVerified else AmberWarning)
                    StatusPill("Admin: ${if (permissions.deviceAdminActive) "ACTIVE" else "OFF"}", if (permissions.deviceAdminActive) EmeraldVerified else ArcCyan)
                }
            }
        }

        if (conversations.isNotEmpty()) {
            item {
                Text("Recent Conversation", style = MaterialTheme.typography.titleMedium)
            }
            items(conversations.takeLast(8).reversed(), key = { it.id }) { msg ->
                HudCard(
                    borderColor = if (msg.role == "user") ArcCyan else EmeraldVerified
                ) {
                    Text(
                        text = if (msg.role == "user") "YOU" else "ULTRA AI",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (msg.role == "user") ArcCyan else EmeraldVerified
                    )
                    Text(msg.content, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
