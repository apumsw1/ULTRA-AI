package com.ultra.assistant.brain

import android.content.Context
import com.ultra.assistant.control.ScreenController
import com.ultra.assistant.data.AiOperatingMode
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.data.NetworkMonitor
import com.ultra.assistant.memory.MemoryManager
import com.ultra.assistant.tools.PermissionRequirement
import com.ultra.assistant.tools.TaskExecutor
import com.ultra.assistant.tools.ToolExecutionResult
import com.ultra.assistant.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

class AgentManager(
    context: Context,
    val memoryManager: MemoryManager = MemoryManager(context),
    val settingsRepository: LocalSettingsRepository = LocalSettingsRepository(context),
    val networkMonitor: NetworkMonitor = NetworkMonitor(context)
) {
    private val appContext = context.applicationContext
    val toolRegistry = ToolRegistry(appContext, memoryManager, settingsRepository)
    private val taskExecutor = TaskExecutor(toolRegistry, memoryManager, settingsRepository)
    private val screenController = ScreenController(appContext)
    val modelRouter = ModelRouter(settingsRepository, networkMonitor)
    private val intentEngine = IntentEngine()
    val contextManager = ContextManager(memoryManager)
    private val reasoningEngine = ReasoningEngine(appContext, modelRouter, settingsRepository, networkMonitor, memoryManager)
    private val taskPlanner = TaskPlanner(toolRegistry)
    private val resultVerifier = ResultVerifier()

    private val _latestReport = MutableStateFlow<AgentExecutionReport?>(null)
    val latestReportFlow: StateFlow<AgentExecutionReport?> = _latestReport.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingToolConfirmation?>(null)
    val pendingConfirmationFlow: StateFlow<PendingToolConfirmation?> = _pendingConfirmation.asStateFlow()

    private val _streamingReply = MutableStateFlow("")
    val streamingReplyFlow: StateFlow<String> = _streamingReply.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreamingFlow: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _liveStatus = MutableStateFlow("")
    val liveStatusFlow: StateFlow<String> = _liveStatus.asStateFlow()

    @Volatile
    private var stopRequested: Boolean = false

    fun requestStop() {
        stopRequested = true
        _isStreaming.value = false
        _streamingReply.value = ""
        _pendingConfirmation.value = null
        _liveStatus.value = "Stopped"
    }

    fun clearTemporaryContext() {
        requestStop()
        contextManager.clearTemporaryContext()
        _latestReport.value = null
        modelRouter.clearTransientDiagnostic()
    }

    suspend fun handleUserCommand(rawInput: String): AgentExecutionReport {
        val input = IntentEngine.normalizeSpeechTranscript(rawInput.trim())
        val traces = mutableListOf<AgentStepTrace>()
        _streamingReply.value = ""
        _liveStatus.value = "Analyzing intent..."
        stopRequested = false

        try {
            // Immediate Stop command in English / Bengali / Hindi
            if (IntentEngine.isStopCommand(input)) {
                requestStop()
                val lang = IntentEngine.detectLanguage(input)
                val stopReply = when (lang) {
                    "bn" -> "ঠিক আছে, থেমে গেলাম।"
                    "hi" -> "ठीक है, मैंने रोक दिया है।"
                    else -> "Stopped as requested."
                }
                val report = AgentExecutionReport(
                    userInput = input,
                    providerUsed = "Local Stop Control",
                    finalReply = stopReply,
                    steps = listOf(AgentStepTrace(1, "VERIFY", "User triggered stop command")),
                    toolResults = emptyList()
                )
                _latestReport.value = report
                return report
            }

            // Persist ONLY user's given name if explicitly introduced
            IntentEngine.extractUserNameDeclaration(input)?.let { declaredName ->
                settingsRepository.updateUserDisplayName(declaredName)
            }
            // Handle active music follow-up slot if waiting
            if (contextManager.followUpSlot is FollowUpSlot.MusicLanguage) {
                contextManager.followUpSlot = FollowUpSlot.None
                val lang = when {
                    input.contains("বাংলা", true) || input.contains("bangla", true) || input.contains("bengali", true) -> "Bengali"
                    input.contains("হিন্দি", true) || input.contains("hindi", true) || input.contains("हिंदी", true) -> "Hindi"
                    input.contains("english", true) || input.contains("ইংরেজি", true) || input.contains("ইংলিশ", true) -> "English"
                    else -> ""
                }
                if (lang.isNotBlank()) {
                    _liveStatus.value = "Opening YouTube Music ($lang)..."
                    val res = taskExecutor.executeTool("search_web", mapOf("query" to "$lang songs", "platform" to "youtube_music"))
                    traces += AgentStepTrace(1, "TOOL", res.summary, res.success)
                    contextManager.recordExchange(input, res.summary, "en")
                    val report = AgentExecutionReport(
                        userInput = input,
                        providerUsed = "Local Music Router",
                        finalReply = res.summary,
                        steps = traces,
                        toolResults = listOf(res),
                        thoughtSummary = "Resolved music language follow-up slot to $lang."
                    )
                    _latestReport.value = report
                    return report
                }
            }

            val intent = intentEngine.classify(input)
            traces += AgentStepTrace(1, "INTENT", "Classified as ${intent.category.name} (lang=${intent.detectedLanguage})")

            if (intent.category == IntentCategory.MUSIC_SEARCH && !IntentEngine.isCompoundAction(input)) {
                val lower = input.lowercase()
                val explicitLang = when {
                    lower.contains("বাংলা") || lower.contains("bangla") || lower.contains("bengali") -> "Bengali songs"
                    lower.contains("হিন্দি") || lower.contains("hindi") || lower.contains("हिंदी") -> "Hindi songs"
                    lower.contains("english") || lower.contains("ইংলিশ") || lower.contains("ইংরেজি") -> "English songs"
                    else -> null
                }
                if (explicitLang == null) {
                    contextManager.followUpSlot = FollowUpSlot.MusicLanguage
                    val prompt = "What kind of songs would you like: Bengali, Hindi, or English?"
                    traces += AgentStepTrace(2, "PLAN", "Waiting for music language follow-up")
                    contextManager.recordExchange(input, prompt, intent.detectedLanguage)
                    val report = AgentExecutionReport(input, "Context Follow-Up", prompt, traces, emptyList())
                    _latestReport.value = report
                    return report
                } else {
                    val res = taskExecutor.executeTool("search_web", mapOf("query" to explicitLang, "platform" to "youtube_music"))
                    traces += AgentStepTrace(2, "TOOL", res.summary, res.success)
                    contextManager.recordExchange(input, res.summary, intent.detectedLanguage)
                    val report = AgentExecutionReport(input, "Local Music Router", res.summary, traces, listOf(res))
                    _latestReport.value = report
                    return report
                }
            }

            val history = contextManager.getRecentHistoryPairs(12)
            val memories = contextManager.buildMemorySummary()
            val settingsSnapshot = settingsRepository.settingsFlow.first()
            val effectiveOffline = reasoningEngine.isEffectiveOffline()

            // Stream analytical ChatGPT-style conversation directly when online and not an action command
            if (intent.category == IntentCategory.CONVERSATION &&
                !effectiveOffline &&
                !looksLikeActionRequest(input)
            ) {
                _liveStatus.value = "✦ Thinking..."
                val streamed = tryStreamConversation(
                    input = input,
                    history = history,
                    memories = memories,
                    userDisplayName = settingsSnapshot.userDisplayName,
                    lang = intent.detectedLanguage
                )
                if (streamed != null) {
                    traces += AgentStepTrace(2, "PLAN", streamed.thoughtSummary.ifBlank { "Streamed conversational reasoning" })
                    memoryManager.recordConversationTurn("user", input, intent.detectedLanguage)
                    memoryManager.recordConversationTurn(
                        role = "assistant",
                        content = streamed.reply,
                        languageTag = intent.detectedLanguage,
                        providerLabel = streamed.provider,
                        thoughtSummary = streamed.thoughtSummary
                    )
                    contextManager.lastTopic = input.take(80)
                    val report = AgentExecutionReport(
                        userInput = input,
                        providerUsed = streamed.provider,
                        finalReply = streamed.reply,
                        steps = traces,
                        toolResults = emptyList(),
                        thoughtSummary = streamed.thoughtSummary
                    )
                    _latestReport.value = report
                    return report
                }
            }

            // Keep temporary conversation context across Online <-> Offline fallback seamlessly
            _liveStatus.value = "✦ Reasoning & Planning..."
            val toolCatalogJson = toolRegistry.schemaSummaryForPrompt()
            val (providerUsed, initialDecision) = reasoningEngine.decide(
                userInput = input,
                intent = intent,
                history = history,
                memorySummary = memories,
                toolCatalogJson = toolCatalogJson
            )
            val accumulatedThoughts = mutableListOf<String>()
            if (initialDecision.thoughtSummary.isNotBlank()) {
                accumulatedThoughts += initialDecision.thoughtSummary
            }
            traces += AgentStepTrace(2, "PLAN", initialDecision.thoughtSummary.ifBlank { "Planned ${initialDecision.plannedTools.size} tool(s) via $providerUsed" })

            val results = mutableListOf<ToolExecutionResult>()
            val completedStepNotes = mutableListOf<String>()
            var latestReply = initialDecision.directReply
            var awaitingConfirmation = false
            var nextNeeded = initialDecision.nextActionNeeded ||
                (IntentEngine.isCompoundAction(input) && initialDecision.plannedTools.any {
                    it.toolId == "open_app" || it.toolId == "open_device_settings"
                })

            // Cycle 0: Execute initial plan
            val initialPlan = taskPlanner.buildPlan(initialDecision)
            for (call in initialPlan) {
                if (stopRequested) break
                _liveStatus.value = "⚡ ${call.toolId.replace('_', ' ')}..."
                val res = taskExecutor.executeTool(call.toolId, call.args, userConfirmed = false)
                results += res
                completedStepNotes += "${call.toolId}(${call.args.values.joinToString(", ")}) -> ${res.summary}"
                traces += AgentStepTrace(traces.size + 1, "TOOL", "${call.toolId}: ${res.summary}", res.success)
                if (res.requiresConfirmation) {
                    _pendingConfirmation.value = PendingToolConfirmation(
                        toolCall = call,
                        promptMessage = "Confirm ${call.toolId.replace('_', ' ')}: ${call.args.entries.joinToString { "${it.key}=${it.value}" }}",
                        originalUserRequest = input
                    )
                    awaitingConfirmation = true
                    break
                }
                if (res.success && call.toolId == "open_app") {
                    contextManager.lastActiveApp = call.args["appName"]?.take(60)
                }
                if (!res.success && call.toolId == "open_app") {
                    nextNeeded = false
                }
            }

            // Autonomous Observe -> Think -> Act loop for multi-step in-app control (up to 5 follow-up cycles)
            var extraMissingPerm: PermissionRequirement? = null
            if (nextNeeded && !awaitingConfirmation && !stopRequested && results.any { it.success }) {
                val initialDelay = if (initialPlan.any { it.toolId == "open_app" || it.toolId == "open_device_settings" }) 950L else 500L
                delay(initialDelay)

                for (cycle in 1..5) {
                    if (stopRequested) break
                    if (!screenController.isReady()) {
                        extraMissingPerm = PermissionRequirement.ACCESSIBILITY
                        traces += AgentStepTrace(
                            traces.size + 1,
                            "VERIFY",
                            "Accessibility Service needed to inspect and control inside the opened app.",
                            success = false
                        )
                        latestReply = "${results.lastOrNull()?.summary.orEmpty()} Enable ULTRA AI Accessibility Service so I can operate inside the app automatically.".trim()
                        break
                    }
                    _liveStatus.value = "👁 Observing screen (Step ${cycle + 1})..."
                    val screenDesc = screenController.describeScreenConcise().getOrElse { "Unavailable" }
                    _liveStatus.value = "✦ Planning Step ${cycle + 1}..."
                    val stepResult = reasoningEngine.planFollowUpStep(
                        goal = input,
                        completedSteps = completedStepNotes,
                        screenContext = screenDesc,
                        toolCatalogJson = toolCatalogJson
                    )
                    val (_, nextDecision) = stepResult.getOrElse { err ->
                        traces += AgentStepTrace(traces.size + 1, "VERIFY", "Stopped autonomous loop: ${err.message}", false)
                        break
                    }
                    if (nextDecision.thoughtSummary.isNotBlank()) {
                        accumulatedThoughts += "Step ${cycle + 1}: ${nextDecision.thoughtSummary}"
                    }
                    if (nextDecision.plannedTools.isEmpty()) {
                        if (nextDecision.directReply.isNotBlank()) {
                            latestReply = nextDecision.directReply
                        }
                        break
                    }
                    val followPlan = taskPlanner.buildPlan(nextDecision)
                    var anyUiAction = false
                    for (call in followPlan) {
                        if (stopRequested) break
                        _liveStatus.value = "⚡ Step ${cycle + 1}: ${call.toolId.replace('_', ' ')}..."
                        val res = taskExecutor.executeTool(call.toolId, call.args, userConfirmed = false)
                        results += res
                        completedStepNotes += "${call.toolId}(${call.args.values.joinToString(", ")}) -> ${res.summary}"
                        traces += AgentStepTrace(traces.size + 1, "TOOL", "${call.toolId}: ${res.summary}", res.success)
                        if (call.toolId in setOf("click_ui_element", "type_text", "scroll_screen", "press_back", "open_app")) {
                            anyUiAction = true
                        }
                        if (res.requiresConfirmation) {
                            _pendingConfirmation.value = PendingToolConfirmation(
                                toolCall = call,
                                promptMessage = "Confirm ${call.toolId.replace('_', ' ')}: ${call.args.entries.joinToString { "${it.key}=${it.value}" }}",
                                originalUserRequest = input
                            )
                            awaitingConfirmation = true
                            break
                        }
                    }
                    if (nextDecision.directReply.isNotBlank()) {
                        latestReply = nextDecision.directReply
                    }
                    if (awaitingConfirmation || !nextDecision.nextActionNeeded || !anyUiAction) {
                        break
                    }
                    delay(550L)
                }
            }

            if (stopRequested) {
                traces += AgentStepTrace(traces.size + 1, "VERIFY", "Execution interrupted by user.", false)
                latestReply = "Stopped as requested."
            }

            val verification = resultVerifier.verify(latestReply, results)
            val missingPerm = extraMissingPerm ?: verification.missingPermission
            val finalReply = if (extraMissingPerm != null && !verification.userReply.contains("Accessibility", ignoreCase = true)) {
                latestReply
            } else {
                verification.userReply
            }
            traces += AgentStepTrace(
                traces.size + 1,
                "VERIFY",
                if (verification.allSucceeded && extraMissingPerm == null) "Verified all steps" else "Completed with follow-up note",
                verification.allSucceeded && extraMissingPerm == null
            )

            val combinedThought = accumulatedThoughts.distinct().joinToString("\n")
            memoryManager.recordConversationTurn("user", input, intent.detectedLanguage)
            memoryManager.recordConversationTurn(
                role = "assistant",
                content = finalReply,
                languageTag = intent.detectedLanguage,
                providerLabel = providerUsed,
                thoughtSummary = combinedThought
            )
            contextManager.lastTopic = input.take(80)

            // Do not store personal memories permanently; only userDisplayName is persisted via explicit introduction.

            val report = AgentExecutionReport(
                userInput = input,
                providerUsed = providerUsed,
                finalReply = finalReply,
                steps = traces,
                toolResults = results,
                missingPermission = missingPerm,
                thoughtSummary = combinedThought
            )
            _latestReport.value = report
            return report
        } finally {
            _liveStatus.value = ""
        }
    }

    private data class StreamResult(
        val provider: String,
        val reply: String,
        val thoughtSummary: String
    )

    private suspend fun tryStreamConversation(
        input: String,
        history: List<Pair<String, String>>,
        memories: String,
        userDisplayName: String,
        lang: String
    ): StreamResult? {
        val langLabel = when (lang) {
            "bn" -> "বাংলা"
            "hi" -> "हिन्दी"
            else -> "English"
        }
        val contextualPrompt = buildString {
            if (userDisplayName.isNotBlank()) {
                append("User's given name: $userDisplayName\n")
            }
            if (memories != "None") {
                append("Temporary session context:\n")
                append(memories)
                append("\n\n")
            }
            append(input)
        }
        return try {
            _isStreaming.value = true
            var rawBuffer = ""
            modelRouter.streamConversation(contextualPrompt, history, langLabel).collect { partial ->
                rawBuffer = partial
                val parsed = extractThoughtAndReply(partial)
                if (parsed.second.isNotBlank()) {
                    _streamingReply.value = parsed.second
                } else if (parsed.first.isNotBlank()) {
                    _liveStatus.value = "✦ Thinking: ${parsed.first.take(72)}..."
                }
            }
            _isStreaming.value = false
            _streamingReply.value = ""
            val (thought, visibleReply) = extractThoughtAndReply(rawBuffer)
            if (visibleReply.isBlank()) null else StreamResult("Online • ChatGPT Brain", visibleReply, thought)
        } catch (e: CancellationException) {
            _isStreaming.value = false
            _streamingReply.value = ""
            throw e
        } catch (_: Exception) {
            _isStreaming.value = false
            _streamingReply.value = ""
            null
        }
    }

    private fun extractThoughtAndReply(raw: String): Pair<String, String> {
        val startTag = "<thought>"
        val endTag = "</thought>"
        val startIdx = raw.indexOf(startTag, ignoreCase = true)
        if (startIdx == -1) {
            return "" to raw.trim()
        }
        val endIdx = raw.indexOf(endTag, startIdx + startTag.length, ignoreCase = true)
        return if (endIdx == -1) {
            val thinkingInProgress = raw.substring(startIdx + startTag.length).trim()
            thinkingInProgress to ""
        } else {
            val thought = raw.substring(startIdx + startTag.length, endIdx).trim()
            val reply = raw.substring(endIdx + endTag.length).trim()
            thought to reply
        }
    }

    private fun looksLikeActionRequest(input: String): Boolean {
        val lower = input.lowercase()
        return listOf(
            "open ", "launch ", "youtube", "volume", "mute", "flashlight", "torch",
            "bluetooth", "wifi", "wi-fi", "settings", "call ", "dial ", "sms", "message",
            "remind", "notification", "screen", "click", "tap", "scroll", "type ", "lock",
            "search", "battery", "quick settings", "খোলো", "খুলে", "সার্চ", "কল", "खोलो", "सर्च", "कॉल"
        ).any { lower.contains(it) }
    }

    suspend fun resolvePendingConfirmation(approved: Boolean): AgentExecutionReport? {
        val pending = _pendingConfirmation.value ?: return null
        _pendingConfirmation.value = null
        if (!approved) {
            memoryManager.recordAuditLog(
                toolId = pending.toolCall.toolId,
                riskLevel = "HIGH",
                status = "DENIED",
                summary = "User declined confirmation for ${pending.toolCall.toolId}.",
                verified = true
            )
            memoryManager.recordConversationTurn("assistant", "Action cancelled as requested.", "en", "Security Gate")
            val report = AgentExecutionReport(
                userInput = pending.originalUserRequest,
                providerUsed = "Security Gate",
                finalReply = "Action cancelled as requested.",
                steps = listOf(AgentStepTrace(1, "VERIFY", "User cancelled sensitive tool execution")),
                toolResults = emptyList()
            )
            _latestReport.value = report
            return report
        }
        val res = taskExecutor.executeTool(pending.toolCall.toolId, pending.toolCall.args, userConfirmed = true)
        memoryManager.recordConversationTurn("assistant", res.summary, "en", "Confirmed Execution")
        val report = AgentExecutionReport(
            userInput = pending.originalUserRequest,
            providerUsed = "Confirmed Execution",
            finalReply = res.summary,
            steps = listOf(AgentStepTrace(1, "TOOL", res.summary, res.success)),
            toolResults = listOf(res),
            missingPermission = res.missingPermission
        )
        _latestReport.value = report
        return report
    }
}
