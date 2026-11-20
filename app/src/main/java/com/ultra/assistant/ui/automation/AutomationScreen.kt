package com.ultra.assistant.ui.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ultra.assistant.memory.AuditLogEntity
import com.ultra.assistant.memory.AutomationRuleEntity
import com.ultra.assistant.ui.components.HudCard
import com.ultra.assistant.ui.components.StatusPill
import com.ultra.assistant.ui.theme.AmberWarning
import com.ultra.assistant.ui.theme.ArcCyan
import com.ultra.assistant.ui.theme.CrimsonAlert
import com.ultra.assistant.ui.theme.EmeraldVerified

@Composable
fun AutomationScreen(
    rules: List<AutomationRuleEntity>,
    pendingReminders: List<Pair<String, String>>,
    auditLogs: List<AuditLogEntity>,
    onScheduleReminder: (String, Long) -> Unit,
    onCancelReminder: (String) -> Unit,
    onAddRule: (String, String, String) -> Unit,
    onToggleRule: (AutomationRuleEntity) -> Unit,
    onDeleteRule: (AutomationRuleEntity) -> Unit,
    onClearAuditLogs: () -> Unit
) {
    var reminderTitle by remember { mutableStateOf("") }
    var reminderDelay by remember { mutableStateOf("15") }
    var ruleName by remember { mutableStateOf("") }
    var ruleDesc by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 640.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HudCard {
                Text("Schedule Reminder (WorkManager)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = reminderTitle,
                    onValueChange = { reminderTitle = it },
                    label = { Text("Reminder message") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reminder_title_input")
                )
                OutlinedTextField(
                    value = reminderDelay,
                    onValueChange = { reminderDelay = it },
                    label = { Text("Delay in minutes (1-525600)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reminder_delay_input")
                )
                Button(
                    onClick = {
                        val mins = reminderDelay.toLongOrNull() ?: 15L
                        if (reminderTitle.isNotBlank()) {
                            onScheduleReminder(reminderTitle, mins)
                            reminderTitle = ""
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("schedule_reminder_button")
                ) {
                    Text("Schedule Reminder")
                }
            }
        }

        if (pendingReminders.isNotEmpty()) {
            item { Text("Pending Reminders", style = MaterialTheme.typography.titleMedium) }
            items(pendingReminders, key = { it.first }) { (id, title) ->
                HudCard {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(
                        onClick = { onCancelReminder(id) },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Cancel Reminder")
                    }
                }
            }
        }

        item {
            HudCard {
                Text("Approved Automation Workflows", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = ruleName,
                    onValueChange = { ruleName = it },
                    label = { Text("Workflow Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ruleDesc,
                    onValueChange = { ruleDesc = it },
                    label = { Text("Workflow Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (ruleName.isNotBlank()) {
                            onAddRule(ruleName, ruleDesc, "open_app")
                            ruleName = ""
                            ruleDesc = ""
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("add_automation_rule_button")
                ) {
                    Text("Save Approved Workflow")
                }
            }
        }

        items(rules, key = { it.id }) { rule ->
            HudCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(rule.name, style = MaterialTheme.typography.titleMedium)
                    Switch(checked = rule.enabled, onCheckedChange = { onToggleRule(rule) })
                }
                Text(rule.description, style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(label = rule.actionTool, color = ArcCyan)
                    OutlinedButton(onClick = { onDeleteRule(rule) }, modifier = Modifier.height(48.dp)) {
                        Text("Delete")
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tool Audit Trail (${auditLogs.size})", style = MaterialTheme.typography.titleMedium)
                if (auditLogs.isNotEmpty()) {
                    OutlinedButton(onClick = onClearAuditLogs, modifier = Modifier.height(48.dp)) {
                        Text("Clear Logs")
                    }
                }
            }
        }

        items(auditLogs.take(25), key = { it.id }) { log ->
            val color = when (log.status) {
                "SUCCESS" -> EmeraldVerified
                "DENIED", "AWAITING_CONFIRMATION" -> AmberWarning
                else -> CrimsonAlert
            }
            HudCard(borderColor = color) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(log.toolId, style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                    StatusPill(label = log.status, color = color)
                }
                Text(log.summary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
