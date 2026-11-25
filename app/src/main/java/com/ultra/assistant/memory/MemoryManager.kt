package com.ultra.assistant.memory

import android.content.Context
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.firebase.CloudSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Privacy-first session MemoryManager:
 * - Conversation history, temporary notes, and tool audit entries live strictly in bounded RAM
 *   and are never written to Room disk or Firestore cloud sync.
 * - Legacy disk rows in memories / conversations / audit_logs are purged on startup.
 * - Only non-sensitive user-configured AutomationRuleEntity schedules persist locally in Room.
 */
class MemoryManager(
    context: Context,
    private val settingsRepository: LocalSettingsRepository = LocalSettingsRepository(context),
    @Suppress("UNUSED_PARAMETER")
    private val cloudSync: CloudSyncRepository? = null
) {
    private val db = UltraDatabase.getInstance(context)
    private val legacyMemoryDao = db.memoryDao()
    private val legacyConversationDao = db.conversationDao()
    private val legacyAuditLogDao = db.auditLogDao()
    private val automationRuleDao = db.automationRuleDao()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _activeSessionId = MutableStateFlow("default")
    val activeSessionIdFlow: StateFlow<String> = _activeSessionId.asStateFlow()

    private val _ephemeralMemories = MutableStateFlow<List<MemoryRecordEntity>>(emptyList())
    val memoriesFlow: Flow<List<MemoryRecordEntity>> = _ephemeralMemories.asStateFlow()

    private val _ephemeralSessions = MutableStateFlow<List<ConversationSessionEntity>>(emptyList())
    val sessionsFlow: Flow<List<ConversationSessionEntity>> = _ephemeralSessions.asStateFlow()

    private val _ephemeralMessages = MutableStateFlow<Map<String, List<ConversationMessageEntity>>>(emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    val conversationFlow: Flow<List<ConversationMessageEntity>> =
        combine(_activeSessionId, _ephemeralMessages) { sid, bySession ->
            bySession[sid].orEmpty().takeLast(MAX_SESSION_MESSAGES)
        }

    private val _ephemeralAuditLogs = MutableStateFlow<List<AuditLogEntity>>(emptyList())
    val auditLogsFlow: Flow<List<AuditLogEntity>> = _ephemeralAuditLogs.asStateFlow()

    val automationRulesFlow: Flow<List<AutomationRuleEntity>> = automationRuleDao.observeAll()

    init {
        ioScope.launch {
            purgeLegacyDiskStorage()
        }
    }

    private suspend fun purgeLegacyDiskStorage() {
        runCatching {
            legacyMemoryDao.clearAll()
            legacyConversationDao.clearAll()
            legacyConversationDao.clearAllSessions()
            legacyAuditLogDao.clearAll()
        }
    }

    suspend fun startNewSession(): String {
        val newId = "chat_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val now = System.currentTimeMillis()
        val session = ConversationSessionEntity(
            id = newId,
            title = "Temporary Session",
            createdAtMillis = now,
            updatedAtMillis = now
        )
        _ephemeralSessions.value = (listOf(session) + _ephemeralSessions.value.filterNot { it.id == newId })
            .take(MAX_RAM_SESSIONS)
        _activeSessionId.value = newId
        return newId
    }

    fun selectSession(sessionId: String) {
        _activeSessionId.value = sessionId
    }

    suspend fun deleteSession(sessionId: String) {
        _ephemeralMessages.value = _ephemeralMessages.value - sessionId
        _ephemeralSessions.value = _ephemeralSessions.value.filterNot { it.id == sessionId }
        if (_activeSessionId.value == sessionId) {
            _activeSessionId.value = "default"
        }
    }

    /**
     * Stores only temporary RAM-only context for the active app run.
     * Permanently storing personal memories, chats, or cloud memory is disabled.
     */
    suspend fun saveMemory(
        category: String,
        title: String,
        content: String,
        source: String = "user",
        ttlDaysOverride: Int? = null
    ): Boolean {
        if (!PrivacyGuard.isEligibleForAutoMemory(source, content)) {
            return false
        }
        val now = System.currentTimeMillis()
        val safeTitle = PrivacyGuard.redact(title).trim().take(80)
        val safeContent = PrivacyGuard.redact(content).trim().take(400)
        if (safeContent.isBlank()) return false

        val record = MemoryRecordEntity(
            id = "tmp_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            category = category.ifBlank { "session_note" },
            title = safeTitle.ifBlank { "Session Context" },
            content = safeContent,
            createdAtMillis = now,
            expiresAtMillis = 0L,
            syncedToCloud = false
        )
        _ephemeralMemories.value = (listOf(record) + _ephemeralMemories.value).take(MAX_SESSION_NOTES)
        return true
    }

    suspend fun getPromptContextMemories(limit: Int = 8): List<MemoryRecordEntity> {
        return _ephemeralMemories.value.take(limit.coerceIn(1, MAX_SESSION_NOTES))
    }

    suspend fun deleteMemory(id: String) {
        _ephemeralMemories.value = _ephemeralMemories.value.filterNot { it.id == id }
    }

    suspend fun clearAllMemories() {
        _ephemeralMemories.value = emptyList()
        runCatching { legacyMemoryDao.clearAll() }
    }

    suspend fun purgeExpiredMemories(): Int {
        return 0
    }

    suspend fun recordConversationTurn(
        role: String,
        content: String,
        languageTag: String,
        providerLabel: String = "",
        thoughtSummary: String = ""
    ) {
        val sid = _activeSessionId.value
        val now = System.currentTimeMillis()
        val safeContent = PrivacyGuard.redact(content).take(2000)

        val currentList = _ephemeralMessages.value[sid].orEmpty()
        if (role == "user") {
            val existingSession = _ephemeralSessions.value.firstOrNull { it.id == sid }
            val title = existingSession?.title?.takeIf { it != "Temporary Session" }
                ?: safeContent.trim().take(42).ifBlank { "Temporary Session" }
            val updatedSession = ConversationSessionEntity(
                id = sid,
                title = title,
                createdAtMillis = existingSession?.createdAtMillis ?: now,
                updatedAtMillis = now
            )
            _ephemeralSessions.value = (listOf(updatedSession) + _ephemeralSessions.value.filterNot { it.id == sid })
                .take(MAX_RAM_SESSIONS)
        }

        val entry = ConversationMessageEntity(
            id = now + currentList.size,
            sessionId = sid,
            role = role,
            content = safeContent,
            languageTag = languageTag,
            providerLabel = providerLabel,
            thoughtSummary = PrivacyGuard.redact(thoughtSummary).take(400),
            timestampMillis = now
        )
        val updatedMessages = (currentList + entry).takeLast(MAX_SESSION_MESSAGES)
        _ephemeralMessages.value = (_ephemeralMessages.value + (sid to updatedMessages))
            .let { map ->
                if (map.size <= MAX_RAM_SESSIONS) map
                else map.entries.toList().takeLast(MAX_RAM_SESSIONS).associate { it.toPair() }
            }
    }

    suspend fun getRecentConversation(limit: Int = 14): List<ConversationMessageEntity> {
        val sid = _activeSessionId.value
        return _ephemeralMessages.value[sid].orEmpty().takeLast(limit.coerceIn(1, MAX_SESSION_MESSAGES))
    }

    suspend fun clearConversationHistory() {
        _ephemeralMessages.value = emptyMap()
        _ephemeralSessions.value = emptyList()
        _activeSessionId.value = "default"
        runCatching {
            legacyConversationDao.clearAll()
            legacyConversationDao.clearAllSessions()
        }
    }

    suspend fun recordAuditLog(
        toolId: String,
        riskLevel: String,
        status: String,
        summary: String,
        verified: Boolean
    ) {
        val id = "log_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val sanitized = PrivacyGuard.redact(summary).take(300).ifBlank { "Action executed" }
        val logEntry = AuditLogEntity(
            id = id,
            toolId = toolId,
            riskLevel = riskLevel,
            status = status,
            summary = sanitized,
            verified = verified
        )
        _ephemeralAuditLogs.value = (listOf(logEntry) + _ephemeralAuditLogs.value).take(MAX_AUDIT_LOGS)
    }

    suspend fun clearAuditLogs() {
        _ephemeralAuditLogs.value = emptyList()
        runCatching { legacyAuditLogDao.clearAll() }
    }

    /**
     * Wipes all ephemeral conversation turns, session notes, and action logs from RAM and
     * ensures legacy database tables stay empty. Only userDisplayName in DataStore persists.
     */
    fun clearTemporaryContext() {
        _ephemeralMessages.value = emptyMap()
        _ephemeralSessions.value = emptyList()
        _ephemeralMemories.value = emptyList()
        _ephemeralAuditLogs.value = emptyList()
        _activeSessionId.value = "default"
        ioScope.launch { purgeLegacyDiskStorage() }
    }

    suspend fun saveAutomationRule(
        name: String,
        description: String,
        triggerType: String,
        actionTool: String,
        actionArgsJson: String,
        enabled: Boolean = true
    ): AutomationRuleEntity {
        val id = "rule_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val entity = AutomationRuleEntity(
            id = id,
            name = name.trim().take(100),
            description = description.trim().take(500),
            triggerType = triggerType,
            actionTool = actionTool,
            actionArgsJson = actionArgsJson.take(1000),
            enabled = enabled
        )
        automationRuleDao.upsert(entity)
        return entity
    }

    suspend fun toggleAutomationRule(rule: AutomationRuleEntity) {
        val updated = rule.copy(enabled = !rule.enabled)
        automationRuleDao.upsert(updated)
    }

    suspend fun deleteAutomationRule(id: String) {
        automationRuleDao.deleteById(id)
    }

    suspend fun getEnabledAutomationRules(): List<AutomationRuleEntity> = automationRuleDao.getEnabledRules()

    companion object {
        const val MAX_SESSION_MESSAGES = 30
        const val MAX_RAM_SESSIONS = 5
        const val MAX_SESSION_NOTES = 10
        const val MAX_AUDIT_LOGS = 25
    }
}
