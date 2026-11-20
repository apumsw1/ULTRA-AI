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

    suspend fun getRecentHistoryPairs(limit: Int = 8): List<Pair<String, String>> {
        return memoryManager.getRecentConversation(limit).map { it.role to it.content }
    }

    suspend fun recordExchange(userText: String, assistantText: String, languageTag: String) {
        memoryManager.recordConversationTurn("user", userText, languageTag)
        memoryManager.recordConversationTurn("assistant", assistantText, languageTag)
    }

    suspend fun buildMemorySummary(): String {
        val memories = memoryManager.getPromptContextMemories(8)
        if (memories.isEmpty()) return "None"
        return memories.joinToString("\n") { "- [${it.category}] ${it.title}: ${it.content}" }
    }
}
