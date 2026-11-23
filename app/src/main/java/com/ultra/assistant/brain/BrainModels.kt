package com.ultra.assistant.brain

import com.ultra.assistant.tools.PermissionRequirement
import com.ultra.assistant.tools.ToolExecutionResult

sealed interface UiState<out T> {
    data object Idle : UiState<Nothing>
    data object Loading : UiState<Nothing>
    data class Streaming(val text: String) : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class IntentCategory {
    APP_OPEN,
    WEB_SEARCH,
    YOUTUBE_SEARCH,
    MUSIC_SEARCH,
    SCREEN_INSPECT,
    SCREEN_ACTION,
    NOTIFICATION_READ,
    SMS_PREPARE,
    PHONE_CALL,
    REMINDER,
    DEVICE_CONTROL,
    MEMORY_SAVE,
    CONVERSATION
}

data class IntentAnalysis(
    val category: IntentCategory,
    val confidence: Float,
    val detectedLanguage: String
)

data class PlannedToolCall(
    val toolId: String,
    val args: Map<String, String>,
    val rationale: String
)

data class ReasoningDecision(
    val thoughtSummary: String,
    val directReply: String,
    val plannedTools: List<PlannedToolCall>,
    val memoryCandidateTitle: String = "",
    val memoryCandidateContent: String = "",
    val nextActionNeeded: Boolean = false
)

data class AgentStepTrace(
    val stepIndex: Int,
    val stage: String, // INTENT, PLAN, TOOL, VERIFY
    val detail: String,
    val success: Boolean = true
)

data class AgentExecutionReport(
    val userInput: String,
    val providerUsed: String,
    val finalReply: String,
    val steps: List<AgentStepTrace>,
    val toolResults: List<ToolExecutionResult>,
    val missingPermission: PermissionRequirement? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
    val thoughtSummary: String = ""
)

data class PendingToolConfirmation(
    val toolCall: PlannedToolCall,
    val promptMessage: String,
    val originalUserRequest: String
)
