package com.ultra.assistant.voice

import java.util.Locale

object LanguageManager {
    fun locale(tag: String): Locale = when (tag.lowercase()) {
        "বাংলা", "bn", "bn-bd", "bn-in" -> Locale("bn", "BD")
        "हिन्दी", "hindi", "hi", "hi-in" -> Locale("hi", "IN")
        else -> Locale.US
    }

    fun speechRecognizerTag(tag: String): String = when (tag.lowercase()) {
        "বাংলা", "bn", "bn-bd", "bn-in" -> "bn-IN"
        "हिन्दी", "hindi", "hi", "hi-in" -> "hi-IN"
        else -> "en-US"
    }
}
