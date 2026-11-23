package com.ultra.assistant.brain

import android.content.Context
import com.ultra.assistant.control.ScreenController
import com.ultra.assistant.data.AiOperatingMode
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.data.NetworkMonitor
import com.ultra.assistant.memory.MemoryManager
import kotlinx.coroutines.flow.first

class ReasoningEngine(
    context: Context,
    private val aiEngine: AIEngine,
    private val settingsRepository: LocalSettingsRepository,
    private val networkMonitor: NetworkMonitor,
    memoryManager: MemoryManager
) {
    private val screenController = ScreenController(context.applicationContext)
    private val offlineEngine = OfflineAssistantEngine(context.applicationContext, memoryManager)

    suspend fun isEffectiveOffline(): Boolean {
        val settings = settingsRepository.settingsFlow.first()
        return when (settings.operatingMode) {
            AiOperatingMode.OFFLINE -> true
            AiOperatingMode.ONLINE -> false
            AiOperatingMode.AUTO -> !networkMonitor.checkCurrentConnectivity()
        }
    }

    suspend fun decide(
        userInput: String,
        intent: IntentAnalysis,
        history: List<Pair<String, String>>,
        memorySummary: String,
        toolCatalogJson: String
    ): Pair<String, ReasoningDecision> {
        val settings = settingsRepository.settingsFlow.first()

        // Fast deterministic local tool routing for immediate Android device controls
        val localDecision = localFastPath(userInput, intent)
        if (localDecision != null) {
            return "Local Agent" to localDecision
        }

        // Offline Mode routing
        if (isEffectiveOffline()) {
            return offlineEngine.respond(userInput, intent, null)
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
            // Fall back cleanly if network/AI drops mid-request
            val offlineFallback = offlineEngine.respond(userInput, intent, null)
            val errDetail = err.localizedMessage ?: "connection error"
            val combinedReply = if (settings.operatingMode == AiOperatingMode.ONLINE) {
                when (intent.detectedLanguage) {
                    "bn" -> "অনলাইন AI-এর সাথে সংযোগ করতে সমস্যা হয়েছে ($errDetail)।"
                    "hi" -> "ऑनलाइन AI से जुड़ने में समस्या हुई ($errDetail)।"
                    else -> "Online AI could not be reached ($errDetail). Check your connection or switch to Offline Mode."
                }
            } else {
                offlineFallback.second.directReply
            }
            "Online Error ($errDetail)" to ReasoningDecision(
                thoughtSummary = "Online AI failed: ${err.message}",
                directReply = combinedReply,
                plannedTools = emptyList()
            )
        }
    }

    fun buildLocalFastPath(input: String, intent: IntentAnalysis): ReasoningDecision? = localFastPath(input, intent)

    private fun localFastPath(input: String, intent: IntentAnalysis): ReasoningDecision? {
        val trimmed = input.trim()
        val lower = trimmed.lowercase()
        return when (intent.category) {
            IntentCategory.DEVICE_CONTROL -> {
                when {
                    lower.contains("flashlight") || lower.contains("torch") || lower.contains("ফ্ল্যাশলাইট") || lower.contains("টর্চ") || lower.contains("फ्लैशलाइट") -> {
                        val state = when {
                            lower.contains("off") || lower.contains("বন্ধ") || lower.contains("बंद") -> "off"
                            lower.contains("on") || lower.contains("চালু") || lower.contains("জ্বালাও") || lower.contains("चालू") -> "on"
                            else -> "toggle"
                        }
                        ReasoningDecision(
                            "Flashlight toggle",
                            if (state == "off") "Turning off the flashlight." else "Updating the flashlight.",
                            listOf(PlannedToolCall("toggle_flashlight", mapOf("state" to state), "Control camera torch"))
                        )
                    }
                    lower.contains("volume") || lower.contains("ভলিউম") || lower.contains("वॉल्यूम") || lower.contains("mute") || lower.contains("unmute") -> {
                        val volAction = when {
                            lower.contains("unmute") -> "unmute"
                            lower.contains("mute") || lower.contains("silent") -> "mute"
                            lower.contains("down") || lower.contains("lower") || lower.contains("decrease") || lower.contains("কমা") || lower.contains("कम") -> "down"
                            else -> "up"
                        }
                        val pctMatch = Regex("(\\d{1,3})\\s*%").find(lower)?.groupValues?.getOrNull(1)
                        val args = if (pctMatch != null) {
                            mapOf("volumeAction" to "set", "levelPercent" to pctMatch)
                        } else {
                            mapOf("volumeAction" to volAction)
                        }
                        ReasoningDecision(
                            "Adjust volume",
                            "Adjusting volume.",
                            listOf(PlannedToolCall("control_media_volume", args, "Adjust device media volume"))
                        )
                    }
                    lower.contains("pause music") || lower.contains("play music") || lower.contains("resume music") ||
                        lower.contains("next song") || lower.contains("previous song") -> {
                        val cmd = when {
                            lower.contains("pause") -> "pause"
                            lower.contains("next") -> "next"
                            lower.contains("previous") -> "previous"
                            else -> "play_pause"
                        }
                        ReasoningDecision(
                            "Media playback control",
                            "Controlling media playback.",
                            listOf(PlannedToolCall("control_media_volume", mapOf("mediaAction" to cmd), "Control playback"))
                        )
                    }
                    lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("ওয়াইফাই") -> ReasoningDecision(
                        "Open Wi-Fi settings",
                        "Opening Wi-Fi and internet settings.",
                        listOf(PlannedToolCall("open_device_settings", mapOf("target" to "wifi"), "Open Wi-Fi panel"))
                    )
                    lower.contains("bluetooth") || lower.contains("ব্লুটুথ") -> ReasoningDecision(
                        "Open Bluetooth settings",
                        "Opening Bluetooth settings.",
                        listOf(PlannedToolCall("open_device_settings", mapOf("target" to "bluetooth"), "Open Bluetooth settings"))
                    )
                    lower.contains("brightness") || lower.contains("display") -> ReasoningDecision(
                        "Open display settings",
                        "Opening display and brightness settings.",
                        listOf(PlannedToolCall("open_device_settings", mapOf("target" to "brightness"), "Open display settings"))
                    )
                    lower.contains("network") -> ReasoningDecision(
                        "Open network settings",
                        "Opening network settings.",
                        listOf(PlannedToolCall("open_device_settings", mapOf("target" to "network"), "Open network settings"))
                    )
                    lower.contains("sound") -> ReasoningDecision(
                        "Open sound settings",
                        "Opening sound settings.",
                        listOf(PlannedToolCall("open_device_settings", mapOf("target" to "sound"), "Open sound panel"))
                    )
                    lower.contains("open settings") || lower.contains("open the settings") || lower.contains("সেটিংস") || lower.contains("सेटिंग्स") -> ReasoningDecision(
                        "Open device settings",
                        "Opening Android settings.",
                        listOf(PlannedToolCall("open_device_settings", mapOf("target" to "main"), "Open main settings"))
                    )
                    lower.contains("lock") || lower.contains("লক") -> ReasoningDecision(
                        "Device lock requested",
                        "Checking Device Administrator permission to lock screen.",
                        listOf(PlannedToolCall("get_device_information", mapOf("action" to "lock_screen"), "Lock screen if Device Admin active"))
                    )
                    else -> ReasoningDecision(
                        "Device diagnostics requested",
                        "Checking device information.",
                        listOf(PlannedToolCall("get_device_information", mapOf("action" to "info"), "Read device info"))
                    )
                }
            }
            IntentCategory.YOUTUBE_SEARCH -> {
                val query = stripPrefixes(
                    trimmed,
                    listOf(
                        "search youtube for ", "search youtube ", "youtube search for ", "youtube search ",
                        "search on youtube for ", "search on youtube ", "ইউটিউবে সার্চ করো ", "ইউটিউব সার্চ ", "यूट्यूब पर खोजो "
                    )
                )
                if (query.isBlank()) null else ReasoningDecision(
                    "YouTube search",
                    "Searching YouTube for $query.",
                    listOf(PlannedToolCall("search_youtube", mapOf("query" to query), "Search YouTube"))
                )
            }
            IntentCategory.PHONE_CALL -> {
                val target = stripPrefixes(
                    trimmed,
                    listOf("call ", "dial ", "phone ", "কল করো ", "কল কর ", "ফোন করো ", "ফোন কর ", "कॉल करो ", "फ़ोन करो ")
                )
                if (target.isBlank()) null else ReasoningDecision(
                    "Phone call request",
                    "Preparing call to $target.",
                    listOf(PlannedToolCall("make_phone_call", mapOf("target" to target, "directCall" to "true"), "Dial or call requested contact"))
                )
            }
            IntentCategory.REMINDER -> {
                val minsMatch = Regex("(\\d+)\\s*(?:min|minute|minutes|মিনিট|मिनट)", RegexOption.IGNORE_CASE).find(trimmed)
                val mins = minsMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 525600) ?: 10
                val title = trimmed
                    .replace(Regex("^(please\\s+)?(remind me to|remind me|set a reminder to|set reminder to|রিমাইন্ডার দাও|মনে করিয়ে দিও|रिमाइंडर सेट करो)\\s*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s*in\\s+\\d+\\s*(?:min|minute|minutes)\\s*", RegexOption.IGNORE_CASE), " ")
                    .trim()
                    .ifBlank { "Reminder from ULTRA AI" }
                ReasoningDecision(
                    "Schedule WorkManager reminder",
                    "Setting a reminder for '$title' in $mins minute(s).",
                    listOf(PlannedToolCall("create_reminder", mapOf("title" to title, "delayMinutes" to mins.toString()), "Schedule local reminder"))
                )
            }
            IntentCategory.APP_OPEN -> {
                val query = stripPrefixes(
                    trimmed,
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
                    trimmed,
                    listOf("search the internet for ", "search web for ", "search for ", "search ", "google ", "সার্চ করে ", "সার্চ করো ", "সার্চ কর ", "সার্চ ", "খুঁজে দেখ ", "খোঁজ কর ", "सर्च करो ", "सर्च ", "खोजो ")
                )
                if (query.isBlank()) null else ReasoningDecision(
                    "Fast web search",
                    "Searching the web for $query.",
                    listOf(PlannedToolCall("search_web", mapOf("query" to query, "platform" to "google"), "Run web search"))
                )
            }
            IntentCategory.NOTIFICATION_READ -> ReasoningDecision(
                "Read recent notifications",
                "Checking your recent notifications.",
                listOf(PlannedToolCall("get_notifications", mapOf("limit" to "6"), "Fetch captured notifications"))
            )
            IntentCategory.SCREEN_INSPECT -> ReasoningDecision(
                "Inspect active screen",
                "Inspecting the current screen.",
                listOf(PlannedToolCall("get_accessible_screen", emptyMap(), "Read Accessibility node tree"))
            )
            IntentCategory.SCREEN_ACTION -> {
                when {
                    lower.contains("home") -> ReasoningDecision(
                        "Navigate home",
                        "Going to the home screen.",
                        listOf(PlannedToolCall("navigate_system", mapOf("action" to "home"), "Global home action"))
                    )
                    lower.contains("recent") -> ReasoningDecision(
                        "Open recents",
                        "Showing recent apps.",
                        listOf(PlannedToolCall("navigate_system", mapOf("action" to "recents"), "Global recents action"))
                    )
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
                    lower.startsWith("type ") || lower.startsWith("টাইপ ") -> {
                        val text = trimmed.substringAfter(" ").trim()
                        if (text.isBlank()) null else ReasoningDecision(
                            "Type text",
                            "Typing your text.",
                            listOf(PlannedToolCall("type_text", mapOf("text" to text), "Type into active field"))
                        )
                    }
                    lower.startsWith("click ") || lower.startsWith("tap ") -> {
                        val target = trimmed.substringAfter(" ").trim()
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
