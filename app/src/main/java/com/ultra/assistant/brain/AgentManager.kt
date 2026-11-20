package com.ultra.assistant.brain

import android.content.Context
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.memory.MemoryManager
import com.ultra.assistant.tools.TaskExecutor
import com.ultra.assistant.tools.ToolExecutionResult
import com.ultra.assistant.tools.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AgentManager(
    context: Context,
    val memoryManager: MemoryManager = MemoryManager(context),
    val settingsRepository: LocalSettingsRepository = LocalSettingsRepository(context)
) {
    private val appContext = context.applicationContext
    val toolRegistry = ToolRegistry(appContext, memoryManager, settingsRepository)
    private val taskExecutor = TaskExecutor(toolRegistry, memoryManager, settingsRepository)
    val modelRouter = ModelRouter(settingsRepository)
    private val intentEngine = IntentEngine()
    val contextManager = ContextManager(memoryManager)
    private val reasoningEngine = ReasoningEngine(appContext, modelRouter, settingsRepository)
    private val taskPlanner = TaskPlanner(toolRegistry)
    private val resultVerifier = ResultVerifier()

    private val _latestReport = MutableStateFlow<AgentExecutionReport?>(null)
    val latestReportFlow: StateFlow<AgentExecutionReport?> = _latestReport.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingToolConfirmation?>(null)
    val pendingConfirmationFlow: StateFlow<PendingToolConfirmation?> = _pendingConfirmation.asStateFlow()

    suspend fun handleUserCommand(rawInput: String): AgentExecutionReport {
        val input = rawInput.trim()
        val traces = mutableListOf<AgentStepTrace>()

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
                val res = taskExecutor.executeTool("search_web", mapOf("query" to "$lang songs", "platform" to "youtube_music"))
                traces += AgentStepTrace(1, "TOOL", res.summary, res.success)
                val report = AgentExecutionReport(input, "Context Follow-Up", res.summary, traces, listOf(res))
                _latestReport.value = report
                return report
            }
        }

        // Step 1: Intent Classification
        val intent = intentEngine.classify(input)
        traces += AgentStepTrace(1, "INTENT", "Classified as ${intent.category.name} (lang=${intent.detectedLanguage})")

        // Step 2: Check music language ambiguity follow-up
        if (intent.category == IntentCategory.MUSIC_SEARCH) {
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
                val report = AgentExecutionReport(input, "Context Follow-Up", prompt, traces, emptyList())
                _latestReport.value = report
                return report
            } else {
                val res = taskExecutor.executeTool("search_web", mapOf("query" to explicitLang, "platform" to "youtube_music"))
                traces += AgentStepTrace(2, "TOOL", res.summary, res.success)
                val report = AgentExecutionReport(input, "Local Music Router", res.summary, traces, listOf(res))
                _latestReport.value = report
                return report
            }
        }

        // Step 3: Reasoning & Planning
        val history = contextManager.getRecentHistoryPairs(8)
        val memories = contextManager.buildMemorySummary()
        val (providerUsed, decision) = reasoningEngine.decide(
            userInput = input,
            intent = intent,
            history = history,
            memorySummary = memories,
            toolCatalogJson = toolRegistry.schemaSummaryForPrompt()
        )
        traces += AgentStepTrace(2, "PLAN", decision.thoughtSummary.ifBlank { "Planned ${decision.plannedTools.size} tool(s) via $providerUsed" })

        val plan = taskPlanner.buildPlan(decision)
        val results = mutableListOf<ToolExecutionResult>()
        for ((index, call) in plan.withIndex()) {
            val res = taskExecutor.executeTool(call.toolId, call.args, userConfirmed = false)
            results += res
            traces += AgentStepTrace(3 + index, "TOOL", "${call.toolId}: ${res.summary}", res.success)
            if (res.requiresConfirmation) {
                _pendingConfirmation.value = PendingToolConfirmation(
                    toolCall = call,
                    promptMessage = "Confirm ${call.toolId}: ${call.args.entries.joinToString { "${it.key}=${it.value}" }}",
                    originalUserRequest = input
                )
                break
            }
        }

        // Step 4: Verify execution & persist non-sensitive memory/context
        val verification = resultVerifier.verify(decision.directReply, results)
        traces += AgentStepTrace(traces.size + 1, "VERIFY", if (verification.allSucceeded) "Verified all steps" else "Detected partial/permission issue", verification.allSucceeded)

        contextManager.recordExchange(input, verification.userReply, intent.detectedLanguage)
        if (decision.memoryCandidateTitle.isNotBlank() && decision.memoryCandidateContent.isNotBlank()) {
            memoryManager.saveMemory(
                category = "PREFERENCE",
                title = decision.memoryCandidateTitle,
                content = decision.memoryCandidateContent,
                source = "ai_reasoning"
            )
        }

        val report = AgentExecutionReport(
            userInput = input,
            providerUsed = providerUsed,
            finalReply = verification.userReply,
            steps = traces,
            toolResults = results,
            missingPermission = verification.missingPermission
        )
        _latestReport.value = report
        return report
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
