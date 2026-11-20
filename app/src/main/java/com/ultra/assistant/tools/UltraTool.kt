package com.ultra.assistant.tools

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

enum class PermissionRequirement(val displayName: String) {
    ACCESSIBILITY("Accessibility Service"),
    NOTIFICATION_LISTENER("Notification Access"),
    POST_NOTIFICATIONS("Post Notifications"),
    MICROPHONE("Microphone"),
    OVERLAY("Display Over Other Apps"),
    DEVICE_ADMIN("Device Administrator"),
    DEFAULT_SMS_APP("Default SMS Handler (or Messaging App)")
}

data class ToolParamSpec(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

data class ToolExecutionResult(
    val toolId: String,
    val success: Boolean,
    val summary: String,
    val outputData: String = "",
    val missingPermission: PermissionRequirement? = null,
    val requiresConfirmation: Boolean = false
)

interface UltraTool {
    val id: String
    val title: String
    val description: String
    val parameters: List<ToolParamSpec>
    val requiredPermissions: List<PermissionRequirement>
    val riskLevel: RiskLevel

    fun validateArgs(args: Map<String, String>): String? {
        for (param in parameters) {
            if (param.required && args[param.name].isNullOrBlank()) {
                return "Missing required parameter '${param.name}' for tool '$id'."
            }
            if (param.type == "integer") {
                val raw = args[param.name]
                if (!raw.isNullOrBlank() && raw.toLongOrNull() == null) {
                    return "Parameter '${param.name}' must be an integer."
                }
            }
        }
        return null
    }

    suspend fun execute(args: Map<String, String>): ToolExecutionResult
}
