package com.ultra.assistant.tools

import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.memory.MemoryManager
import kotlinx.coroutines.flow.first

class TaskExecutor(
    private val registry: ToolRegistry,
    private val memoryManager: MemoryManager,
    private val settingsRepository: LocalSettingsRepository
) {
    suspend fun executeTool(
        toolId: String,
        args: Map<String, String>,
        userConfirmed: Boolean = false
    ): ToolExecutionResult {
        val tool = registry.getTool(toolId)
            ?: return ToolExecutionResult(
                toolId = toolId,
                success = false,
                summary = "Blocked unknown tool '$toolId'. Only registered typed tools are permitted."
            ).also {
                memoryManager.recordAuditLog(toolId, "HIGH", "DENIED", it.summary, verified = false)
            }

        val validationError = tool.validateArgs(args)
        if (validationError != null) {
            memoryManager.recordAuditLog(tool.id, tool.riskLevel.name, "FAILED", validationError, verified = false)
            return ToolExecutionResult(tool.id, false, validationError)
        }

        val settings = settingsRepository.settingsFlow.first()
        val isDestructiveEmailAction = tool.id == "manage_email" &&
            args["action"]?.lowercase()?.let { it.contains("delete") || it.contains("trash") || it.contains("remove") } == true

        val needsGate = when {
            userConfirmed -> false
            isDestructiveEmailAction -> true
            tool.id == "prepare_sms" -> settings.requireSmsConfirmation
            tool.id == "make_phone_call" -> settings.requireCallConfirmation
            tool.riskLevel == RiskLevel.HIGH -> true
            else -> false
        }
        if (needsGate) {
            val summary = if (isDestructiveEmailAction) {
                "Confirmation required before deleting an email."
            } else {
                "Confirmation required before executing ${tool.title}."
            }
            memoryManager.recordAuditLog(tool.id, "HIGH", "AWAITING_CONFIRMATION", summary, verified = false)
            return ToolExecutionResult(
                toolId = tool.id,
                success = false,
                summary = summary,
                requiresConfirmation = true
            )
        }

        val result = tool.execute(args)
        val status = when {
            result.success -> "SUCCESS"
            result.missingPermission != null -> "DENIED"
            else -> "FAILED"
        }
        memoryManager.recordAuditLog(
            toolId = tool.id,
            riskLevel = tool.riskLevel.name,
            status = status,
            summary = result.summary,
            verified = result.success
        )
        return result
    }
}
