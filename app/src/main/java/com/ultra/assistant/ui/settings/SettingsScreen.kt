package com.ultra.assistant.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
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
import androidx.compose.ui.unit.dp
import com.ultra.assistant.R
import com.ultra.assistant.control.DeviceInfoReport
import com.ultra.assistant.data.AiOperatingMode
import com.ultra.assistant.data.AiProviderMode
import com.ultra.assistant.data.AiSpeedProfile
import com.ultra.assistant.data.ChatBackgroundStyle
import com.ultra.assistant.data.UltraSettings
import com.ultra.assistant.data.UltraThemeStyle
import com.ultra.assistant.data.VoiceGender
import com.ultra.assistant.memory.ConversationSessionEntity
import com.ultra.assistant.memory.MemoryRecordEntity
import com.ultra.assistant.permissions.PermissionAuditSnapshot
import com.ultra.assistant.ui.components.HudCard
import com.ultra.assistant.ui.components.StatusPill
import com.ultra.assistant.ui.theme.AmberWarning
import com.ultra.assistant.ui.theme.ArcCyan
import com.ultra.assistant.ui.theme.CrimsonAlert
import com.ultra.assistant.ui.theme.EmeraldVerified
import com.ultra.assistant.ui.theme.ObsidianBg

@Composable
fun SettingsScreen(
    userEmail: String?,
    isOnline: Boolean,
    settings: UltraSettings,
    permissions: PermissionAuditSnapshot,
    deviceInfo: DeviceInfoReport,
    deviceAdminExplanation: String,
    memories: List<MemoryRecordEntity>,
    sessions: List<ConversationSessionEntity>,
    onlineDiagnostic: String,
    workerHealthStatus: String?,
    onUpdateOperatingMode: (AiOperatingMode) -> Unit,
    onUpdateSpeedProfile: (AiSpeedProfile) -> Unit,
    onUpdateAiConfig: (AiProviderMode, String, String, String) -> Unit,
    onTestWorkerConnection: (String, String) -> Unit,
    onUpdateVoiceGender: (VoiceGender) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onUpdateLanguageAutoDetect: (Boolean) -> Unit,
    onUpdateAutoSpeakChatReplies: (Boolean) -> Unit,
    onUpdateWakePhrase: (String) -> Unit,
    onUpdateVoiceTuning: (Float, Float) -> Unit,
    onUpdateVoiceVolume: (Float) -> Unit,
    onTestVoice: () -> Unit,
    onUpdateAppearance: (ChatBackgroundStyle, UltraThemeStyle, Boolean) -> Unit,
    onUpdateBehavior: (Boolean, Boolean, Boolean) -> Unit,
    onLaunchFloatingDot: () -> Unit,
    onUpdatePrivacyConfig: (Boolean, Boolean, Int, Boolean, Boolean, Boolean) -> Unit,
    onSaveMemory: (String, String) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onClearAllMemories: () -> Unit,
    onStartNewChat: () -> Unit,
    onClearConversationHistory: () -> Unit,
    onRequestAllRuntimePermissions: () -> Unit,
    onRequestContactsAndPhone: () -> Unit,
    onOpenMicAppSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onActivateDeviceAdmin: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onSignOut: () -> Unit
) {
    var workerUrl by remember(settings.workerEndpoint) { mutableStateOf(settings.workerEndpoint) }
    var workerToken by remember(settings.workerAuthToken) { mutableStateOf(settings.workerAuthToken) }
    var wakePhrase by remember(settings.wakePhrase) { mutableStateOf(settings.wakePhrase) }
    var speed by remember(settings.voiceSpeed) { mutableFloatStateOf(settings.voiceSpeed) }
    var pitch by remember(settings.voicePitch) { mutableFloatStateOf(settings.voicePitch) }
    var volume by remember(settings.voiceVolume) { mutableFloatStateOf(settings.voiceVolume) }
    var memTitle by remember { mutableStateOf("") }
    var memContent by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 680.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1 & 2. Online / Offline Mode + AI Brain Settings
        item {
            HudCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AI Brain & Online/Offline Mode", style = MaterialTheme.typography.titleMedium)
                    StatusPill(
                        label = if (isOnline) "NETWORK ONLINE" else "NO INTERNET",
                        color = if (isOnline) EmeraldVerified else AmberWarning
                    )
                }

                Text("Operating Mode", style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AiOperatingMode.entries.forEach { opMode ->
                        FilterChip(
                            selected = settings.operatingMode == opMode,
                            onClick = { onUpdateOperatingMode(opMode) },
                            label = {
                                Text(
                                    when (opMode) {
                                        AiOperatingMode.AUTO -> "Auto"
                                        AiOperatingMode.ONLINE -> "Online Only"
                                        AiOperatingMode.OFFLINE -> "Offline Local"
                                    }
                                )
                            }
                        )
                    }
                }

                Text("AI Speed & Thinking Profile", style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AiSpeedProfile.entries.forEach { profile ->
                        FilterChip(
                            selected = settings.aiSpeedProfile == profile,
                            onClick = { onUpdateSpeedProfile(profile) },
                            label = {
                                Text(
                                    when (profile) {
                                        AiSpeedProfile.TURBO -> "Turbo (Instant)"
                                        AiSpeedProfile.BALANCED -> "Balanced"
                                        AiSpeedProfile.DEEP_THINK -> "Deep Reasoning"
                                    }
                                )
                            }
                        )
                    }
                }

                Text("AI Gateway Routing", style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AiProviderMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.aiProviderMode == mode,
                            onClick = { onUpdateAiConfig(mode, settings.geminiModel, workerUrl, workerToken) },
                            label = {
                                Text(
                                    when (mode) {
                                        AiProviderMode.AUTO -> "Auto"
                                        AiProviderMode.FIREBASE_AI -> "Gemini"
                                        AiProviderMode.CLOUDFLARE_WORKER -> "Cloudflare"
                                    }
                                )
                            }
                        )
                    }
                }

                Text("Gemini Model", style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-4-argon").forEach { modelId ->
                        FilterChip(
                            selected = settings.geminiModel == modelId,
                            onClick = { onUpdateAiConfig(settings.aiProviderMode, modelId, workerUrl, workerToken) },
                            label = { Text(modelId.removePrefix("gemini-")) }
                        )
                    }
                }

                OutlinedTextField(
                    value = workerUrl,
                    onValueChange = { workerUrl = it },
                    label = { Text("Cloudflare Worker URL (Free Plan Ready)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = workerToken,
                    onValueChange = { workerToken = it },
                    label = { Text("Worker Bearer Token (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!workerHealthStatus.isNullOrBlank() || onlineDiagnostic.isNotBlank()) {
                    Text(
                        text = workerHealthStatus ?: "Online Status: $onlineDiagnostic",
                        style = MaterialTheme.typography.bodySmall,
                        color = if ((workerHealthStatus ?: onlineDiagnostic).contains("OK", true) ||
                            (workerHealthStatus ?: onlineDiagnostic).contains("Connected", true)
                        ) EmeraldVerified else AmberWarning
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onUpdateAiConfig(settings.aiProviderMode, settings.geminiModel, workerUrl, workerToken) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Save AI Config")
                    }
                    OutlinedButton(
                        onClick = { onTestWorkerConnection(workerUrl, workerToken) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("settings_test_worker_button")
                    ) {
                        Text("Test Cloudflare")
                    }
                }
            }
        }

        // 3, 4, 5, 8, 9. Voice Selection, Speed, Volume, Language, Floating & Continuous Listening, Edge Glow
        item {
            HudCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Voice & Conversation Controls", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = onTestVoice,
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("settings_test_voice_button")
                    ) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = "Test voice")
                        Text(" Test Voice")
                    }
                }

                Text("Voice Personality", style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceGender.entries.forEach { gender ->
                        FilterChip(
                            selected = settings.voiceGender == gender,
                            onClick = { onUpdateVoiceGender(gender) },
                            label = { Text(gender.label) }
                        )
                    }
                }

                Text("Assistant Language", style = MaterialTheme.typography.labelMedium, color = ArcCyan)
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = wakePhrase,
                        onValueChange = { wakePhrase = it },
                        label = { Text("Wake Phrase") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onUpdateWakePhrase(wakePhrase) },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Save")
                    }
                }

                Text("Voice Speed: ${"%.2f".format(speed)}x", style = MaterialTheme.typography.bodyMedium)
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

                Text("Voice Volume: ${(volume * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    valueRange = 0.1f..1.0f,
                    onValueChangeFinished = { onUpdateVoiceVolume(volume) }
                )

                SettingsSwitchRow(
                    label = "Auto-Detect Multilingual Speech (EN/BN/HI)",
                    checked = settings.languageAutoDetect,
                    onCheckedChange = onUpdateLanguageAutoDetect
                )
                SettingsSwitchRow(
                    label = "Auto-Speak Typed Chat Replies",
                    checked = settings.autoSpeakChatReplies,
                    onCheckedChange = onUpdateAutoSpeakChatReplies
                )
                SettingsSwitchRow(
                    label = "Continuous Voice Listening",
                    checked = settings.continuousListening,
                    onCheckedChange = {
                        onUpdateBehavior(settings.floatingAssistantEnabled, it, settings.requireCallConfirmation)
                    }
                )
                SettingsSwitchRow(
                    label = "Floating Assistant Bubble",
                    checked = settings.floatingAssistantEnabled,
                    onCheckedChange = {
                        onUpdateBehavior(it, settings.continuousListening, settings.requireCallConfirmation)
                    }
                )
                OutlinedButton(
                    onClick = onLaunchFloatingDot,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("settings_launch_floating_button")
                ) {
                    Text("Open Floating Assistant Now")
                }
            }
        }

        // 6 & 7. Background Images & UI Themes + Ambient Edge Glow
        item {
            HudCard {
                Text("Background Images & UI Themes", style = MaterialTheme.typography.titleMedium)
                Text("UI Theme", style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UltraThemeStyle.entries.forEach { theme ->
                        FilterChip(
                            selected = settings.uiThemeStyle == theme,
                            onClick = { onUpdateAppearance(settings.backgroundStyle, theme, settings.ambientEdgeGlowEnabled) },
                            label = { Text(theme.label) }
                        )
                    }
                }

                Text("Chat Background Wallpaper", style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ChatBackgroundStyle.entries.forEach { bg ->
                        val selected = settings.backgroundStyle == bg
                        val previewRes = when (bg) {
                            ChatBackgroundStyle.NEURAL_COSMOS -> R.drawable.img_bg_neural_cosmos
                            ChatBackgroundStyle.QUANTUM_GRID -> R.drawable.img_bg_quantum_grid
                            ChatBackgroundStyle.AURORA_SILK -> R.drawable.img_bg_aurora_silk
                            ChatBackgroundStyle.PURE_OBSIDIAN -> null
                        }
                        Surface(
                            modifier = Modifier
                                .width(104.dp)
                                .clickable {
                                    onUpdateAppearance(bg, settings.uiThemeStyle, settings.ambientEdgeGlowEnabled)
                                },
                            shape = MaterialTheme.shapes.medium,
                            color = ObsidianBg,
                            border = BorderStroke(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (previewRes != null) {
                                    Image(
                                        painter = painterResource(id = previewRes),
                                        contentDescription = bg.label,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(64.dp)
                                            .clip(MaterialTheme.shapes.small)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(64.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("Dark", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                Text(bg.label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                SettingsSwitchRow(
                    label = "Ambient Edge Lighting (Active Glow)",
                    checked = settings.ambientEdgeGlowEnabled,
                    onCheckedChange = {
                        onUpdateAppearance(settings.backgroundStyle, settings.uiThemeStyle, it)
                    }
                )
            }
        }

        // 10-14. Permissions, Accessibility, Notification Listener, Floating Overlay & Battery
        item {
            HudCard {
                Text("Permissions & System Access", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = onRequestAllRuntimePermissions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("settings_grant_runtime_permissions")
                ) {
                    Text("Grant All Runtime Permissions")
                }
                PermissionRow(
                    title = "Microphone & Notifications",
                    granted = permissions.microphoneGranted && permissions.notificationsGranted,
                    onManage = onOpenMicAppSettings
                )
                PermissionRow(
                    title = "Contacts & Phone Calls",
                    granted = permissions.contactsGranted && permissions.callPhoneGranted,
                    onManage = onRequestContactsAndPhone
                )
                PermissionRow(
                    title = "Accessibility Service",
                    granted = permissions.accessibilityEnabled,
                    onManage = onOpenAccessibilitySettings
                )
                PermissionRow(
                    title = "Notification Listener",
                    granted = permissions.notificationListenerEnabled,
                    onManage = onOpenNotificationSettings
                )
                PermissionRow(
                    title = "Floating Overlay Permission",
                    granted = permissions.overlayGranted,
                    onManage = onOpenOverlaySettings
                )
                PermissionRow(
                    title = "Battery Optimization Bypass",
                    granted = permissions.batteryOptimizationIgnored,
                    onManage = onOpenBatterySettings
                )
            }
        }

        // 15. Device Admin
        item {
            HudCard(borderColor = if (deviceInfo.isDeviceAdminActive) EmeraldVerified else AmberWarning) {
                Text("Device Management (DevicePolicyManager)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Device Admin: ${if (deviceInfo.isDeviceAdminActive) "ACTIVE" else "NOT ACTIVATED"} | Device Owner: ${deviceInfo.isDeviceOwner}",
                    style = MaterialTheme.typography.labelMedium,
                    color = ArcCyan
                )
                Text(
                    deviceAdminExplanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!deviceInfo.isDeviceAdminActive) {
                        Button(
                            onClick = onActivateDeviceAdmin,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("activate_device_admin_button")
                        ) {
                            Text("Enable Admin")
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenSecuritySettings,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Security Settings")
                    }
                }
            }
        }

        // 16 & 17. Privacy Vault, Conversation History & Memory Controls
        item {
            HudCard {
                Text("Privacy, Security & Conversation History", style = MaterialTheme.typography.titleMedium)
                SettingsSwitchRow(
                    label = "Cloud Sync (Firestore)",
                    checked = settings.cloudSyncEnabled,
                    onCheckedChange = {
                        onUpdatePrivacyConfig(it, settings.encryptCloudMemory, settings.memoryTtlDays, settings.allowNotificationContext, settings.requireSmsConfirmation, settings.requireCallConfirmation)
                    }
                )
                SettingsSwitchRow(
                    label = "AES-256 Encrypt Synced Memory",
                    checked = settings.encryptCloudMemory,
                    onCheckedChange = {
                        onUpdatePrivacyConfig(settings.cloudSyncEnabled, it, settings.memoryTtlDays, settings.allowNotificationContext, settings.requireSmsConfirmation, settings.requireCallConfirmation)
                    }
                )
                SettingsSwitchRow(
                    label = "Confirm Sensitive SMS Actions",
                    checked = settings.requireSmsConfirmation,
                    onCheckedChange = {
                        onUpdatePrivacyConfig(settings.cloudSyncEnabled, settings.encryptCloudMemory, settings.memoryTtlDays, settings.allowNotificationContext, it, settings.requireCallConfirmation)
                    }
                )
                SettingsSwitchRow(
                    label = "Confirm Direct Phone Calls",
                    checked = settings.requireCallConfirmation,
                    onCheckedChange = {
                        onUpdatePrivacyConfig(settings.cloudSyncEnabled, settings.encryptCloudMemory, settings.memoryTtlDays, settings.allowNotificationContext, settings.requireSmsConfirmation, it)
                    }
                )
                Text(
                    "Saved chat sessions: ${sessions.size}. Privacy Guard blocks OTPs and passwords from AI memory.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EmeraldVerified
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onStartNewChat,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("New Chat")
                    }
                    OutlinedButton(
                        onClick = onClearConversationHistory,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberWarning),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("clear_chat_history_button")
                    ) {
                        Text("Clear Chats")
                    }
                }

                OutlinedTextField(
                    value = memTitle,
                    onValueChange = { memTitle = it },
                    label = { Text("Add Memory Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = memContent,
                    onValueChange = { memContent = it },
                    label = { Text("Memory Content") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (memTitle.isNotBlank() && memContent.isNotBlank()) {
                                onSaveMemory(memTitle, memContent)
                                memTitle = ""
                                memContent = ""
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("save_memory_button")
                    ) {
                        Text("Save Memory")
                    }
                    OutlinedButton(
                        onClick = onClearAllMemories,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonAlert),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("clear_all_memories_button")
                    ) {
                        Text("Wipe Vault")
                    }
                }
            }
        }

        items(memories.take(15), key = { it.id }) { mem ->
            HudCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(mem.title, style = MaterialTheme.typography.titleMedium)
                    StatusPill(label = mem.category, color = ArcCyan)
                }
                Text(mem.content, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { onDeleteMemory(mem.id) }, modifier = Modifier.height(48.dp)) {
                    Text("Delete Memory")
                }
            }
        }

        // 18. Account & Sign Out
        item {
            HudCard {
                Text("Account", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Signed in as: ${userEmail ?: "Google Account"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(
                    onClick = onSignOut,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonAlert),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("sign_out_button")
                ) {
                    Text("Sign Out")
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    onManage: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        StatusPill(
            label = if (granted) "GRANTED" else "OFF",
            color = if (granted) EmeraldVerified else AmberWarning
        )
        OutlinedButton(
            onClick = onManage,
            modifier = Modifier
                .padding(start = 6.dp)
                .height(48.dp)
        ) {
            Text(if (granted) "Manage" else "Grant")
        }
    }
}
