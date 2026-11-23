package com.ultra.assistant.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class MemoryRecordEntity(
    @PrimaryKey val id: String,
    val category: String, // PREFERENCE, CONTEXT, CONVERSATION, KNOWLEDGE
    val title: String,
    val content: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long, // 0L = no expiration
    val syncedToCloud: Boolean = false
)

@Entity(tableName = "conversation_sessions")
data class ConversationSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "conversation_messages")
data class ConversationMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: String = "default",
    val role: String, // "user", "assistant", "tool"
    val content: String,
    val languageTag: String = "en-US",
    val providerLabel: String = "",
    val thoughtSummary: String = "",
    val timestampMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val toolId: String,
    val riskLevel: String, // LOW, MEDIUM, HIGH
    val status: String, // SUCCESS, FAILED, DENIED, AWAITING_CONFIRMATION
    val summary: String,
    val verified: Boolean,
    val timestampMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "automation_rules")
data class AutomationRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val triggerType: String, // VOICE_PHRASE, SCHEDULED_REMINDER, MANUAL_WORKFLOW
    val actionTool: String,
    val actionArgsJson: String,
    val enabled: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis()
)
