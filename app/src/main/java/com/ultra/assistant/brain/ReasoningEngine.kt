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
    private val offlineEngine = OfflineAssistantEngine(context.applicationContext, memoryManager, settingsRepository)

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
        val isCompound = IntentEngine.isCompoundAction(userInput)

        // Skip single-step fast path for compound multi-step in-app goals so AI / Autonomous loop handles them cleanly
        if (!isCompound) {
            val localDecision = localFastPath(userInput, intent)
            if (localDecision != null) {
                return "Local Agent" to localDecision
            }
        }

        // Offline Mode routing (including deterministic offline compound helper!)
        if (isEffectiveOffline()) {
            val offlineCompound = if (isCompound) buildOfflineCompoundDecision(userInput) else null
            return offlineEngine.respond(userInput, intent, offlineCompound)
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
            // If online planner fails, fall back cleanly to local compound or offline engine without double waiting
            val localFallback = localFastPath(userInput, intent) ?: if (isCompound) buildOfflineCompoundDecision(userInput) else null
            if (localFallback != null) {
                return "Offline Fallback • Local Agent" to localFallback
            }
            val offlineFallback = offlineEngine.respond(userInput, intent, null)
            val errDetail = err.localizedMessage ?: "connection error"
            if (settings.operatingMode == AiOperatingMode.ONLINE) {
                val errReply = when (intent.detectedLanguage) {
                    "bn" -> "অনলাইন AI-এর সাথে সংযোগ করা যায়নি ($errDetail)। ইন্টারনেট বা Settings > AI Gateway পরীক্ষা করুন।"
                    "hi" -> "ऑनलाइन AI से जुड़ने में समस्या हुई ($errDetail)।"
                    else -> "Online AI could not be reached ($errDetail). Check internet or Settings > AI Gateway."
                }
                "Online Error • $errDetail" to ReasoningDecision(
                    thoughtSummary = "Online AI failed: ${err.message}",
                    directReply = errReply,
                    plannedTools = emptyList()
                )
            } else {
                "Offline Fallback • Local Assistant" to ReasoningDecision(
                    thoughtSummary = "Auto fallback to Offline Engine after online error: $errDetail",
                    directReply = offlineFallback.second.directReply,
                    plannedTools = offlineFallback.second.plannedTools
                )
            }
        }
    }

    suspend fun planFollowUpStep(
        goal: String,
        completedSteps: List<String>,
        screenContext: String,
        toolCatalogJson: String
    ): Result<Pair<String, ReasoningDecision>> {
        val settings = settingsRepository.settingsFlow.first()
        if (isEffectiveOffline()) {
            return Result.success("Offline • In-App Step" to buildOfflineFollowUp(goal, completedSteps, screenContext))
        }
        val router = aiEngine as? ModelRouter
        return if (router != null) {
            router.planNextInAppStep(
                goal = goal,
                completedSteps = completedSteps,
                screenContext = screenContext,
                toolCatalogJson = toolCatalogJson,
                languageLabel = settings.language
            )
        } else {
            aiEngine.reasonAndPlan(
                userMessage = "Continue goal: $goal\nCompleted: ${completedSteps.joinToString("; ")}",
                history = emptyList(),
                memoryContext = "",
                toolCatalogJson = toolCatalogJson,
                screenContext = screenContext,
                languageLabel = settings.language
            )
        }
    }

    private fun buildOfflineCompoundDecision(input: String): ReasoningDecision? {
        val lower = input.lowercase()
        // Handle "open youtube and search/play X" directly if clear
        val ytMatch = Regex("(?:youtube|ইউটিউব).*(?:search|play|for|সার্চ|দেখাও)\\s+(.+)", RegexOption.IGNORE_CASE).find(input)
        if (ytMatch != null) {
            val q = ytMatch.groupValues[1].trim()
            if (q.isNotBlank()) {
                return ReasoningDecision(
                    thoughtSummary = "Parsed compound YouTube query '$q'",
                    directReply = "Opening YouTube and searching for $q.",
                    plannedTools = listOf(PlannedToolCall("search_youtube", mapOf("query" to q), "Launch YouTube search")),
                    nextActionNeeded = false
                )
            }
        }
        // Extract app name before "and" / "then" / "করে" / "তারপর"
        val appClause = input
            .split(Regex("\\s+(?:and|then|after that|এবং|তারপর|করে|और|फिर)\\s+", RegexOption.IGNORE_CASE), limit = 2)
            .firstOrNull()
            .orEmpty()
        val appName = stripPrefixes(
            appClause,
            listOf("open the app ", "launch the app ", "open ", "launch ", "start ", "অ্যাপ খোলো ", "খুলে দাও ", "খোলো ", "চালু কর ", "ऐप खोलो ", "खोलो ")
        )
        if (appName.isBlank()) return null
        return ReasoningDecision(
            thoughtSummary = "Step 1 of compound goal: launch '$appName' then inspect live screen UI for next steps",
            directReply = "Opening $appName and preparing next action.",
            plannedTools = listOf(PlannedToolCall("open_app", mapOf("appName" to appName), "Open target app before in-app control")),
            nextActionNeeded = lower.contains("search") || lower.contains("type") || lower.contains("click") || lower.contains("tap") || lower.contains("scroll")
        )
    }

    private fun buildOfflineFollowUp(
        goal: String,
        completedSteps: List<String>,
        screenContext: String
    ): ReasoningDecision {
        val lower = goal.lowercase()
        val typedAlready = completedSteps.any { it.startsWith("type_text") }
        val clickedSearchAlready = completedSteps.any { it.contains("Search", ignoreCase = true) }

        val searchTerm = Regex("(?:search(?: for)?|type|write|find|সার্চ|টাইপ)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(goal)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        if (searchTerm.isNotBlank() && !typedAlready) {
            if (screenContext.contains("(editable)", ignoreCase = true) ||
                screenContext.contains("editable", ignoreCase = true)
            ) {
                return ReasoningDecision(
                    thoughtSummary = "Found editable text box on active screen; typing '$searchTerm' with submit",
                    directReply = "Typed '$searchTerm' inside the app.",
                    plannedTools = listOf(
                        PlannedToolCall("type_text", mapOf("text" to searchTerm, "submit" to "true"), "Enter query and submit")
                    ),
                    nextActionNeeded = false
                )
            }
            if (!clickedSearchAlready && screenContext.contains("search", ignoreCase = true)) {
                return ReasoningDecision(
                    thoughtSummary = "Tapping Search control first to reveal input field",
                    directReply = "Opening search box.",
                    plannedTools = listOf(
                        PlannedToolCall("click_ui_element", mapOf("targetText" to "Search"), "Reveal search input")
                    ),
                    nextActionNeeded = true
                )
            }
        }
        return ReasoningDecision(
            thoughtSummary = "Completed offline in-app steps ($screenContext)",
            directReply = "Done. Completed the requested in-app action.",
            plannedTools = emptyList(),
            nextActionNeeded = false
        )
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
                        lower.contains("next song") || lower.contains("previous song") ||
                        lower.contains("পরের গান") || lower.contains("আগের গান") ||
                        lower.contains("গান থামাও") || lower.contains("গান চালাও") ||
                        lower.contains("अगला गाना") || lower.contains("पिछला गाना") -> {
                        val cmd = when {
                            lower.contains("pause") || lower.contains("থামা") -> "pause"
                            lower.contains("next") || lower.contains("পরের") || lower.contains("अगला") -> "next"
                            lower.contains("previous") || lower.contains("আগের") || lower.contains("पिछला") -> "previous"
                            else -> "play_pause"
                        }
                        ReasoningDecision(
                            "Media playback control",
                            "Controlling media playback.",
                            listOf(PlannedToolCall("control_media_volume", mapOf("mediaAction" to cmd), "Control playback"))
                        )
                    }
                    lower.contains("quick settings") || lower.contains("কুইক সেটিংস") -> ReasoningDecision(
                        "Open Quick Settings shade",
                        "Opening Quick Settings.",
                        listOf(PlannedToolCall("navigate_system", mapOf("action" to "quick_settings"), "Expand quick settings"))
                    )
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
                        "Checking Device Administrator / Accessibility to lock screen.",
                        listOf(PlannedToolCall("get_device_information", mapOf("action" to "lock_screen"), "Lock screen if permitted"))
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
            IntentCategory.SCREEN_INSPECT -> {
                if (lower.contains("aloud") || lower.contains("read screen") ||
                    lower.contains("পড়ে") || lower.contains("পড়ে") || lower.contains("शোনাও") || lower.contains("पढ़ो")
                ) {
                    ReasoningDecision(
                        "Read active screen aloud",
                        "Reading the current screen.",
                        listOf(PlannedToolCall("read_screen_aloud", emptyMap(), "Read active screen text aloud"))
                    )
                } else {
                    ReasoningDecision(
                        "Inspect active screen",
                        "Inspecting the current screen.",
                        listOf(PlannedToolCall("get_accessible_screen", emptyMap(), "Read Accessibility node tree"))
                    )
                }
            }
            IntentCategory.EMAIL_ACTION -> {
                when {
                    lower.contains("delete") || lower.contains("remove") || lower.contains("trash") ||
                        lower.contains("মুছ") || lower.contains("ডিলিট") || lower.contains("हटा") -> {
                        if (lower.contains("all") || lower.contains("bulk") || lower.contains("সব")) {
                            ReasoningDecision(
                                "Blocked ambiguous bulk email deletion",
                                "For safety, bulk email deletion isn't automatic. Please open the specific email you want to delete and ask again so I can request your confirmation.",
                                emptyList()
                            )
                        } else {
                            ReasoningDecision(
                                "Email deletion request (requires confirmation)",
                                "Confirming before deleting the currently open email.",
                                listOf(PlannedToolCall("manage_email", mapOf("action" to "delete_email"), "Delete active email with user confirmation"))
                            )
                        }
                    }
                    lower.contains("next") || lower.contains("পরের") || lower.contains("अगला") -> ReasoningDecision(
                        "Open next email",
                        "Moving to the next email.",
                        listOf(PlannedToolCall("manage_email", mapOf("action" to "next_email"), "Navigate to next email"))
                    )
                    lower.contains("read") || lower.contains("check") || lower.contains("পড়") || lower.contains("পড়") || lower.contains("पढ़") -> {
                        if (screenController.isCurrentlyInEmailApp()) {
                            ReasoningDecision(
                                "Read open email screen",
                                "Reading your visible email.",
                                listOf(PlannedToolCall("manage_email", mapOf("action" to "read_email"), "Read visible email content"))
                            )
                        } else {
                            ReasoningDecision(
                                "Open email app and read inbox",
                                "Opening your email app so I can read it.",
                                listOf(PlannedToolCall("manage_email", mapOf("action" to "open_email"), "Launch email app")),
                                nextActionNeeded = true
                            )
                        }
                    }
                    else -> ReasoningDecision(
                        "Open email app",
                        "Opening your email app.",
                        listOf(PlannedToolCall("manage_email", mapOf("action" to "open_email"), "Launch email app"))
                    )
                }
            }
            IntentCategory.SCREEN_ACTION -> {
                when {
                    lower.contains("home") || lower.contains("হোম") || lower.contains("होम") -> ReasoningDecision(
                        "Navigate home",
                        "Going to the home screen.",
                        listOf(PlannedToolCall("navigate_system", mapOf("action" to "home"), "Global home action"))
                    )
                    lower.contains("recent") -> ReasoningDecision(
                        "Open recents",
                        "Showing recent apps.",
                        listOf(PlannedToolCall("navigate_system", mapOf("action" to "recents"), "Global recents action"))
                    )
                    lower.contains("back") || lower.contains("ব্যাক") || lower.contains("बैक") -> ReasoningDecision(
                        "Global back navigation",
                        "Going back.",
                        listOf(PlannedToolCall("press_back", emptyMap(), "Perform Accessibility GLOBAL_ACTION_BACK"))
                    )
                    lower.contains("scroll left") || lower.contains("swipe left") || lower.contains("বামে") -> ReasoningDecision(
                        "Scroll left",
                        "Scrolling left.",
                        listOf(PlannedToolCall("scroll_screen", mapOf("direction" to "left"), "Scroll horizontally left"))
                    )
                    lower.contains("scroll right") || lower.contains("swipe right") || lower.contains("ডানে") -> ReasoningDecision(
                        "Scroll right",
                        "Scrolling right.",
                        listOf(PlannedToolCall("scroll_screen", mapOf("direction" to "right"), "Scroll horizontally right"))
                    )
                    lower.contains("scroll up") || lower.contains("উপরে স্ক্রল") || lower.contains("স্ক্রল উপরে") || lower.contains("ऊपर") -> ReasoningDecision(
                        "Scroll up",
                        "Scrolling up.",
                        listOf(PlannedToolCall("scroll_screen", mapOf("direction" to "up"), "Scroll backward"))
                    )
                    lower.contains("scroll") || lower.contains("স্ক্রল") || lower.contains("स्क्रॉल") -> ReasoningDecision(
                        "Scroll down",
                        "Scrolling down.",
                        listOf(PlannedToolCall("scroll_screen", mapOf("direction" to "down"), "Scroll forward"))
                    )
                    lower.trim() in setOf("next", "next one", "পরেরটা", "अगला") -> {
                        if (screenController.isCurrentlyInEmailApp()) {
                            ReasoningDecision(
                                "Context-aware Next -> Email",
                                "Moving to the next email.",
                                listOf(PlannedToolCall("manage_email", mapOf("action" to "next_email"), "Advance to next email"))
                            )
                        } else {
                            ReasoningDecision(
                                "Context-aware Next -> Screen scroll/next",
                                "Moving to the next item.",
                                listOf(PlannedToolCall("click_ui_element", mapOf("targetText" to "Next"), "Try Next button or scroll"))
                            )
                        }
                    }
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
