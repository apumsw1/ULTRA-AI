package com.ultra.assistant

import android.content.Context
import com.ultra.assistant.brain.AgentManager
import com.ultra.assistant.control.NotificationRepository
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.data.NetworkMonitor
import com.ultra.assistant.firebase.CloudSyncRepository
import com.ultra.assistant.memory.MemoryManager

/**
 * Process-scoped runtime holder so foreground VoiceAssistantService and UI ViewModels
 * share one MemoryManager, NetworkMonitor, and AgentManager instance.
 */
object UltraRuntime {
    @Volatile
    private var settingsRepo: LocalSettingsRepository? = null

    @Volatile
    private var networkMon: NetworkMonitor? = null

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

    fun networkMonitor(context: Context): NetworkMonitor =
        networkMon ?: synchronized(this) {
            networkMon ?: NetworkMonitor(context.applicationContext).also { networkMon = it }
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
                cloudSync = null
            ).also { memoryMgr = it }
        }

    fun agentManager(context: Context): AgentManager =
        agentMgr ?: synchronized(this) {
            agentMgr ?: AgentManager(
                context = context.applicationContext,
                memoryManager = memoryManager(context),
                settingsRepository = settingsRepository(context),
                networkMonitor = networkMonitor(context)
            ).also { agentMgr = it }
        }

    /**
     * Clears temporary conversation turns, session notes, follow-up slots, and captured notifications
     * when the app closes, leaving ONLY the user's given name intact.
     */
    fun clearEphemeralSession(context: Context) {
        val appCtx = context.applicationContext
        memoryManager(appCtx).clearTemporaryContext()
        agentManager(appCtx).clearTemporaryContext()
        NotificationRepository.clearAll()
    }
}
