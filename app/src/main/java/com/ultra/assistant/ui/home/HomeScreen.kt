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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
    settings: UltraSettings,
    permissions: PermissionAuditSnapshot,
    isBusy: Boolean,
    isStreaming: Boolean,
    streamingReply: String,
    latestReport: AgentExecutionReport?,
    conversations: List<ConversationMessageEntity>,
    sessions: List<ConversationSessionEntity>,
    activeSessionId: String,
    onStartAssistant: () -> Unit,
    onStopAssistant: () -> Unit,
    onStopSpeaking: () -> Unit,
    onInterruptVoice: () -> Unit,
    onEnableFloatingDot: () -> Unit,
    onOpenVoiceScreen: () -> Unit,
    onSubmitCommand: (String) -> Unit,
    onSpeakText: (String) -> Unit,
    onStartNewChat: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onFixPermission: (PermissionRequirement) -> Unit
) {
    var commandText by rememberSaveable { mutableStateOf("") }
    var showHistorySheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val effectiveOnline = settings.operatingMode != AiOperatingMode.OFFLINE && isOnline
    val speaking = voiceState.equals("SPEAKING", true)
    val listening = isServiceRunning && voiceState.equals("LISTENING", true)

    LaunchedEffect(conversations.size, streamingReply, isBusy) {
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
                    Text("Conversation History", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        onStartNewChat()
                        showHistorySheet = false
                    }) {
                        Text("+ New Chat")
                    }
                }
                if (sessions.isEmpty()) {
                    Text(
                        "No saved chats yet. Start typing or speaking to begin.",
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 720.dp)
            .imePadding()
    ) {
        // ChatGPT-style Top Header Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            tonalElevation = 2.dp
        ) {
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
                                    .background(if (effectiveOnline) EmeraldVerified else AmberWarning)
                            )
                            Text(
                                text = when {
                                    speaking -> "Speaking • ${settings.voiceGender.label}"
                                    listening -> "Listening..."
                                    isBusy || isStreaming -> "Thinking..."
                                    effectiveOnline -> "Online • ${settings.voiceGender.label}"
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
                            .size(86.dp)
                            .clip(CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), CircleShape)
                    )
                    Text(
                        text = "How can I help you today?",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (effectiveOnline) {
                            "Ask anything in English, বাংলা, or हिन्दी — or ask me to control apps, YouTube, volume, calls, and settings."
                        } else {
                            "Offline Local Mode is active. I can still run device controls, calculate, check time/battery, and recall saved memories."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val starters = listOf(
                        "Search YouTube for relaxing piano music",
                        "Open Wi-Fi settings",
                        "Read my latest notifications",
                        "Turn volume to 60% and check battery"
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
                        ThinkingOrStreamingBubble(streamingReply = streamingReply)
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

        // Active Voice Activity Bar (visible when voice conversation is active)
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
                            "Message ULTRA AI...",
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
private fun ChatBubbleItem(
    message: ConversationMessageEntity,
    onSpeakClick: () -> Unit
) {
    val isUser = message.role.equals("user", ignoreCase = true)
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
private fun ThinkingOrStreamingBubble(streamingReply: String) {
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
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
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
            }
        }
    }
}
