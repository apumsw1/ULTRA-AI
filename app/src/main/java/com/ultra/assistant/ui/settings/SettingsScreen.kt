package com.ultra.assistant.ui.settings

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.ultra.assistant.control.DeviceInfoReport
import com.ultra.assistant.data.AiProviderMode
import com.ultra.assistant.data.UltraSettings
import com.ultra.assistant.memory.MemoryRecordEntity
import com.ultra.assistant.permissions.PermissionAuditSnapshot
import com.ultra.assistant.ui.components.HudCard
import com.ultra.assistant.ui.components.StatusPill
import com.ultra.assistant.ui.theme.AmberWarning
import com.ultra.assistant.ui.theme.ArcCyan
import com.ultra.assistant.ui.theme.CrimsonAlert
import com.ultra.assistant.ui.theme.EmeraldVerified

@Composable
fun SettingsScreen(
    userEmail: String?,
    settings: UltraSettings,
    permissions: PermissionAuditSnapshot,
    deviceInfo: DeviceInfoReport,
    deviceAdminExplanation: String,
    memories: List<MemoryRecordEntity>,
    onUpdateAiConfig: (AiProviderMode, String, String) -> Unit,
    onUpdatePrivacyConfig: (Boolean, Boolean, Int, Boolean, Boolean) -> Unit,
    onSaveMemory: (String, String) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onClearAllMemories: () -> Unit,
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
    var memTitle by remember { mutableStateOf("") }
    var memContent by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 640.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            HudCard {
                Text("AI Brain & Model Router", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Route inference through Firebase AI Logic (Gemini) or your Cloudflare Worker Gateway.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AiProviderMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.aiProviderMode == mode,
                            onClick = { onUpdateAiConfig(mode, settings.geminiModel, workerUrl) },
                            label = {
                                Text(
                                    when (mode) {
                                        AiProviderMode.AUTO -> "Auto"
                                        AiProviderMode.FIREBASE_AI -> "Gemini"
                                        AiProviderMode.CLOUDFLARE_WORKER -> "Worker"
                                    }
                                )
                            }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-4-argon").forEach { modelId ->
                        FilterChip(
                            selected = settings.geminiModel == modelId,
                            onClick = { onUpdateAiConfig(settings.aiProviderMode, modelId, workerUrl) },
                            label = { Text(modelId.removePrefix("gemini-")) }
                        )
                    }
                }
                OutlinedTextField(
                    value = workerUrl,
                    onValueChange = { workerUrl = it },
                    label = { Text("Cloudflare Worker URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onUpdateAiConfig(settings.aiProviderMode, settings.geminiModel, workerUrl) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Save Model Router Config")
                }
            }
        }

        item {
            HudCard {
                Text("Memory & Privacy Vault", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cloud Sync (Firestore)", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = settings.cloudSyncEnabled,
                        onCheckedChange = {
                            onUpdatePrivacyConfig(it, settings.encryptCloudMemory, settings.memoryTtlDays, settings.allowNotificationContext, settings.requireSmsConfirmation)
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AES-256 Encrypt Synced Memory", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = settings.encryptCloudMemory,
                        onCheckedChange = {
                            onUpdatePrivacyConfig(settings.cloudSyncEnabled, it, settings.memoryTtlDays, settings.allowNotificationContext, settings.requireSmsConfirmation)
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Confirm Sensitive SMS Actions", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = settings.requireSmsConfirmation,
                        onCheckedChange = {
                            onUpdatePrivacyConfig(settings.cloudSyncEnabled, settings.encryptCloudMemory, settings.memoryTtlDays, settings.allowNotificationContext, it)
                        }
                    )
                }
                Text(
                    "Privacy Guard automatically blocks OTPs, passwords, and raw notification/SMS secrets from AI memory.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EmeraldVerified
                )

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
                        Text("Wipe All")
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

        item {
            HudCard {
                Text("Permissions & Revocation Paths", style = MaterialTheme.typography.titleMedium)
                PermissionRow("Microphone & Notifications", permissions.microphoneGranted && permissions.notificationsGranted, onOpenMicAppSettings)
                PermissionRow("Accessibility Service", permissions.accessibilityEnabled, onOpenAccessibilitySettings)
                PermissionRow("Notification Listener", permissions.notificationListenerEnabled, onOpenNotificationSettings)
                PermissionRow("Floating Overlay (Dot)", permissions.overlayGranted, onOpenOverlaySettings)
                PermissionRow("Battery Optimization Bypass", permissions.batteryOptimizationIgnored, onOpenBatterySettings)
            }
        }

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
                            Text("Enable Device Admin")
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenSecuritySettings,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Revoke / Security Settings")
                    }
                }
            }
        }

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
            modifier = Modifier.height(48.dp)
        ) {
            Text(if (granted) "Revoke" else "Grant")
        }
    }
}
