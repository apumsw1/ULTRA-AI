package com.ultra.assistant.ui.apps

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ultra.assistant.control.InstalledAppInfo
import com.ultra.assistant.ui.components.HudCard
import com.ultra.assistant.ui.theme.ArcCyan

@Composable
fun AppManagerScreen(
    apps: List<InstalledAppInfo>,
    onLaunchApp: (String) -> Unit,
    onOpenAppInfo: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 640.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            HudCard {
                Text("Installed Applications (${apps.size})", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Launch visible Android launcher apps or inspect system App Info safely.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("app_search_input")
                )
            }
        }

        items(filtered, key = { it.packageName }) { app ->
            HudCard {
                Text(app.label, style = MaterialTheme.typography.titleMedium)
                Text(app.packageName, style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onLaunchApp(app.packageName) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Open App")
                    }
                    OutlinedButton(
                        onClick = { onOpenAppInfo(app.packageName) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("App Info")
                    }
                }
            }
        }
    }
}
