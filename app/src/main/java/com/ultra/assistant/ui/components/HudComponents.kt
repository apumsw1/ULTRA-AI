package com.ultra.assistant.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ultra.assistant.brain.PendingToolConfirmation
import com.ultra.assistant.tools.RiskLevel
import com.ultra.assistant.ui.theme.AmberWarning
import com.ultra.assistant.ui.theme.ArcCyan
import com.ultra.assistant.ui.theme.CrimsonAlert
import com.ultra.assistant.ui.theme.EmeraldVerified

@Composable
fun HudCard(
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

fun riskColor(risk: RiskLevel): Color = when (risk) {
    RiskLevel.LOW -> EmeraldVerified
    RiskLevel.MEDIUM -> ArcCyan
    RiskLevel.HIGH -> AmberWarning
}

@Composable
fun SensitiveConfirmationDialog(
    confirmation: PendingToolConfirmation,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDeny,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(label = "HIGH RISK GATE", color = AmberWarning)
                Text("Confirm Action", style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(confirmation.promptMessage, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "ULTRA AI never executes sensitive actions without explicit approval.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onApprove,
                modifier = Modifier.testTag("confirm_sensitive_action_button")
            ) {
                Text("Approve")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDeny,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonAlert),
                modifier = Modifier.testTag("deny_sensitive_action_button")
            ) {
                Text("Cancel")
            }
        }
    )
}
