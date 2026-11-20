package com.ultra.assistant.automation

class AutomationEngine(private val rules: RuleManager) {
    fun savedRules(): List<Pair<String, String>> = rules.all()
}
