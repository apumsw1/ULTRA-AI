package com.ultra.assistant.brain

import com.ultra.assistant.commands.CommandParser

class IntentEngine {
    fun classify(input: String): IntentAnalysis {
        val norm = CommandParser.normalize(input)
        val lang = when {
            input.any { it in '\u0980'..'\u09FF' } -> "bn"
            input.any { it in '\u0900'..'\u097F' } -> "hi"
            else -> "en"
        }
        val category = when {
            containsAny(norm, "lock phone", "lock screen", "ফোন লক", "ডিভাইস তথ্য", "device info", "battery", "status") ->
                IntentCategory.DEVICE_CONTROL
            containsAny(norm, "notification", "নোটিফিকেশন", "नोटिफिकेशन", "unread") ->
                IntentCategory.NOTIFICATION_READ
            containsAny(norm, "send sms", "prepare sms", "text ", "মেসেজ", "এসএমএস", "संदेश") ->
                IntentCategory.SMS_PREPARE
            containsAny(norm, "remind", "reminder", "রিমাইন্ডার", "মনে করিয়ে", "रिमाइंडर") ->
                IntentCategory.REMINDER
            containsAny(norm, "read screen", "what's on screen", "screen", "স্ক্রিন", "स्क्रीन") &&
                containsAny(norm, "read", "inspect", "see", "পড়", "দেখ", "देखो") ->
                IntentCategory.SCREEN_INSPECT
            containsAny(norm, "click ", "tap ", "scroll", "go back", "press back", "ক্লিক", "ব্যাক", "स्क्रॉल") ->
                IntentCategory.SCREEN_ACTION
            containsAny(norm, "remember that", "মনে রাখ", "याद रखना") ->
                IntentCategory.MEMORY_SAVE
            containsAny(norm, "গান", "song", "music", "youtube music") ->
                IntentCategory.MUSIC_SEARCH
            CommandParser.isSearch(norm) ->
                IntentCategory.WEB_SEARCH
            containsAny(norm, "open ", "launch ", "start ", "খোলো", "খুলে দাও", "চালু কর", "खोलो", "ऐप खोलो") ->
                IntentCategory.APP_OPEN
            else -> IntentCategory.CONVERSATION
        }
        val confidence = if (category == IntentCategory.CONVERSATION) 0.65f else 0.92f
        return IntentAnalysis(category, confidence, lang)
    }

    private fun containsAny(text: String, vararg terms: String): Boolean =
        terms.any { text.contains(it, ignoreCase = true) }
}
