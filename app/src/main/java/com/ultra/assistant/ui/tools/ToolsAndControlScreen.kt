package com.ultra.assistant.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ultra.assistant.control.AccessibleScreenSnapshot
import com.ultra.assistant.control.CapturedNotification
import com.ultra.assistant.control.SmsCapabilityReport
import com.ultra.assistant.permissions.PermissionAuditSnapshot
import com.ultra.assistant.tools.UltraTool
import com.ultra.assistant.ui.components.HudCard
import com.ultra.assistant.ui.components.StatusPill
import com.ultra.assistant.ui.components.riskColor
import com.ultra.assistant.ui.theme.AmberWarning
import com.ultra.assistant.ui.theme.ArcCyan
import com.ultra.assistant.ui.theme.EmeraldVerified

@Composable
fun ToolsAndControlScreen(
    tools: List<UltraTool>,
    permissions: PermissionAuditSnapshot,
    screenSnapshot: AccessibleScreenSnapshot?,
    notifications: List<CapturedNotification>,
    smsCaps: SmsCapabilityReport,
    onRunToolTest: (String, Map<String, String>) -> Unit,
    onInspectScreen: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onClearNotifications: () -> Unit,
    onPrepareSmsDraft: (String, String) -> Unit
) {
    var selectedSubTab by rememberSaveable { mutableIntStateOf(0) }
    var smsRecipient by remember { mutableStateOf("") }
    var smsBody by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 640.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("Tools (${tools.size})", "Screen", "Notify", "SMS").forEachIndexed { idx, label ->
                FilterChip(
                    selected = selectedSubTab == idx,
                    onClick = { selectedSubTab = idx },
                    label = { Text(label) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (selectedSubTab) {
                0 -> {
                    items(tools, key = { it.id }) { tool ->
                        HudCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tool.id, style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                                StatusPill(label = tool.riskLevel.name, color = riskColor(tool.riskLevel))
                            }
                            Text(tool.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                tool.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (tool.parameters.isNotEmpty()) {
                                Text(
                                    "Schema: " + tool.parameters.joinToString { "${it.name}:${it.type}" },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ArcCyan
                                )
                            }
                            if (tool.id in setOf("get_device_information", "get_accessible_screen", "get_notifications")) {
                                OutlinedButton(
                                    onClick = { onRunToolTest(tool.id, emptyMap()) },
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Text("Run Tool Check")
                                }
                            }
                        }
                    }
                }
                1 -> {
                    item {
                        HudCard(borderColor = if (permissions.accessibilityEnabled) EmeraldVerified else AmberWarning) {
                            Text("Android Accessibility Control", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (permissions.accessibilityEnabled) {
                                    "Accessibility Service is active. ULTRA AI can inspect non-password UI elements and execute user-authorized clicks, scrolling, and typing."
                                } else {
                                    "Accessibility Service is disabled. Enable ULTRA AI in Android Accessibility Settings to inspect screen content."
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onInspectScreen,
                                    enabled = permissions.accessibilityEnabled,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("inspect_screen_button")
                                ) {
                                    Text("Inspect Screen")
                                }
                                OutlinedButton(
                                    onClick = onOpenAccessibilitySettings,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("open_accessibility_settings_button")
                                ) {
                                    Text("Settings")
                                }
                            }
                        }
                    }
                    if (screenSnapshot != null) {
                        item {
                            HudCard {
                                Text("Window: ${screenSnapshot.windowTitle}", style = MaterialTheme.typography.titleMedium)
                                Text("Package: ${screenSnapshot.packageName}", style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                            }
                        }
                        items(screenSnapshot.elements.take(30)) { elem ->
                            HudCard {
                                Text(
                                    elem.text.ifBlank { elem.contentDescription }.ifBlank { elem.className },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    "id=${elem.viewId.ifBlank { "none" }} | clickable=${elem.clickable} editable=${elem.editable}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                2 -> {
                    item {
                        HudCard(borderColor = if (permissions.notificationListenerEnabled) EmeraldVerified else AmberWarning) {
                            Text("Notification Understanding", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Notifications are classified locally and filtered by Privacy Guard. Sensitive OTPs/tokens are masked and never auto-uploaded.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onOpenNotificationSettings,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("open_notification_listener_button")
                                ) {
                                    Text(if (permissions.notificationListenerEnabled) "Manage Access" else "Enable Access")
                                }
                                OutlinedButton(
                                    onClick = onClearNotifications,
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Text("Clear Feed")
                                }
                            }
                        }
                    }
                    if (notifications.isEmpty()) {
                        item {
                            HudCard {
                                Text("No notifications captured in this session yet.", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } else {
                        items(notifications, key = { it.key }) { item ->
                            HudCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    StatusPill(label = item.category.name, color = ArcCyan)
                                    if (item.sensitiveRedacted) {
                                        StatusPill(label = "PRIVACY REDACTED", color = AmberWarning)
                                    }
                                }
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                Text(item.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                3 -> {
                    item {
                        HudCard {
                            Text("SMS Assistance & Policy Status", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Telephony: ${smsCaps.hasTelephony} | Draft App Ready: ${smsCaps.canOpenDraftApp} | Default SMS Handler: ${smsCaps.isDefaultSmsHandler}",
                                style = MaterialTheme.typography.labelMedium,
                                color = ArcCyan
                            )
                            Text(
                                "By default, ULTRA AI opens your messaging app with a prefilled draft (zero SMS permissions required). Direct sending only activates if ULTRA AI is set as the device's default SMS handler.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = smsRecipient,
                                onValueChange = { smsRecipient = it },
                                label = { Text("Recipient Phone Number (optional)") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("sms_recipient_input")
                            )
                            OutlinedTextField(
                                value = smsBody,
                                onValueChange = { smsBody = it },
                                label = { Text("Message Draft") },
                                minLines = 3,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("sms_body_input")
                            )
                            Button(
                                onClick = { onPrepareSmsDraft(smsRecipient, smsBody) },
                                enabled = smsBody.isNotBlank(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("prepare_sms_draft_button")
                            ) {
                                Text("Open Prefilled SMS Draft")
                            }
                        }
                    }
                }
            }
        }
    }
}
