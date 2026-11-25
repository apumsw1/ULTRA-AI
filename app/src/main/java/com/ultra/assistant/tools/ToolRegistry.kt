package com.ultra.assistant.tools

import android.content.Context
import android.content.Intent
import com.ultra.assistant.automation.RuleManager
import com.ultra.assistant.automation.TaskScheduler
import com.ultra.assistant.control.AppController
import com.ultra.assistant.control.BrowserController
import com.ultra.assistant.control.DeviceManagementController
import com.ultra.assistant.control.MediaVolumeController
import com.ultra.assistant.control.NotificationRepository
import com.ultra.assistant.control.PhoneContactsController
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
    private val mediaVolumeController = MediaVolumeController(appContext)
    private val phoneContactsController = PhoneContactsController(appContext)
    private val taskScheduler = TaskScheduler(appContext)
    private val ruleManager = RuleManager(appContext)

    private val tools: Map<String, UltraTool> = listOf(
        OpenAppTool(),
        SearchYouTubeTool(),
        ControlMediaVolumeTool(),
        ToggleFlashlightTool(),
        OpenDeviceSettingsTool(),
        MakePhoneCallTool(),
        NavigateSystemTool(),
        GetAccessibleScreenTool(),
        ReadScreenAloudTool(),
        ClickUiElementTool(),
        TypeTextTool(),
        ScrollScreenTool(),
        PressBackTool(),
        ManageEmailTool(),
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
                    ToolExecutionResult(id, true, "Launched ${app.label}.", app.packageName)
                },
                onFailure = { err ->
                    ToolExecutionResult(id, false, err.message ?: "Could not open '$query'.")
                }
            )
        }
    }

    private inner class SearchYouTubeTool : UltraTool {
        override val id = "search_youtube"
        override val title = "Search YouTube"
        override val description = "Search YouTube videos or channels in the YouTube app or browser."
        override val parameters = listOf(
            ToolParamSpec("query", "string", "Topic, video title, or creator to search on YouTube", required = true)
        )
        override val requiredPermissions = emptyList<PermissionRequirement>()
        override val riskLevel = RiskLevel.LOW

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            val query = args["query"].orEmpty().trim()
            return browserController.searchYouTube(query).fold(
                onSuccess = { url -> ToolExecutionResult(id, true, "Searching YouTube for '$query'.", url) },
                onFailure = { err -> ToolExecutionResult(id, false, err.message ?: "Could not open YouTube search.") }
            )
        }
    }

    private inner class ControlMediaVolumeTool : UltraTool {
        override val id = "control_media_volume"
        override val title = "Media & Volume Control"
        override val description = "Adjust device media volume ('up','down','mute','unmute','set') or control media playback ('play','pause','play_pause','next','previous','stop')."
        override val parameters = listOf(
            ToolParamSpec("volumeAction", "string", "Optional: up, down, mute, unmute, set", required = false),
            ToolParamSpec("levelPercent", "integer", "Optional 0-100 when volumeAction='set'", required = false),
            ToolParamSpec("mediaAction", "string", "Optional: play, pause, play_pause, next, previous, stop", required = false)
        )
        override val requiredPermissions = emptyList<PermissionRequirement>()
        override val riskLevel = RiskLevel.LOW

        override fun validateArgs(args: Map<String, String>): String? {
            if (args["volumeAction"].isNullOrBlank() && args["mediaAction"].isNullOrBlank()) {
                return "Provide 'volumeAction' or 'mediaAction'."
            }
            val pctRaw = args["levelPercent"]
            if (!pctRaw.isNullOrBlank() && pctRaw.toIntOrNull() == null) {
                return "Parameter 'levelPercent' must be an integer."
            }
            return null
        }

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            val mediaAct = args["mediaAction"].orEmpty().trim()
            val volAct = args["volumeAction"].orEmpty().trim()
            val pct = args["levelPercent"]?.toIntOrNull()
            val res = if (mediaAct.isNotBlank()) {
                mediaVolumeController.controlMediaPlayback(mediaAct)
            } else {
                mediaVolumeController.adjustVolume(volAct, pct)
            }
            return res.fold(
                onSuccess = { msg -> ToolExecutionResult(id, true, msg) },
                onFailure = { err -> ToolExecutionResult(id, false, err.message ?: "Audio control failed.") }
            )
        }
    }

    private inner class ToggleFlashlightTool : UltraTool {
        override val id = "toggle_flashlight"
        override val title = "Flashlight Control"
        override val description = "Turn the device camera flashlight on, off, or toggle it."
        override val parameters = listOf(
            ToolParamSpec("state", "string", "Optional: 'on', 'off', or 'toggle'", required = false)
        )
        override val requiredPermissions = emptyList<PermissionRequirement>()
        override val riskLevel = RiskLevel.LOW

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            val enable = when (args["state"]?.trim()?.lowercase()) {
                "on", "true", "enable" -> true
                "off", "false", "disable" -> false
                else -> null
            }
            return mediaVolumeController.setFlashlight(enable).fold(
                onSuccess = { msg -> ToolExecutionResult(id, true, msg) },
                onFailure = { err -> ToolExecutionResult(id, false, err.message ?: "Flashlight unavailable.") }
            )
        }
    }

    private inner class OpenDeviceSettingsTool : UltraTool {
        override val id = "open_device_settings"
        override val title = "Open System Settings"
        override val description = "Open permitted Android settings or system panels (wifi, bluetooth, network, sound, brightness, notifications, battery, main)."
        override val parameters = listOf(
            ToolParamSpec("target", "string", "Settings section: wifi, bluetooth, network, sound, brightness, notifications, battery, main", required = true)
        )
        override val requiredPermissions = emptyList<PermissionRequirement>()
        override val riskLevel = RiskLevel.LOW

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            val target = args["target"].orEmpty().trim().ifBlank { "main" }
            return appController.openDeviceSettings(target).fold(
                onSuccess = { msg -> ToolExecutionResult(id, true, msg) },
                onFailure = { err -> ToolExecutionResult(id, false, err.message ?: "Unable to open settings.") }
            )
        }
    }

    private inner class MakePhoneCallTool : UltraTool {
        override val id = "make_phone_call"
        override val title = "Phone Call / Dialer"
        override val description = "Look up a contact or phone number and open the phone dialer or place a call after user confirmation."
        override val parameters = listOf(
            ToolParamSpec("target", "string", "Contact name or phone number", required = true),
            ToolParamSpec("directCall", "boolean", "If true and CALL_PHONE permission granted, dial directly after confirmation", required = false)
        )
        override val requiredPermissions = emptyList<PermissionRequirement>()
        override val riskLevel = RiskLevel.HIGH

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            val target = args["target"].orEmpty().trim()
            val direct = args["directCall"]?.equals("true", ignoreCase = true) == true
            val matchRes = phoneContactsController.findContactNumber(target)
            if (matchRes.isFailure) {
                val err = matchRes.exceptionOrNull()
                if (!PermissionManager.hasContacts(appContext) && target.any { it.isLetter() }) {
                    return ToolExecutionResult(
                        id,
                        false,
                        "Contacts permission is needed to look up '$target'. Grant Contacts permission or provide a phone number.",
                        missingPermission = PermissionRequirement.READ_CONTACTS
                    )
                }
                return ToolExecutionResult(id, false, err?.message ?: "Could not find phone number for '$target'.")
            }
            val match = matchRes.getOrThrow()
            val callRes = if (direct) {
                phoneContactsController.placeCallIfPermitted(match.phoneNumber)
            } else {
                phoneContactsController.openDialer(match.phoneNumber)
            }
            return callRes.fold(
                onSuccess = { msg -> ToolExecutionResult(id, true, msg, "${match.displayName}: ${match.phoneNumber}") },
                onFailure = { err -> ToolExecutionResult(id, false, err.message ?: "Unable to open dialer.") }
            )
        }
    }

    private inner class NavigateSystemTool : UltraTool {
        override val id = "navigate_system"
        override val title = "System Navigation"
        override val description = "Perform global Android navigation: 'home', 'recents', 'notifications', 'quick_settings', 'lock_screen', or 'back'."
        override val parameters = listOf(
            ToolParamSpec("action", "string", "'home', 'recents', 'notifications', 'quick_settings', 'lock_screen', or 'back'", required = true)
        )
        override val requiredPermissions = listOf(PermissionRequirement.ACCESSIBILITY)
        override val riskLevel = RiskLevel.LOW

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            if (!PermissionManager.isAccessibilityEnabled(appContext)) {
                return ToolExecutionResult(
                    id,
                    false,
                    "Accessibility Service is required for system navigation.",
                    missingPermission = PermissionRequirement.ACCESSIBILITY
                )
            }
            val res = screenController.navigateSystem(args["action"].orEmpty().ifBlank { "home" })
            return ToolExecutionResult(id, res.success, res.message)
        }
    }

    private inner class GetAccessibleScreenTool : UltraTool {
        override val id = "get_accessible_screen"
        override val title = "Inspect Active Screen"
        override val description = "Read visible UI controls, search bars, and active window structure via Android Accessibility."
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

    private inner class ReadScreenAloudTool : UltraTool {
        override val id = "read_screen_aloud"
        override val title = "Read Screen Aloud"
        override val description = "Summarize visible screen text or open email content in human-readable form for speaking aloud."
        override val parameters = emptyList<ToolParamSpec>()
        override val requiredPermissions = listOf(PermissionRequirement.ACCESSIBILITY)
        override val riskLevel = RiskLevel.LOW

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            if (!PermissionManager.isAccessibilityEnabled(appContext)) {
                return ToolExecutionResult(
                    id,
                    false,
                    "Accessibility Service is required to read the active screen aloud.",
                    missingPermission = PermissionRequirement.ACCESSIBILITY
                )
            }
            val res = screenController.readScreenAloud()
            return ToolExecutionResult(id, res.success, res.message, res.affectedElement.orEmpty())
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
            ToolParamSpec("targetField", "string", "Optional label/hint/viewId of target field", required = false),
            ToolParamSpec("submit", "boolean", "Press Enter/Search after typing", required = false)
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
            val submit = args["submit"]?.equals("true", ignoreCase = true) == true
            val res = screenController.typeText(args["targetField"].orEmpty(), args["text"].orEmpty(), submit = submit)
            return ToolExecutionResult(id, res.success, res.message, res.affectedElement.orEmpty())
        }
    }

    private inner class ScrollScreenTool : UltraTool {
        override val id = "scroll_screen"
        override val title = "Scroll Screen"
        override val description = "Scroll the active screen down, up, left, or right."
        override val parameters = listOf(
            ToolParamSpec("direction", "string", "'down', 'up', 'left', or 'right'", required = false)
        )
        override val requiredPermissions = listOf(PermissionRequirement.ACCESSIBILITY)
        override val riskLevel = RiskLevel.LOW

        override fun validateArgs(args: Map<String, String>): String? {
            val dir = args["direction"]?.trim()?.lowercase().orEmpty()
            if (dir.isNotEmpty() && dir !in setOf("down", "up", "left", "right")) {
                return "Invalid scroll direction '$dir'. Expected down, up, left, or right."
            }
            return null
        }

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

    private inner class ManageEmailTool : UltraTool {
        override val id = "manage_email"
        override val title = "Email Assistant"
        override val description = "Open email app ('open_email'), read visible inbox/email aloud ('read_email'), move to next email ('next_email'), go back ('go_back'), or delete currently open email ('delete_email' — always requires explicit user confirmation; bulk deletion is blocked unless explicitly confirmed)."
        override val parameters = listOf(
            ToolParamSpec("action", "string", "'open_email', 'read_email', 'next_email', 'go_back', or 'delete_email'", required = true),
            ToolParamSpec("targetEmail", "string", "Optional subject or sender to tap/open before acting", required = false)
        )
        override val requiredPermissions = emptyList<PermissionRequirement>()
        override val riskLevel = RiskLevel.MEDIUM

        override fun validateArgs(args: Map<String, String>): String? {
            val action = args["action"]?.trim()?.lowercase().orEmpty()
            if (action.isBlank()) return "Provide an email 'action' (open_email, read_email, next_email, go_back, delete_email)."
            if (action.contains("bulk") || action.contains("all")) {
                return "Safety guard: Bulk email deletion cannot run automatically. Open and confirm specific emails individually."
            }
            return null
        }

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            val action = args["action"]?.trim()?.lowercase().orEmpty()
            val targetEmail = args["targetEmail"].orEmpty().trim()
            if (action == "open_email") {
                val gmail = appController.launchApp("Gmail")
                if (gmail.isSuccess) {
                    return ToolExecutionResult(id, true, "Opened Gmail.", gmail.getOrNull()?.packageName.orEmpty())
                }
                val fallback = runCatching {
                    val intent = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_APP_EMAIL)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                }
                return if (fallback.isSuccess) {
                    ToolExecutionResult(id, true, "Opened your default email app.")
                } else {
                    ToolExecutionResult(id, false, "No email app found on this device.")
                }
            }

            if (!PermissionManager.isAccessibilityEnabled(appContext)) {
                return ToolExecutionResult(
                    id,
                    false,
                    "Accessibility Service is required to read or control inside your email app.",
                    missingPermission = PermissionRequirement.ACCESSIBILITY
                )
            }

            if (targetEmail.isNotBlank()) {
                val openRes = screenController.clickElement(targetEmail)
                if (!openRes.success) {
                    return ToolExecutionResult(id, false, "Could not find email matching '$targetEmail' on screen.")
                }
            }

            return when (action) {
                "read_email" -> {
                    val res = screenController.readScreenAloud()
                    ToolExecutionResult(id, res.success, res.message)
                }
                "next_email" -> {
                    val res = screenController.clickNextInApp()
                    ToolExecutionResult(id, res.success, res.message)
                }
                "go_back" -> {
                    val res = screenController.pressBack()
                    ToolExecutionResult(id, res.success, res.message)
                }
                "delete_email" -> {
                    val deleteClick = listOf("Delete", "Trash", "Move to Trash", "মুছুন", "ডিলিট", "हटाएं")
                        .firstNotNullOfOrNull { label ->
                            screenController.clickElement(label).takeIf { it.success }
                        }
                    if (deleteClick != null) {
                        ToolExecutionResult(id, true, "Deleted the selected email as confirmed.", deleteClick.affectedElement.orEmpty())
                    } else {
                        ToolExecutionResult(id, false, "Could not locate a Delete button on the active email screen.")
                    }
                }
                else -> ToolExecutionResult(id, false, "Unsupported email action '$action'.")
            }
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
                return ToolExecutionResult(id, true, "No new notifications right now.")
            }
            val summary = list.joinToString("\n") { n ->
                "[${n.category.name}] ${n.title}: ${n.text}${if (n.sensitiveRedacted) " (redacted)" else ""}"
            }
            return ToolExecutionResult(id, true, "You have ${list.size} recent notification(s).", summary)
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
            val recipientInput = args["recipient"].orEmpty().trim()
            val resolvedRecipient = if (recipientInput.isNotBlank() && recipientInput.any { it.isLetter() }) {
                phoneContactsController.findContactNumber(recipientInput).getOrNull()?.phoneNumber ?: recipientInput
            } else {
                recipientInput
            }
            val message = args["message"].orEmpty().trim()
            val directSend = args["directSend"]?.equals("true", ignoreCase = true) == true
            val settings = settingsRepository.settingsFlow.first()

            val result = if (directSend && settings.directSmsEnabled && PermissionManager.isDefaultSmsApp(appContext)) {
                val direct = smsController.sendDirectIfAuthorized(resolvedRecipient, message)
                if (direct.success) direct else smsController.prepareDraftIntent(resolvedRecipient, message)
            } else {
                smsController.prepareDraftIntent(resolvedRecipient, message)
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
        override val description = "Search Google, YouTube, or YouTube Music."
        override val parameters = listOf(
            ToolParamSpec("query", "string", "Search query", required = true),
            ToolParamSpec("platform", "string", "'google', 'youtube', or 'youtube_music'", required = false)
        )
        override val requiredPermissions = emptyList<PermissionRequirement>()
        override val riskLevel = RiskLevel.LOW

        override suspend fun execute(args: Map<String, String>): ToolExecutionResult {
            val query = args["query"].orEmpty()
            val platform = args["platform"].orEmpty().lowercase()
            val res = when (platform) {
                "youtube_music", "music" -> browserController.searchYouTubeMusic(query)
                "youtube" -> browserController.searchYouTube(query)
                else -> browserController.searchWeb(query)
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
        override val description = "Retrieve device model, Android version, battery level, permission status, and optional screen lock."
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
            val battery = mediaVolumeController.batterySummary()
            val perms = PermissionManager.snapshot(appContext)
            val summary = "${info.manufacturer} ${info.model} (Android ${info.androidVersion}, API ${info.sdkInt}) | " +
                "Battery: $battery | Mic=${perms.microphoneGranted}, Accessibility=${perms.accessibilityEnabled}, " +
                "NotifListener=${perms.notificationListenerEnabled}, Contacts=${perms.contactsGranted}, Call=${perms.callPhoneGranted}"
            return ToolExecutionResult(id, true, "Battery is $battery on ${info.manufacturer} ${info.model}.", summary)
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
