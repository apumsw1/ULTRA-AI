package com.ultra.assistant.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ultra.assistant.R
import com.ultra.assistant.brain.AgentExecutionReport
import com.ultra.assistant.data.AiOperatingMode
import com.ultra.assistant.data.AiSpeedProfile
import com.ultra.assistant.data.UltraSettings
import com.ultra.assistant.memory.ConversationMessageEntity
import com.ultra.assistant.memory.ConversationSessionEntity
import com.ultra.assistant.permissions.PermissionAuditSnapshot
import com.ultra.assistant.tools.PermissionRequirement
import com.ultra.assistant.ui.theme.AmberWarning
import com.ultra.assistant.ui.theme.ArcCyan
import com.ultra.assistant.ui.theme.EmeraldVerified
import com.ultra.assistant.ui.theme.NeuralViolet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isServiceRunning: Boolean,
    voiceState: String,
    partialTranscript: String,
    isOnline: Boolean,
    isReconnecting: Boolean,
    settings: UltraSettings,
    permissions: PermissionAuditSnapshot,
    isBusy: Boolean,
    isStreaming: Boolean,
    streamingReply: String,
    liveAgentStatus: String,
    onlineDiagnostic: String,
    latestReport: AgentExecutionReport?,
    conversations: List<ConversationMessageEntity>,
    sessions: List<ConversationSessionEntity>,
    activeSessionId: String,
    onStartAssistant: () -> Unit,
    onStopAssistant: () -> Unit,
    onStopSpeaking: () -> Unit,
    onInterruptVoice: () -> Unit,
    onStopAgentExecution: () -> Unit,
    onEnableFloatingDot: () -> Unit,
    onOpenVoiceScreen: () -> Unit,
    onSubmitCommand: (String) -> Unit,
    onSpeakText: (String) -> Unit,
    onStartNewChat: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onFixPermission: (PermissionRequirement) -> Unit,
    onCycleOperatingMode: () -> Unit,
    onCycleSpeedProfile: () -> Unit,
    onRequestAllRuntimePermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    var commandText by rememberSaveable { mutableStateOf("") }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showPermissionsSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val effectiveOnline = settings.operatingMode != AiOperatingMode.OFFLINE && isOnline
    val speaking = voiceState.equals("SPEAKING", true)
    val listening = isServiceRunning && (voiceState.equals("LISTENING", true) || voiceState.equals("USER_SPEAKING", true))

    val grantedCount = listOf(
        permissions.microphoneGranted && permissions.notificationsGranted,
        permissions.contactsGranted && permissions.callPhoneGranted,
        permissions.accessibilityEnabled,
        permissions.notificationListenerEnabled,
        permissions.overlayGranted,
        permissions.batteryOptimizationIgnored,
        permissions.deviceAdminActive
    ).count { it }

    LaunchedEffect(conversations.size, streamingReply, isBusy, liveAgentStatus) {
        val total = conversations.size + if (isBusy || isStreaming || streamingReply.isNotBlank()) 1 else 0
        if (total > 0) {
            runCatching { listState.animateScrollToItem(total - 1) }
        }
    }

    if (showHistorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showHistorySheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Session History (RAM-Only)", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        onStartNewChat()
                        showHistorySheet = false
                    }) {
                        Text("+ New Session")
                    }
                }
                if (sessions.isEmpty()) {
                    Text(
                        "Temporary session only — conversations clear automatically when you close the app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                } else {
                    sessions.take(20).forEach { session ->
                        val isCurrent = session.id == activeSessionId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectSession(session.id)
                                    showHistorySheet = false
                                },
                            shape = MaterialTheme.shapes.medium,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            border = if (isCurrent) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                            } else null
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = session.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onDeleteSession(session.id) }) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Delete conversation",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }

    if (showPermissionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPermissionsSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Full Access Permission Setup ($grantedCount/7)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Grant Microphone, Accessibility, Overlay, Contacts, and Notification access so ULTRA AI can operate inside apps and hold continuous natural voice conversations.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onRequestAllRuntimePermissions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("wizard_grant_runtime_button")
                ) {
                    Text("Grant Runtime Permissions (Mic, Notif, Contacts, Call)")
                }

                WizardPermissionRow(
                    title = "Microphone & Notifications",
                    subtitle = "Required for continuous realistic voice conversation",
                    granted = permissions.microphoneGranted && permissions.notificationsGranted,
                    onGrant = { onFixPermission(PermissionRequirement.MICROPHONE) }
                )
                WizardPermissionRow(
                    title = "Accessibility Service (In-App Control)",
                    subtitle = "Required to observe screens, tap buttons, and type inside apps",
                    granted = permissions.accessibilityEnabled,
                    onGrant = { onFixPermission(PermissionRequirement.ACCESSIBILITY) }
                )
                WizardPermissionRow(
                    title = "Floating Overlay Bubble",
                    subtitle = "Keeps Jarvis floating voice orb visible across other apps",
                    granted = permissions.overlayGranted,
                    onGrant = { onFixPermission(PermissionRequirement.OVERLAY) }
                )
                WizardPermissionRow(
                    title = "Notification Listener",
                    subtitle = "Lets ULTRA read & summarize unread notifications on demand",
                    granted = permissions.notificationListenerEnabled,
                    onGrant = { onFixPermission(PermissionRequirement.NOTIFICATION_LISTENER) }
                )
                WizardPermissionRow(
                    title = "Contacts & Direct Calling",
                    subtitle = "Look up contacts and dial on voice command",
                    granted = permissions.contactsGranted && permissions.callPhoneGranted,
                    onGrant = { onFixPermission(PermissionRequirement.CALL_PHONE) }
                )
                WizardPermissionRow(
                    title = "Battery Optimization Bypass",
                    subtitle = "Prevents Android from killing background voice listening",
                    granted = permissions.batteryOptimizationIgnored,
                    onGrant = onOpenBatterySettings
                )
                WizardPermissionRow(
                    title = "Device Administrator (Optional Lock)",
                    subtitle = "Allows instant voice screen locking",
                    granted = permissions.deviceAdminActive,
                    onGrant = { onFixPermission(PermissionRequirement.DEVICE_ADMIN) }
                )
                Spacer(Modifier.height(18.dp))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 720.dp)
            .imePadding()
    ) {
        // Jarvis + ChatGPT Hybrid Header Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_ultra_avatar),
                            contentDescription = "ULTRA AI Avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), CircleShape)
                        )
                        Column {
                            Text(
                                text = "ULTRA AI",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isReconnecting -> ArcCyan
                                                effectiveOnline -> EmeraldVerified
                                                else -> AmberWarning
                                            }
                                        )
                                )
                                Text(
                                    text = when {
                                        speaking -> "Speaking • ${settings.voiceGender.label}"
                                        listening -> "Listening..."
                                        liveAgentStatus.isNotBlank() -> liveAgentStatus
                                        isBusy || isStreaming -> "Thinking..."
                                        isReconnecting -> "Reconnecting..."
                                        effectiveOnline -> "Online • ${settings.aiSpeedProfile.name.replace('_', ' ')}"
                                        else -> "Offline Local Mode"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onEnableFloatingDot,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("home_floating_bubble_button")
                        ) {
                            Icon(
                                Icons.Default.BubbleChart,
                                contentDescription = "Launch floating voice bubble",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = { showHistorySheet = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("home_history_button")
                        ) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = "Conversation history",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = onStartNewChat,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("home_new_chat_button")
                        ) {
                            Icon(
                                Icons.Default.AddComment,
                                contentDescription = "New conversation",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Jarvis Telemetry Control Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    JarvisChip(
                        label = when (settings.operatingMode) {
                            AiOperatingMode.AUTO -> when {
                                isReconnecting -> "Mode: Reconnecting..."
                                isOnline -> "Mode: Auto (Online)"
                                else -> "Mode: Auto (Offline)"
                            }
                            AiOperatingMode.ONLINE -> if (isOnline) "Mode: Online Force" else "Mode: Online (No Net)"
                            AiOperatingMode.OFFLINE -> "Mode: Offline Local"
                        },
                        accent = when {
                            isReconnecting -> ArcCyan
                            effectiveOnline -> EmeraldVerified
                            else -> AmberWarning
                        },
                        onClick = onCycleOperatingMode,
                        testTag = "home_mode_chip"
                    )
                    JarvisChip(
                        label = when (settings.aiSpeedProfile) {
                            AiSpeedProfile.TURBO -> "⚡ Turbo"
                            AiSpeedProfile.BALANCED -> "✦ Balanced"
                            AiSpeedProfile.DEEP_THINK -> "◈ Deep Think"
                        },
                        accent = ArcCyan,
                        onClick = onCycleSpeedProfile,
                        testTag = "home_speed_chip"
                    )
                    JarvisChip(
                        label = "Access: $grantedCount/7",
                        accent = if (grantedCount >= 5) EmeraldVerified else AmberWarning,
                        icon = Icons.Default.Security,
                        onClick = { showPermissionsSheet = true },
                        testTag = "home_permissions_chip"
                    )
                    if (onlineDiagnostic.isNotBlank() &&
                        !onlineDiagnostic.startsWith("Connected") &&
                        !onlineDiagnostic.startsWith("Health OK") &&
                        isOnline &&
                        settings.operatingMode != AiOperatingMode.OFFLINE
                    ) {
                        JarvisChip(
                            label = onlineDiagnostic.take(28),
                            accent = AmberWarning,
                            onClick = onCycleOperatingMode,
                            testTag = "home_diagnostic_chip"
                        )
                    }
                }
            }
        }

        // Main Chat Message Stream
        if (conversations.isEmpty() && !isBusy && !isStreaming && streamingReply.isBlank()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_ultra_avatar),
                        contentDescription = "ULTRA AI Core",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), CircleShape)
                    )
                    Text(
                        text = if (settings.userDisplayName.isNotBlank()) {
                            "How can I help you today, ${settings.userDisplayName}?"
                        } else {
                            "How can I help you today?"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (effectiveOnline) {
                            "ChatGPT-level thinking + Jarvis autonomous in-app phone control in English, বাংলা, or हिन्दी."
                        } else {
                            "Offline Local Mode active. I can launch apps, navigate inside screens, convert units, calculate, and check battery/storage."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val starters = listOf(
                        "Open YouTube and search relaxing piano music",
                        "Open Settings and scroll down",
                        "Wi-Fi সেটিংস খোলো",
                        "Think step by step: how can I manage my daily focus?"
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        starters.forEach { prompt ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSubmitCommand(prompt) },
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                            ) {
                                Text(
                                    text = prompt,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(conversations, key = { it.id }) { msg ->
                    ChatBubbleItem(
                        message = msg,
                        onSpeakClick = { onSpeakText(msg.content) }
                    )
                }

                if (isBusy || isStreaming || streamingReply.isNotBlank()) {
                    item(key = "live_thinking_bubble") {
                        ThinkingOrStreamingBubble(
                            streamingReply = streamingReply,
                            liveStatus = liveAgentStatus,
                            isBusy = isBusy,
                            onStopAgent = onStopAgentExecution
                        )
                    }
                }
            }
        }

        // Missing permission quick fix notice if relevant report flagged it
        latestReport?.missingPermission?.let { req ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.7f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Permission needed: ${req.name.replace('_', ' ')}",
                        style = MaterialTheme.typography.labelMedium,
                        color = AmberWarning,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onFixPermission(req) }) {
                        Text("Grant")
                    }
                }
            }
        }

        // Active Voice Activity Bar
        AnimatedVisibility(visible = isServiceRunning || speaking || partialTranscript.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = "Voice activity",
                            tint = if (speaking) EmeraldVerified else ArcCyan
                        )
                        Text(
                            text = when {
                                partialTranscript.isNotBlank() -> "\"$partialTranscript\""
                                speaking -> "Speaking aloud — tap Interrupt to talk"
                                listening -> "Listening naturally..."
                                else -> "Voice session: $voiceState"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (speaking) {
                        IconButton(onClick = onInterruptVoice) {
                            Icon(Icons.Default.PanTool, contentDescription = "Interrupt AI", tint = ArcCyan)
                        }
                        IconButton(onClick = onStopSpeaking) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop speaking", tint = AmberWarning)
                        }
                    } else if (isServiceRunning) {
                        IconButton(onClick = onStopAssistant) {
                            Icon(Icons.Default.MicOff, contentDescription = "Stop voice service", tint = AmberWarning)
                        }
                    }
                }
            }
        }

        // Bottom ChatGPT-style Composer
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = if (isServiceRunning) onStopAssistant else onStartAssistant,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("chat_mic_button")
                ) {
                    Icon(
                        imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isServiceRunning) "Stop voice conversation" else "Start voice conversation",
                        tint = if (isServiceRunning) EmeraldVerified else MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = commandText,
                    onValueChange = { commandText = it },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_field"),
                    maxLines = 4,
                    placeholder = {
                        Text(
                            "Message or command ULTRA AI...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val clean = commandText.trim()
                            if (clean.isNotEmpty() && !isBusy) {
                                commandText = ""
                                onSubmitCommand(clean)
                            }
                        }
                    )
                )

                IconButton(
                    onClick = onOpenVoiceScreen,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("chat_voice_call_button")
                ) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = "Open full voice call screen",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = {
                        val clean = commandText.trim()
                        if (clean.isNotEmpty() && !isBusy) {
                            commandText = ""
                            onSubmitCommand(clean)
                        }
                    },
                    enabled = commandText.isNotBlank() && !isBusy,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("chat_send_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (commandText.isNotBlank() && !isBusy) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message",
                            tint = if (commandText.isNotBlank() && !isBusy) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JarvisChip(
    label: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WizardPermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, (if (granted) EmeraldVerified else AmberWarning).copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(
                onClick = onGrant,
                modifier = Modifier.height(44.dp)
            ) {
                Text(if (granted) "Active" else "Enable")
            }
        }
    }
}

