package com.ultra.assistant.voice

import java.util.Locale

object LanguageManager {

    fun isAutoLanguage(tag: String): Boolean {
        val norm = tag.trim().lowercase()
        return norm.isBlank() || norm == "auto" || norm.startsWith("auto ")
    }

    fun locale(tag: String): Locale = when (tag.lowercase()) {
        "বাংলা", "bn", "bn-bd", "bn-in" -> Locale.forLanguageTag("bn-BD")
        "हिन्दी", "hindi", "hi", "hi-in" -> Locale.forLanguageTag("hi-IN")
        "auto", "auto (bn/hi/en)" -> Locale.getDefault().takeIf {
            it.language in setOf("bn", "hi", "en")
        } ?: Locale.US
        else -> Locale.US
    }

    fun speechRecognizerTag(tag: String): String = when (tag.lowercase()) {
        "বাংলা", "bn", "bn-bd", "bn-in" -> "bn-IN"
        "हिन्दी", "hindi", "hi", "hi-in" -> "hi-IN"
        "auto", "auto (bn/hi/en)" -> when (Locale.getDefault().language) {
            "bn" -> "bn-IN"
            "hi" -> "hi-IN"
            else -> "en-US"
        }
        else -> "en-US"
    }

    fun detectScriptLanguageTag(text: String, fallbackSetting: String = "English"): String {
        val bnCount = text.count { it in '\u0980'..'\u09FF' }
        val hiCount = text.count { it in '\u0900'..'\u097F' }
        val latinCount = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return when {
            bnCount > 0 && bnCount >= hiCount && bnCount >= latinCount / 3 -> "বাংলা"
            hiCount > 0 && hiCount > bnCount && hiCount >= latinCount / 3 -> "हिन्दी"
            latinCount > 0 -> "English"
            isAutoLanguage(fallbackSetting) -> "English"
            else -> fallbackSetting
        }
    }

    /**
     * Splits mixed-script text (e.g. Bengali + English or Hindi + English code-switching)
     * into contiguous speech chunks tagged with the appropriate Locale so TTS pronounces
     * multilingual replies more naturally.
     */
    fun splitCodeSwitchedSegments(text: String, defaultSetting: String): List<Pair<Locale, String>> {
        val clean = text.trim()
        if (clean.isBlank()) return emptyList()

        val defaultLocale = locale(detectScriptLanguageTag(clean, defaultSetting))
        val segments = mutableListOf<Pair<Locale, StringBuilder>>()

        var currentLocale = defaultLocale
        val tokens = clean.split(Regex("(?<=\\s)|(?=\\s+)"))

        for (token in tokens) {
            val tokenLocale = when {
                token.any { it in '\u0980'..'\u09FF' } -> Locale.forLanguageTag("bn-BD")
                token.any { it in '\u0900'..'\u097F' } -> Locale.forLanguageTag("hi-IN")
                token.any { it in 'a'..'z' || it in 'A'..'Z' } -> Locale.US
                else -> currentLocale
            }

            if (segments.isEmpty() || segments.last().first.language != tokenLocale.language) {
                segments += tokenLocale to StringBuilder(token)
            } else {
                segments.last().second.append(token)
            }
            currentLocale = tokenLocale
        }

        // Merge tiny punctuation-only or single-word fragments into neighbors to avoid choppy TTS transitions
        val merged = mutableListOf<Pair<Locale, String>>()
        for ((loc, sb) in segments) {
            val str = sb.toString().trim()
            if (str.isBlank()) continue
            if (merged.isNotEmpty() && str.length <= 2 && str.none { it.isLetter() }) {
                val prev = merged.removeAt(merged.lastIndex)
                merged += prev.first to "${prev.second} $str".trim()
            } else {
                merged += loc to str
            }
        }
        return if (merged.isEmpty()) listOf(defaultLocale to clean) else merged
    }
}
