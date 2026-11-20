package com.ultra.assistant.tools

import android.content.Context
import com.ultra.assistant.automation.RuleManager
import com.ultra.assistant.automation.TaskScheduler
import com.ultra.assistant.control.AppController
import com.ultra.assistant.control.BrowserController
import com.ultra.assistant.control.DeviceManagementController
import com.ultra.assistant.control.NotificationRepository
import com.ultra.assistant.control.ScreenController
import com.ultra.assistant.control.SmsController
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.memory.MemoryManager
import com.ultra.assistant.permissions.PermissionManager
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

class ToolRegistry(
    context: Context,
    private val memoryManager: MemoryManager,
    private val settingsRepository: LocalSettingsRepository = LocalSettingsRepository(context)
) {
    private val appContext = context.applicationContext
    private val appController = AppController(appContext)
    private val screenController = ScreenController(appContext)
    private val smsController = SmsController(appContext)
    private val browserController = BrowserController(appContext)
    private val deviceController = DeviceManagementController(appContext)
    private val taskScheduler = TaskScheduler(appContext)
    private val ruleManager = RuleManager(appContext)

    private val tools: Map<String, UltraTool> = listOf(
        OpenAppTool(),
        GetAccessibleScreenTool(),
        ClickUiElementTool(),
        TypeTextTool(),
        ScrollScreenTool(),
        PressBackTool(),
        GetNotificationsTool(),
        PrepareSmsTool(),
        OpenBrowserTool(),
        SearchWebTool(),
        GetDeviceInformationTool(),
        CreateReminderTool(),
        ManageApprovedAutomationTool()
    ).associateBy { it.id }

    fun allTools(): List<UltraTool> = tools.values.toList()

    fun getTool(id: String): UltraTool? = tools[id]

    fun schemaSummaryForPrompt(): String {
        val array = JSONArray()
        tools.values.forEach { tool ->
            val paramsObj = JSONObject()
            tool.parameters.forEach { p ->
                paramsObj.put(p.name, "${p.type}${if (p.required) " (required)" else " (optional)"}: ${p.description}")
            }
            array.put(
                JSONObject().apply {
                    put("id", tool.id)
                    put("description", tool.description)
                    put("risk", tool.riskLevel.name)
                    put("params", paramsObj)
                }
            )
        }
        return array.toString()
    }

    private inner class OpenAppTool : UltraTool {
        override val id = "open_app"
        override val title = "Open App"
        override val description = "Find and launch an installed Android application by name or package ID."
        override val parameters = listOf(
            ToolParamSpec("appName", "string", "App name or package name to launch", required = true)
        )
        override val requiredPermissions = emptyList<PermissionRequirement>()
        override val riskLevel = RiskLevel.LOW

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            val query = args["appName"].orEmpty().trim()
            val result = appController.launchApp(query)
            return result.fold(
                onSuccess = { app ->
                    ToolExecutionResult(id, true, "Launched ${app.label} (${app.packageName}).", app.packageName)
                },
                onFailure = { err ->
                    ToolExecutionResult(id, false, err.message ?: "Could not open '$query'.")
                }
            )
        }
    }

    private inner class GetAccessibleScreenTool : UltraTool {
        override val id = "get_accessible_screen"
        override val title = "Inspect Active Screen"
        override val description = "Read visible UI controls and active window structure via Android Accessibility."
        override val parameters = emptyList<ToolParamSpec>()
        override val requiredPermissions = listOf(PermissionRequirement.ACCESSIBILITY)
        override val riskLevel = RiskLevel.MEDIUM

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            if (!PermissionManager.isAccessibilityEnabled(appContext)) {
                return ToolExecutionResult(
                    id,
                    false,
                    "Accessibility Service is not enabled. Enable ULTRA AI in Accessibility Settings first.",
                    missingPermission = PermissionRequirement.ACCESSIBILITY
                )
            }
            return screenController.describeScreenConcise().fold(
                onSuccess = { desc -> ToolExecutionResult(id, true, "Captured accessible screen.", desc) },
                onFailure = { err -> ToolExecutionResult(id, false, err.message ?: "Failed to inspect screen.") }
            )
        }
    }

    private inner class ClickUiElementTool : UltraTool {
        override val id = "click_ui_element"
        override val title = "Click UI Element"
        override val description = "Click a visible button or control on the current screen by text or viewId."
        override val parameters = listOf(
            ToolParamSpec("targetText", "string", "Visible text or label of the UI control", required = false),
            ToolParamSpec("viewId", "string", "Optional Android view resource ID", required = false)
        )
        override val requiredPermissions = listOf(PermissionRequirement.ACCESSIBILITY)
        override val riskLevel = RiskLevel.MEDIUM

        override fun validateArgs(args: Map<String, String>): String? {
            if (args["targetText"].isNullOrBlank() && args["viewId"].isNullOrBlank()) {
                return "Provide either 'targetText' or 'viewId' for click_ui_element."
            }
            return null
        }

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            if (!PermissionManager.isAccessibilityEnabled(appContext)) {
                return ToolExecutionResult(
                    id,
                    false,
                    "Accessibility Service is required to click UI elements.",
                    missingPermission = PermissionRequirement.ACCESSIBILITY
                )
            }
            val res = screenController.clickElement(args["targetText"].orEmpty(), args["viewId"].orEmpty())
            return ToolExecutionResult(id, res.success, res.message, res.affectedElement.orEmpty())
        }
    }

    private inner class TypeTextTool : UltraTool {
        override val id = "type_text"
        override val title = "Type Into Field"
        override val description = "Enter text into an editable non-password field on the active screen."
        override val parameters = listOf(
            ToolParamSpec("text", "string", "Text to enter into the field", required = true),
            ToolParamSpec("targetField", "string", "Optional label/hint/viewId of target field", required = false)
        )
        override val requiredPermissions = listOf(PermissionRequirement.ACCESSIBILITY)
        override val riskLevel = RiskLevel.MEDIUM

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            if (!PermissionManager.isAccessibilityEnabled(appContext)) {
                return ToolExecutionResult(
                    id,
                    false,
                    "Accessibility Service is required to type text.",
                    missingPermission = PermissionRequirement.ACCESSIBILITY
                )
            }
            val res = screenController.typeText(args["targetField"].orEmpty(), args["text"].orEmpty())
            return ToolExecutionResult(id, res.success, res.message, res.affectedElement.orEmpty())
        }
    }

    private inner class ScrollScreenTool : UltraTool {
        override val id = "scroll_screen"
        override val title = "Scroll Screen"
        override val description = "Scroll the active screen up/backward or down/forward."
        override val parameters = listOf(
            ToolParamSpec("direction", "string", "'down' or 'up'", required = false)
        )
        override val requiredPermissions = listOf(PermissionRequirement.ACCESSIBILITY)
        override val riskLevel = RiskLevel.LOW

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            if (!PermissionManager.isAccessibilityEnabled(appContext)) {
                return ToolExecutionResult(
                    id,
                    false,
                    "Accessibility Service is required to scroll.",
                    missingPermission = PermissionRequirement.ACCESSIBILITY
                )
            }
            val dir = args["direction"]?.ifBlank { "down" } ?: "down"
            val res = screenController.scroll(dir)
            return ToolExecutionResult(id, res.success, res.message)
        }
    }

    private inner class PressBackTool : UltraTool {
        override val id = "press_back"
        override val title = "Press Back"
        override val description = "Perform global Android Back navigation."
        override val parameters = emptyList<ToolParamSpec>()
        override val requiredPermissions = listOf(PermissionRequirement.ACCESSIBILITY)
        override val riskLevel = RiskLevel.LOW

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            if (!PermissionManager.isAccessibilityEnabled(appContext)) {
                return ToolExecutionResult(
                    id,
                    false,
                    "Accessibility Service is required for global back navigation.",
                    missingPermission = PermissionRequirement.ACCESSIBILITY
                )
            }
            val res = screenController.pressBack()
            return ToolExecutionResult(id, res.success, res.message)
        }
    }

    private inner class GetNotificationsTool : UltraTool {
        override val id = "get_notifications"
        override val title = "Read Notifications"
        override val description = "Read recent privacy-filtered notifications captured by NotificationListenerService."
        override val parameters = listOf(
            ToolParamSpec("limit", "integer", "Max notifications to return (1-20)", required = false)
        )
        override val requiredPermissions = listOf(PermissionRequirement.NOTIFICATION_LISTENER)
        override val riskLevel = RiskLevel.MEDIUM

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            if (!PermissionManager.isNotificationListenerEnabled(appContext)) {
                return ToolExecutionResult(
                    id,
                    false,
                    "Notification Access is not enabled. Grant access in Notification Listener Settings.",
                    missingPermission = PermissionRequirement.NOTIFICATION_LISTENER
                )
            }
            val limit = (args["limit"]?.toIntOrNull() ?: 6).coerceIn(1, 20)
            val list = NotificationRepository.notificationsFlow.value.take(limit)
            if (list.isEmpty()) {
                return ToolExecutionResult(id, true, "No active notifications captured yet.")
            }
            val summary = list.joinToString("\n") { n ->
                "[${n.category.name}] ${n.title}: ${n.text}${if (n.sensitiveRedacted) " (redacted)" else ""}"
            }
            return ToolExecutionResult(id, true, "Found ${list.size} notification(s).", summary)
        }
    }

    private inner class PrepareSmsTool : UltraTool {
        override val id = "prepare_sms"
        override val title = "Prepare SMS"
        override val description = "Prepare a contextual SMS draft in the messaging app (or send directly only when default SMS handler & confirmed)."
        override val parameters = listOf(
            ToolParamSpec("recipient", "string", "Phone number or recipient identifier", required = false),
            ToolParamSpec("message", "string", "Message body to prefill", required = true),
            ToolParamSpec("directSend", "boolean", "Attempt direct send if default SMS handler", required = false)
        )
        override val requiredPermissions = emptyList<PermissionRequirement>()
        override val riskLevel = RiskLevel.HIGH

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            val recipient = args["recipient"].orEmpty().trim()
            val message = args["message"].orEmpty().trim()
            val directSend = args["directSend"]?.equals("true", ignoreCase = true) == true
            val settings = settingsRepository.settingsFlow.first()

            val result = if (directSend && settings.directSmsEnabled && PermissionManager.isDefaultSmsApp(appContext)) {
                val direct = smsController.sendDirectIfAuthorized(recipient, message)
                if (direct.success) direct else smsController.prepareDraftIntent(recipient, message)
            } else {
                smsController.prepareDraftIntent(recipient, message)
            }
            return ToolExecutionResult(
                toolId = id,
                success = result.success,
                summary = result.message,
                outputData = result.mode
            )
        }
    }

    private inner class OpenBrowserTool : UltraTool {
        override val id = "open_browser"
        override val title = "Open Browser URL"
        override val description = "Open a validated http/https URL in the user's web browser."
        override val parameters = listOf(
            ToolParamSpec("url", "string", "Target http:// or https:// URL", required = true)
        )
        override val requiredPermissions = emptyList<PermissionRequirement>()
        override val riskLevel = RiskLevel.LOW

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            val url = args["url"].orEmpty()
            return browserController.openUrl(url).fold(
                onSuccess = { opened -> ToolExecutionResult(id, true, "Opened $opened in browser.", opened) },
                onFailure = { err -> ToolExecutionResult(id, false, err.message ?: "Invalid URL.") }
            )
        }
    }

    private inner class SearchWebTool : UltraTool {
        override val id = "search_web"
        override val title = "Search Web"
        override val description = "Search Google or YouTube Music in the browser."
        override val parameters = listOf(
            ToolParamSpec("query", "string", "Search query", required = true),
            ToolParamSpec("platform", "string", "'google' or 'youtube_music'", required = false)
        )
        override val requiredPermissions = emptyList<PermissionRequirement>()
        override val riskLevel = RiskLevel.LOW

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            val query = args["query"].orEmpty()
            val platform = args["platform"].orEmpty().lowercase()
            val res = if (platform == "youtube_music" || platform == "music") {
                browserController.searchYouTubeMusic(query)
            } else {
                browserController.searchWeb(query)
            }
            return res.fold(
                onSuccess = { url -> ToolExecutionResult(id, true, "Searching for '$query'.", url) },
                onFailure = { err -> ToolExecutionResult(id, false, err.message ?: "Web search failed.") }
            )
        }
    }

    private inner class GetDeviceInformationTool : UltraTool {
        override val id = "get_device_information"
        override val title = "Get Device Information"
        override val description = "Retrieve device model, Android version, permission status, and optional screen lock check."
        override val parameters = listOf(
            ToolParamSpec("action", "string", "Optional 'info' or 'lock_screen'", required = false)
        )
        override val requiredPermissions = emptyList<PermissionRequirement>()
        override val riskLevel = RiskLevel.LOW

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            val action = args["action"].orEmpty().lowercase()
            if (action == "lock_screen") {
                if (!deviceController.isAdminActive()) {
                    return ToolExecutionResult(
                        id,
                        false,
                        "Device Administrator is not enabled. Enable it in Settings > Security to lock the screen.",
                        missingPermission = PermissionRequirement.DEVICE_ADMIN
                    )
                }
                return deviceController.lockScreenIfAuthorized().fold(
                    onSuccess = { msg -> ToolExecutionResult(id, true, msg) },
                    onFailure = { err -> ToolExecutionResult(id, false, err.message ?: "Lock failed.") }
                )
            }
            val info = deviceController.getDeviceInfo()
            val perms = PermissionManager.snapshot(appContext)
            val summary = "${info.manufacturer} ${info.model} (Android ${info.androidVersion}, API ${info.sdkInt}) | " +
                "Mic=${perms.microphoneGranted}, Accessibility=${perms.accessibilityEnabled}, " +
                "NotifListener=${perms.notificationListenerEnabled}, Admin=${perms.deviceAdminActive}"
            return ToolExecutionResult(id, true, "Device diagnostics collected.", summary)
        }
    }

    private inner class CreateReminderTool : UltraTool {
        override val id = "create_reminder"
        override val title = "Create Reminder"
        override val description = "Schedule a persistent background reminder via Android WorkManager."
        override val parameters = listOf(
            ToolParamSpec("title", "string", "Reminder message", required = true),
            ToolParamSpec("delayMinutes", "integer", "Minutes from now (1-525600)", required = true)
        )
        override val requiredPermissions = listOf(PermissionRequirement.POST_NOTIFICATIONS)
        override val riskLevel = RiskLevel.LOW

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            val title = args["title"].orEmpty().trim()
            val delay = args["delayMinutes"]?.toLongOrNull()?.coerceIn(1L, 525600L) ?: 10L
            return runCatching {
                val workId = taskScheduler.scheduleReminder(title, delay)
                ToolExecutionResult(
                    id,
                    true,
                    "Scheduled reminder '$title' in $delay minute(s).",
                    workId
                )
            }.getOrElse { err ->
                ToolExecutionResult(id, false, err.message ?: "Failed to schedule reminder.")
            }
        }
    }

    private inner class ManageApprovedAutomationTool : UltraTool {
        override val id = "manage_approved_automation"
        override val title = "Manage Approved Automation"
        override val description = "Create or list user-approved automation workflows."
        override val parameters = listOf(
            ToolParamSpec("operation", "string", "'add' or 'list'", required = false),
            ToolParamSpec("name", "string", "Workflow name (for add)", required = false),
            ToolParamSpec("description", "string", "Workflow description (for add)", required = false),
            ToolParamSpec("actionTool", "string", "Target tool id (for add)", required = false)
        )
        override val requiredPermissions = emptyList<PermissionRequirement>()
        override val riskLevel = RiskLevel.MEDIUM

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            val op = args["operation"]?.lowercase() ?: "list"
            if (op == "add") {
                val name = args["name"].orEmpty().trim()
                val desc = args["description"].orEmpty().trim().ifBlank { "User-approved workflow" }
                val actionTool = args["actionTool"].orEmpty().trim().ifBlank { "open_app" }
                if (name.isBlank()) {
                    return ToolExecutionResult(id, false, "Automation 'name' is required.")
                }
                ruleManager.add(name, desc)
                val saved = memoryManager.saveAutomationRule(
                    name = name,
                    description = desc,
                    triggerType = "MANUAL_WORKFLOW",
                    actionTool = actionTool,
                    actionArgsJson = "{}"
                )
                return ToolExecutionResult(id, true, "Saved automation workflow '${saved.name}'.", saved.id)
            }
            val rules = memoryManager.getEnabledAutomationRules()
            val text = if (rules.isEmpty()) "No enabled automation rules." else rules.joinToString("; ") { "${it.name} (${it.actionTool})" }
            return ToolExecutionResult(id, true, "Listed ${rules.size} enabled automation(s).", text)
        }
    }
}
