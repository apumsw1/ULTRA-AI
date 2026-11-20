package com.ultra.assistant.automation

import android.content.Context

class RuleManager(context: Context) {
    private val prefs = context.getSharedPreferences("ultra_automation", Context.MODE_PRIVATE)

    fun all(): List<Pair<String, String>> =
        prefs.all.entries
            .filter { it.value is String }
            .map { it.key to (it.value as String) }
            .sortedBy { it.first }

    fun add(name: String, description: String) {
        prefs.edit().putString(name, description).apply()
    }

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }
}
