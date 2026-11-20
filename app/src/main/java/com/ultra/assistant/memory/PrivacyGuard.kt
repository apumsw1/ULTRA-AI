package com.ultra.assistant.memory

object PrivacyGuard {
    private val sensitivePatterns = listOf(
        Regex("\\b\\d{4,8}\\b(?=.*(otp|code|pin|verification|যাচাই|ওটিপি|कोड))", RegexOption.IGNORE_CASE),
        Regex("(otp|verification code|one[- ]time password|passcode|cvv|pin)\\s*(?:is|are|:|-)?\\s*\\d{3,8}", RegexOption.IGNORE_CASE),
        Regex("(password|secret|api[_\\s-]?key|bearer|token)\\s*[:=]\\s*\\S+", RegexOption.IGNORE_CASE),
        Regex("\\b(?:AIza[0-9A-Za-z_\\-]{20,}|sk-[0-9A-Za-z]{16,}|ghp_[0-9A-Za-z]{20,})\\b"),
        Regex("\\b(?:\\d[ -]*?){13,19}\\b") // credit/debit card patterns
    )

    fun containsSensitiveData(text: String): Boolean {
        if (text.isBlank()) return false
        return sensitivePatterns.any { it.containsMatchIn(text) }
    }

    fun redact(text: String): String {
        var sanitized = text
        sensitivePatterns.forEach { pattern ->
            sanitized = pattern.replace(sanitized, "[REDACTED]")
        }
        return sanitized
    }

    fun isEligibleForAutoMemory(source: String, content: String): Boolean {
        val lowerSource = source.lowercase()
        if (lowerSource in setOf("sms", "notification", "password_field", "credential")) {
            return false
        }
        return !containsSensitiveData(content)
    }
}
