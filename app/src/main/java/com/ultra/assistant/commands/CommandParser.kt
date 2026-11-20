package com.ultra.assistant.commands

object CommandParser {
    fun normalize(text: String): String = text.trim().lowercase()

    fun isSearch(text: String): Boolean =
        listOf("search", "সার্চ", "খুঁজে", "খোঁজ", "खोजो").any { normalize(text).contains(it) }

    fun isNext(text: String): Boolean =
        normalize(text).let { it.contains("next") || it.contains("পরের গান") }
}
