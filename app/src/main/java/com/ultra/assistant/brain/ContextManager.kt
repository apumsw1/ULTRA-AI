package com.ultra.assistant.brain

import com.ultra.assistant.memory.MemoryManager

sealed interface FollowUpSlot {
    data object None : FollowUpSlot
    data object MusicLanguage : FollowUpSlot
    data class PendingConfirmation(val confirmation: PendingToolConfirmation) : FollowUpSlot
}

class ContextManager(private val memoryManager: MemoryManager) {
    @Volatile
    var followUpSlot: FollowUpSlot = FollowUpSlot.None

    @Volatile
    var lastActiveApp: String? = null

    @Volatile
    var lastTopic: String? = null

    suspend fun getRecentHistoryPairs(limit: Int = 8): List<Pair<String, String>> {
        return memoryManager.getRecentConversation(limit).map { it.role to it.content }
    }

    suspend fun recordExchange(userText: String, assistantText: String, languageTag: String) {
        memoryManager.recordConversationTurn("user", userText, languageTag)
        memoryManager.recordConversationTurn("assistant", assistantText, languageTag)
        if (userText.isNotBlank()) {
            lastTopic = userText.trim().take(80)
        }
    }

    suspend fun buildMemorySummary(): String {
        val memories = memoryManager.getPromptContextMemories(6)
        val parts = mutableListOf<String>()
        lastActiveApp?.let { parts.add("Recent app focus: $it") }
        lastTopic?.let { parts.add("Recent topic: $it") }
        memories.forEach {
            parts.add("- [${it.category}] ${it.title}: ${it.content}")
        }
        return if (parts.isEmpty()) "None" else parts.joinToString("\n")
    }

    fun clearTemporaryContext() {
        followUpSlot = FollowUpSlot.None
        lastActiveApp = null
        lastTopic = null
    }
}
