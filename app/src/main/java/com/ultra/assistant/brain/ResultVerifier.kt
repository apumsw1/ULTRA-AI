package com.ultra.assistant.brain

import com.ultra.assistant.tools.PermissionRequirement
import com.ultra.assistant.tools.ToolExecutionResult

data class VerificationSummary(
    val allSucceeded: Boolean,
    val userReply: String,
    val missingPermission: PermissionRequirement?
)

class ResultVerifier {
    fun verify(
        decisionReply: String,
        results: List<ToolExecutionResult>
    ): VerificationSummary {
        if (results.isEmpty()) {
            return VerificationSummary(allSucceeded = true, userReply = decisionReply, missingPermission = null)
        }
        val missingPerm = results.firstNotNullOfOrNull { it.missingPermission }
        val allOk = results.all { it.success }
        val confirmationNeeded = results.any { it.requiresConfirmation }

        val combinedDetails = results.joinToString("\n") { res ->
            val extra = if (res.outputData.isNotBlank() && res.toolId in setOf("get_notifications", "get_accessible_screen", "get_device_information")) {
                "\n${res.outputData}"
            } else {
                ""
            }
            "${res.summary}$extra"
        }

        val verbatimSummaryTools = setOf(
            "get_notifications",
            "get_accessible_screen",
            "get_device_information",
            "read_screen_aloud",
            "manage_email"
        )

        val reply = when {
            confirmationNeeded -> results.first { it.requiresConfirmation }.summary
            missingPerm != null -> "${results.first { it.missingPermission != null }.summary} Open Settings to grant ${missingPerm.displayName}."
            allOk && decisionReply.isNotBlank() && results.none { it.toolId in verbatimSummaryTools } ->
                if (results.size == 1 && results.first().summary.equals(decisionReply, ignoreCase = true)) {
                    decisionReply
                } else {
                    "$decisionReply\n$combinedDetails"
                }
            else -> combinedDetails
        }

        return VerificationSummary(
            allSucceeded = allOk,
            userReply = reply.trim(),
            missingPermission = missingPerm
        )
    }
}
