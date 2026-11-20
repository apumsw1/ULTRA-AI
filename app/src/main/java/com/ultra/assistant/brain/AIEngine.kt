package com.ultra.assistant.brain

import kotlinx.coroutines.flow.Flow

interface AIEngine {
    suspend fun reasonAndPlan(
        userMessage: String,
        history: List<Pair<String, String>>,
        memoryContext: String,
        toolCatalogJson: String,
        screenContext: String,
        languageLabel: String
    ): Result<Pair<String, ReasoningDecision>>

    fun streamConversation(
        prompt: String,
        history: List<Pair<String, String>>,
        languageLabel: String
    ): Flow<String>
}
