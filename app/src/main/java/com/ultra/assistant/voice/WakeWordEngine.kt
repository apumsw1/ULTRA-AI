package com.ultra.assistant.voice

/** Transcript-based configurable wake phrase matcher (not an acoustic always-on DSP hotword model). */
object WakeWordEngine {
    private val defaultPhrases = listOf("hey ultra", "হে আল্ট্রা", "হেই আল্ট্রা", "हे अल्ट्रा")

    fun detected(text: String, customPhrase: String = "Hey Ultra"): Boolean {
        val all = buildPhrases(customPhrase)
        return all.any { text.contains(it, ignoreCase = true) }
    }

    fun strip(text: String, customPhrase: String = "Hey Ultra"): String {
        val all = buildPhrases(customPhrase)
        return all.fold(text) { acc, phrase -> acc.replace(phrase, "", ignoreCase = true) }.trim()
    }

    private fun buildPhrases(customPhrase: String): List<String> {
        val clean = customPhrase.trim()
        return if (clean.isNotBlank() && defaultPhrases.none { it.equals(clean, ignoreCase = true) }) {
            listOf(clean) + defaultPhrases
        } else {
            defaultPhrases
        }
    }
}
