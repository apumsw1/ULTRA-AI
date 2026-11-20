package com.ultra.assistant.brain

import android.content.Context
import android.provider.Settings
import com.ultra.assistant.control.AppController
import com.ultra.assistant.control.ScreenController
import com.ultra.assistant.data.LocalSettingsRepository
import kotlinx.coroutines.flow.first

class ReasoningEngine(
    context: Context,
    private val aiEngine: AIEngine,
    private val settingsRepository: LocalSettingsRepository
) {
    private val appController = AppController(context.applicationContext)
    private val screenController = ScreenController(context.applicationContext)

    suspend fun decide(
        userInput: String,
        intent: IntentAnalysis,
        history: List<Pair<String, String>>,
        memorySummary: String,
        toolCatalogJson: String
    ): Pair<String, ReasoningDecision> {
        val settings = settingsRepository.settingsFlow.first()

        // Fast deterministic local path for immediate Android controls without network latency
        localFastPath(userInput, intent)?.let { localDecision ->
            return "Local Control Router" to localDecision
        }

        val screenContext = if (screenController.isReady()) {
            screenController.describeScreenConcise().getOrElse { "Unavailable" }
        } else {
            "Accessibility Service disabled"
        }

        val aiResult = aiEngine.reasonAndPlan(
            userMessage = userInput,
            history = history,
            memoryContext = memorySummary,
            toolCatalogJson = toolCatalogJson,
            screenContext = screenContext,
            languageLabel = settings.language
        )

        return aiResult.getOrElse { err ->
            val fallbackReply = when (intent.detectedLanguage) {
                "bn" -> "দুঃখিত, অনলাইন AI-এর সঙ্গে সংযোগ ব্যর্থ হয়েছে (${err.localizedMessage ?: "network error"})। লোকাল কমান্ড যেমন অ্যাপ খোলা বা রিমাইন্ডার ব্যবহার করতে পারেন।"
                "hi" -> "माफ़ कीजिए, ऑनलाइन AI से कनेक्ट नहीं हो सका (${err.localizedMessage ?: "network error"})। आप लोकल कमांड का उपयोग कर सकते हैं।"
                else -> "I couldn't reach the online AI brain (${err.localizedMessage ?: "network error"}). You can still use local app, browser, and reminder commands."
            }
            "Offline Fallback" to ReasoningDecision(
                thoughtSummary = "ModelRouter unavailable: ${err.message}",
                directReply = fallbackReply,
                plannedTools = emptyList()
            )
        }
    }

    private fun localFastPath(input: String, intent: IntentAnalysis): ReasoningDecision? {
        val lower = input.trim().lowercase()
        return when (intent.category) {
            IntentCategory.DEVICE_CONTROL -> {
                when {
                    lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("ওয়াইফাই") -> {
                        appController.openWifiPanel()
                        ReasoningDecision("Local Wi-Fi settings panel", "Opening internet settings.", emptyList())
                    }
                    lower.contains("bluetooth") || lower.contains("ব্লুটুথ") -> {
                        appController.openSettingsAction(Settings.ACTION_BLUETOOTH_SETTINGS)
                        ReasoningDecision("Local Bluetooth settings", "Opening Bluetooth settings.", emptyList())
                    }
                    lower.contains("lock") || lower.contains("লক") -> ReasoningDecision(
                        "Device lock requested",
                        "Checking Device Administrator permission to lock screen.",
                        listOf(PlannedToolCall("get_device_information", mapOf("action" to "lock_screen"), "Lock screen if Device Admin active"))
                    )
                    else -> ReasoningDecision(
                        "Device diagnostics requested",
                        "Checking device information and permission state.",
                        listOf(PlannedToolCall("get_device_information", mapOf("action" to "info"), "Read device info"))
                    )
                }
            }
            IntentCategory.APP_OPEN -> {
                val query = stripPrefixes(
                    input,
                    listOf("open the app ", "launch the app ", "open ", "launch ", "start ", "অ্যাপ খোলো ", "খুলে দাও ", "খোলো ", "চালু কর ", "ऐप खोलो ", "खोलो ", "चालू करो ")
                )
                if (query.isBlank()) null else ReasoningDecision(
                    "Fast local app launch",
                    "Opening $query.",
                    listOf(PlannedToolCall("open_app", mapOf("appName" to query), "Launch requested app"))
                )
            }
            IntentCategory.WEB_SEARCH -> {
                val query = stripPrefixes(
                    input,
                    listOf("search for ", "search ", "google ", "সার্চ করে ", "সার্চ করো ", "সার্চ কর ", "সার্চ ", "খুঁজে দেখ ", "খোঁজ কর ", "सर्च करो ", "सर्च ", "खोजो ")
                )
                if (query.isBlank()) null else ReasoningDecision(
                    "Fast web search",
                    "Searching the web for $query.",
                    listOf(PlannedToolCall("search_web", mapOf("query" to query, "platform" to "google"), "Run web search"))
                )
            }
            IntentCategory.NOTIFICATION_READ -> ReasoningDecision(
                "Read recent notifications",
                "Reading recent notifications.",
                listOf(PlannedToolCall("get_notifications", mapOf("limit" to "6"), "Fetch captured notifications"))
            )
            IntentCategory.SCREEN_INSPECT -> ReasoningDecision(
                "Inspect active screen",
                "Inspecting active accessible window.",
                listOf(PlannedToolCall("get_accessible_screen", emptyMap(), "Read Accessibility node tree"))
            )
            IntentCategory.SCREEN_ACTION -> {
                when {
                    lower.contains("back") || lower.contains("ব্যাক") -> ReasoningDecision(
                        "Global back navigation",
                        "Going back.",
                        listOf(PlannedToolCall("press_back", emptyMap(), "Perform Accessibility GLOBAL_ACTION_BACK"))
                    )
                    lower.contains("scroll up") || lower.contains("ऊपर") -> ReasoningDecision(
                        "Scroll up",
                        "Scrolling up.",
                        listOf(PlannedToolCall("scroll_screen", mapOf("direction" to "up"), "Scroll backward"))
                    )
                    lower.contains("scroll") || lower.contains("स्क्रॉल") -> ReasoningDecision(
                        "Scroll down",
                        "Scrolling down.",
                        listOf(PlannedToolCall("scroll_screen", mapOf("direction" to "down"), "Scroll forward"))
                    )
                    lower.startsWith("click ") || lower.startsWith("tap ") -> {
                        val target = input.substringAfter(" ").trim()
                        ReasoningDecision(
                            "Click UI element",
                            "Clicking $target.",
                            listOf(PlannedToolCall("click_ui_element", mapOf("targetText" to target), "Click matching UI element"))
                        )
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun stripPrefixes(input: String, prefixes: List<String>): String {
        val trimmed = input.trim()
        val match = prefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        return if (match != null) trimmed.drop(match.length).trim() else trimmed
    }
}