@Composable
private fun ChatBubbleItem(
    message: ConversationMessageEntity,
    onSpeakClick: () -> Unit
) {
    val isUser = message.role.equals("user", ignoreCase = true)
    var thoughtExpanded by remember(message.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            Image(
                painter = painterResource(id = R.drawable.img_ultra_avatar),
                contentDescription = "ULTRA AI",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(top = 4.dp, end = 8.dp)
                    .size(28.dp)
                    .clip(CircleShape)
            )
        }

        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
            },
            border = if (!isUser) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            } else null
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!isUser && message.thoughtSummary.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { thoughtExpanded = !thoughtExpanded },
                        shape = RoundedCornerShape(10.dp),
                        color = NeuralViolet.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, NeuralViolet.copy(alpha = 0.35f))
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        contentDescription = "Thought process",
                                        tint = NeuralViolet,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "Thought Process & Agent Steps",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = NeuralViolet
                                    )
                                }
                                Icon(
                                    imageVector = if (thoughtExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (thoughtExpanded) "Collapse reasoning" else "Expand reasoning",
                                    tint = NeuralViolet,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            AnimatedVisibility(visible = thoughtExpanded) {
                                Text(
                                    text = message.thoughtSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }
                }

                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!isUser) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = message.providerLabel.ifBlank { "ULTRA AI" },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = onSpeakClick,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Speak reply aloud",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingOrStreamingBubble(
    streamingReply: String,
    liveStatus: String,
    isBusy: Boolean,
    onStopAgent: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "thinkingDots")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_ultra_avatar),
            contentDescription = "ULTRA AI Thinking",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(top = 4.dp, end = 8.dp)
                .size(28.dp)
                .clip(CircleShape)
                .alpha(pulse)
        )
        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 4.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, NeuralViolet.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (liveStatus.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(14.dp))
                        Text(
                            text = liveStatus,
                            style = MaterialTheme.typography.labelMedium,
                            color = ArcCyan
                        )
                    }
                }
                if (streamingReply.isNotBlank()) {
                    Text(
                        text = "$streamingReply ▍",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Text(
                        text = "ULTRA is thinking...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.alpha(pulse)
                    )
                }
                if (isBusy) {
                    TextButton(
                        onClick = onStopAgent,
                        modifier = Modifier.testTag("stop_agent_button")
                    ) {
                        Text("Stop Agent", color = AmberWarning)
                    }
                }
            }
        }
    }
}
