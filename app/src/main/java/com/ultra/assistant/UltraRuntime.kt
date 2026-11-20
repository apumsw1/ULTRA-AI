package com.ultra.assistant

import android.content.Context
import com.ultra.assistant.brain.AgentManager
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.firebase.CloudSyncRepository
import com.ultra.assistant.memory.MemoryManager

/**
 * Process-scoped runtime holder so foreground VoiceAssistantService and UI ViewModels
 * share one MemoryManager and AgentManager instance (including pending confirmations and traces).
 */
object UltraRuntime {
    @Volatile
    private var settingsRepo: LocalSettingsRepository? = null

    @Volatile
    private var cloudRepo: CloudSyncRepository? = null

    @Volatile
    private var memoryMgr: MemoryManager? = null

    @Volatile
    private var agentMgr: AgentManager? = null

    fun settingsRepository(context: Context): LocalSettingsRepository =
        settingsRepo ?: synchronized(this) {
            settingsRepo ?: LocalSettingsRepository(context.applicationContext).also { settingsRepo = it }
        }

    fun cloudSyncRepository(context: Context): CloudSyncRepository =
        cloudRepo ?: synchronized(this) {
            cloudRepo ?: CloudSyncRepository(context.applicationContext).also { cloudRepo = it }
        }

    fun memoryManager(context: Context): MemoryManager =
        memoryMgr ?: synchronized(this) {
            memoryMgr ?: MemoryManager(
                context = context.applicationContext,
                settingsRepository = settingsRepository(context),
                cloudSync = cloudSyncRepository(context)
            ).also { memoryMgr = it }
        }

    fun agentManager(context: Context): AgentManager =
        agentMgr ?: synchronized(this) {
            agentMgr ?: AgentManager(
                context = context.applicationContext,
                memoryManager = memoryManager(context),
                settingsRepository = settingsRepository(context)
            ).also { agentMgr = it }
        }
}
