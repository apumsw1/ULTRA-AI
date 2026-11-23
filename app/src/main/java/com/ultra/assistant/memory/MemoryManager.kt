package com.ultra.assistant.memory

import android.content.Context
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.firebase.CloudSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import java.util.UUID
import java.util.concurrent.TimeUnit

class MemoryManager(
    context: Context,
    private val settingsRepository: LocalSettingsRepository = LocalSettingsRepository(context),
    private val cloudSync: CloudSyncRepository = CloudSyncRepository(context)
) {
    private val db = UltraDatabase.getInstance(context)
    private val memoryDao = db.memoryDao()
    private val conversationDao = db.conversationDao()
    private val auditLogDao = db.auditLogDao()
    private val automationRuleDao = db.automationRuleDao()

    private val _activeSessionId = MutableStateFlow("default")
    val activeSessionIdFlow: StateFlow<String> = _activeSessionId.asStateFlow()

    val memoriesFlow: Flow<List<MemoryRecordEntity>> = memoryDao.observeAll()
    val sessionsFlow: Flow<List<ConversationSessionEntity>> = conversationDao.observeSessions()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val conversationFlow: Flow<List<ConversationMessageEntity>> = _activeSessionId
        .flatMapLatest { sid -> conversationDao.observeBySession(sid, 120) }

    val auditLogsFlow: Flow<List<AuditLogEntity>> = auditLogDao.observeRecent(100)
    val automationRulesFlow: Flow<List<AutomationRuleEntity>> = automationRuleDao.observeAll()

    suspend fun startNewSession(): String {
        val newId = "chat_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        conversationDao.upsertSession(
            ConversationSessionEntity(
                id = newId,
                title = "New Conversation",
                createdAtMillis = System.currentTimeMillis(),
                updatedAtMillis = System.currentTimeMillis()
            )
        )
        _activeSessionId.value = newId
        return newId
    }

    fun selectSession(sessionId: String) {
        _activeSessionId.value = sessionId
    }

    suspend fun deleteSession(sessionId: String) {
        conversationDao.clearSessionMessages(sessionId)
        conversationDao.deleteSession(sessionId)
        if (_activeSessionId.value == sessionId) {
            _activeSessionId.value = "default"
        }
    }

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
        val settings = settingsRepository.settingsFlow.first()
        val ttlDays = ttlDaysOverride ?: settings.memoryTtlDays
        val now = System.currentTimeMillis()
        val expiresAt = if (ttlDays > 0) now + TimeUnit.DAYS.toMillis(ttlDays.toLong()) else 0L
        val id = "mem_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val safeTitle = PrivacyGuard.redact(title).trim().take(120)
        val safeContent = PrivacyGuard.redact(content).trim().take(1800)

        var cloudOk = false
        if (settings.cloudSyncEnabled) {
            val payload = if (settings.encryptCloudMemory) MemoryCrypto.encrypt(safeContent) else safeContent
            cloudOk = cloudSync.upsertMemory(
                docId = id,
                category = category,
                title = safeTitle,
                content = payload,
                encrypted = payload.startsWith("ENCv1:"),
                expiresAtMillis = expiresAt
            ).isSuccess
        }

        memoryDao.upsert(
            MemoryRecordEntity(
                id = id,
                category = category,
                title = safeTitle,
                content = safeContent,
                createdAtMillis = now,
                expiresAtMillis = expiresAt,
                syncedToCloud = cloudOk
            )
        )
        return true
    }

    suspend fun getPromptContextMemories(limit: Int = 10): List<MemoryRecordEntity> {
        val now = System.currentTimeMillis()
        memoryDao.purgeExpired(now)
        return memoryDao.getActiveMemories(now, limit)
    }

    suspend fun deleteMemory(id: String) {
        memoryDao.deleteById(id)
        cloudSync.deleteMemory(id)
    }

    suspend fun clearAllMemories() {
        val existing = memoryDao.getActiveMemories(0L, 500)
        memoryDao.clearAll()
        existing.forEach { cloudSync.deleteMemory(it.id) }
    }

    suspend fun purgeExpiredMemories(): Int {
        return memoryDao.purgeExpired(System.currentTimeMillis())
    }

    suspend fun recordConversationTurn(
        role: String,
        content: String,
        languageTag: String,
        providerLabel: String = ""
    ) {
        val sid = _activeSessionId.value
        val now = System.currentTimeMillis()
        val safeContent = PrivacyGuard.redact(content).take(4000)

        val existingCount = conversationDao.getLatestForSession(sid, 1).size
        val titleHint = if (role == "user" && existingCount == 0) {
            safeContent.trim().take(42).ifBlank { "Conversation" }
        } else null

        if (titleHint != null) {
            conversationDao.upsertSession(
                ConversationSessionEntity(
                    id = sid,
                    title = titleHint,
                    createdAtMillis = now,
                    updatedAtMillis = now
                )
            )
        } else if (role == "user") {
            val existingSessions = conversationDao.observeSessions().first()
            val currentSession = existingSessions.firstOrNull { it.id == sid }
            conversationDao.upsertSession(
                ConversationSessionEntity(
                    id = sid,
                    title = currentSession?.title?.takeIf { it != "New Conversation" }
                        ?: safeContent.trim().take(42).ifBlank { "Conversation" },
                    createdAtMillis = currentSession?.createdAtMillis ?: now,
                    updatedAtMillis = now
                )
            )
        }

        conversationDao.insert(
            ConversationMessageEntity(
                sessionId = sid,
                role = role,
                content = safeContent,
                languageTag = languageTag,
                providerLabel = providerLabel,
                timestampMillis = now
            )
        )
    }

    suspend fun getRecentConversation(limit: Int = 14): List<ConversationMessageEntity> {
        return conversationDao.getLatestForSession(_activeSessionId.value, limit).reversed()
    }

    suspend fun clearConversationHistory() {
        conversationDao.clearAll()
        conversationDao.clearAllSessions()
        _activeSessionId.value = "default"
    }

    suspend fun recordAuditLog(
        toolId: String,
        riskLevel: String,
        status: String,
        summary: String,
        verified: Boolean
    ) {
        val id = "log_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val sanitized = PrivacyGuard.redact(summary).take(500).ifBlank { "Action executed" }
        auditLogDao.insert(
            AuditLogEntity(
                id = id,
                toolId = toolId,
                riskLevel = riskLevel,
                status = status,
                summary = sanitized,
                verified = verified
            )
        )
        val settings = settingsRepository.settingsFlow.first()
        if (settings.cloudSyncEnabled) {
            cloudSync.appendAuditLog(id, toolId, riskLevel, status, sanitized, verified)
        }
    }

    suspend fun clearAuditLogs() {
        auditLogDao.clearAll()
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
        val settings = settingsRepository.settingsFlow.first()
        if (settings.cloudSyncEnabled) {
            cloudSync.upsertAutomation(id, entity.name, entity.description, entity.triggerType, entity.actionTool, entity.actionArgsJson, entity.enabled)
        }
        return entity
    }

    suspend fun toggleAutomationRule(rule: AutomationRuleEntity) {
        val updated = rule.copy(enabled = !rule.enabled)
        automationRuleDao.upsert(updated)
        val settings = settingsRepository.settingsFlow.first()
        if (settings.cloudSyncEnabled) {
            cloudSync.upsertAutomation(updated.id, updated.name, updated.description, updated.triggerType, updated.actionTool, updated.actionArgsJson, updated.enabled)
        }
    }

    suspend fun deleteAutomationRule(id: String) {
        automationRuleDao.deleteById(id)
        cloudSync.deleteAutomation(id)
    }

    suspend fun getEnabledAutomationRules(): List<AutomationRuleEntity> = automationRuleDao.getEnabledRules()
}
