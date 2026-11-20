package com.ultra.assistant.brain

import com.ultra.assistant.tools.ToolRegistry

class TaskPlanner(private val registry: ToolRegistry) {
    fun buildPlan(decision: ReasoningDecision): List<PlannedToolCall> {
        return decision.plannedTools
            .filter { registry.getTool(it.toolId) != null }
            .take(5) // Bound max sequential tool calls per turn for safety & responsiveness
    }
}
