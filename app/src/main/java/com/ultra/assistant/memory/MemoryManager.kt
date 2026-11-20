package com.ultra.assistant.memory

import android.content.Context
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.firebase.CloudSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

    val memoriesFlow: Flow<List<MemoryRecordEntity>> = memoryDao.observeAll()
    val conversationFlow: Flow<List<ConversationMessageEntity>> = conversationDao.observeRecent(80)
    val auditLogsFlow: Flow<List<AuditLogEntity>> = auditLogDao.observeRecent(100)
    val automationRulesFlow: Flow<List<AutomationRuleEntity>> = automationRuleDao.observeAll()

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

    suspend fun recordConversationTurn(role: String, content: String, languageTag: String) {
        val safeContent = PrivacyGuard.redact(content).take(2500)
        conversationDao.insert(
            ConversationMessageEntity(
                role = role,
                content = safeContent,
                languageTag = languageTag,
                timestampMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun getRecentConversation(limit: Int = 10): List<ConversationMessageEntity> {
        return conversationDao.getLatest(limit).reversed()
    }

    suspend fun clearConversationHistory() {
        conversationDao.clearAll()
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
