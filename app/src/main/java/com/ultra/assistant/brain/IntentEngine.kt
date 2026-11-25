package com.ultra.assistant.brain

import com.ultra.assistant.commands.CommandParser

class IntentEngine {
    fun classify(input: String): IntentAnalysis {
        val cleaned = normalizeSpeechTranscript(input)
        val norm = CommandParser.normalize(cleaned)
        val lang = detectLanguage(cleaned)
        val category = when {
            isCompoundAction(cleaned) -> IntentCategory.SCREEN_ACTION
            containsAny(
                norm,
                "lock phone", "lock screen", "ফোন লক", "ডিভাইস তথ্য", "device info",
                "wifi", "wi-fi", "bluetooth", "ওয়াইফাই", "ব্লুটুথ", "brightness",
                "display settings", "network settings", "sound settings",
                "open settings", "open the settings", "সেটিংস খোলো", "सेटिंग्स खोलो",
                "quick settings", "কুইক সেটিংস"
            ) -> IntentCategory.DEVICE_CONTROL
            containsAny(
                norm,
                "volume", "mute", "unmute", "pause music", "play music", "resume music",
                "next song", "previous song", "flashlight", "torch",
                "ভলিউম", "ফ্ল্যাশলাইট", "টর্চ", "পরের গান", "আগের গান", "গান থামাও", "গান চালাও",
                "वॉल्यूम", "फ्लैशलाइट", "अगला गाना", "पिछला गाना"
            ) -> IntentCategory.DEVICE_CONTROL
            containsAny(norm, "call ", "dial ", "phone ", "কল কর", "ফোন কর", "कॉल कर", "फ़ोन कर") ->
                IntentCategory.PHONE_CALL
            containsAny(norm, "notification", "নোটিফিকেশন", "नोटिफिकेशन", "unread") ->
                IntentCategory.NOTIFICATION_READ
            containsAny(norm, "send sms", "prepare sms", "send message", "মেসেজ", "এসএমএস", "संदेश") ->
                IntentCategory.SMS_PREPARE
            containsAny(norm, "remind", "reminder", "রিমাইন্ডার", "মনে করিয়ে", "रिमाइंडर") ->
                IntentCategory.REMINDER
            containsAny(norm, "read screen", "what's on screen", "inspect screen", "স্ক্রিন দেখ", "স্ক্রিন পড়", "स्क्रीन देखो") ->
                IntentCategory.SCREEN_INSPECT
            containsAny(
                norm,
                "click ", "tap ", "type ", "scroll", "go back", "press back", "go home",
                "home screen", "recent apps", "ক্লিক", "টাইপ", "ব্যাক", "স্ক্রল", "হোম", "स्क्रॉल", "होम", "बैक"
            ) -> IntentCategory.SCREEN_ACTION
            containsAny(norm, "remember that", "মনে রাখ", "याद रखना", "याद रखो") ->
                IntentCategory.MEMORY_SAVE
            containsAny(norm, "search youtube", "youtube for", "ইউটিউবে সার্চ", "ইউটিউব সার্চ", "यूट्यूब पर खोजो", "यूट्यूब सर्च") ->
                IntentCategory.YOUTUBE_SEARCH
            containsAny(norm, "গান", "song", "music", "youtube music", "गाना") ->
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

    companion object {
        fun detectLanguage(input: String): String {
            val lower = input.lowercase()
            return when {
                input.any { it in '\u0980'..'\u09FF' } -> "bn"
                input.any { it in '\u0900'..'\u097F' } -> "hi"
                listOf("kholo", "koro", "kore", "korbo", "bhalo", "amar", "tumi", "kemone", "kemon", "thamo", "naam").any {
                    Regex("\\b$it\\b").containsMatchIn(lower)
                } -> "bn"
                else -> "en"
            }
        }

        fun isStopCommand(input: String): Boolean {
            val trimmed = input.trim().lowercase()
                .trimEnd('.', '!', '?', '।')
            if (trimmed.isBlank()) return false
            val exact = setOf(
                "stop", "stop speaking", "stop talking", "be quiet", "shut up", "quiet",
                "stop listening", "cancel", "abort", "halt",
                "থামো", "থাম", "চুপ করো", "চুপ", "বন্ধ করো", "থামিয়ে দাও", "বাতিল করো",
                "রুকো", "रुको", "बंद करो", "चुप रहो", "chup koro", "thamo"
            )
            return trimmed in exact ||
                trimmed.startsWith("stop ultra") ||
                trimmed.startsWith("ultra stop")
        }

        fun extractUserNameDeclaration(input: String): String? {
            val trimmed = input.trim()
            val patterns = listOf(
                Regex("^(?:my name is|call me|i am)\\s+([\\p{L}\\p{M}][\\p{L}\\p{M}\\s'.-]{0,40})$", RegexOption.IGNORE_CASE),
                Regex("^(?:আমার নাম|আমাকে)\\s+([\\p{L}\\p{M}][\\p{L}\\p{M}\\s'.-]{0,40}?)(?:\\s+(?:হয়|হলো|বলো|ডাকো|বলে ডাকো))?$", RegexOption.IGNORE_CASE),
                Regex("^(?:मेरा नाम|मुझे)\\s+([\\p{L}\\p{M}][\\p{L}\\p{M}\\s'.-]{0,40}?)(?:\\s+(?:है|कहें|बोलो))?$", RegexOption.IGNORE_CASE)
            )
            for (pattern in patterns) {
                val match = pattern.find(trimmed) ?: continue
                val candidate = match.groupValues[1]
                    .trim()
                    .trimEnd('.', '!', '?', '।', ',')
                if (candidate.isNotBlank() &&
                    candidate.split(Regex("\\s+")).size <= 4 &&
                    !candidate.contains("ultra", ignoreCase = true)
                ) {
                    return candidate
                }
            }
            return null
        }

        fun normalizeSpeechTranscript(input: String): String {
            var s = input.trim()
            val replacements = listOf(
                Regex("\\bu\\s*tube\\b", RegexOption.IGNORE_CASE) to "YouTube",
                Regex("\\byou\\s+tube\\b", RegexOption.IGNORE_CASE) to "YouTube",
                Regex("\\bwi\\s*fi\\b", RegexOption.IGNORE_CASE) to "wifi",
                Regex("\\bblue\\s+tooth\\b", RegexOption.IGNORE_CASE) to "bluetooth",
                Regex("\\bflash\\s+light\\b", RegexOption.IGNORE_CASE) to "flashlight",
                Regex("\\bwhats\\s+app\\b", RegexOption.IGNORE_CASE) to "WhatsApp",
                Regex("\\bface\\s+book\\b", RegexOption.IGNORE_CASE) to "Facebook",
                Regex("\\binsta\\s+gram\\b", RegexOption.IGNORE_CASE) to "Instagram"
            )
            replacements.forEach { (pattern, replacement) ->
                s = s.replace(pattern, replacement)
            }
            return s
        }

        fun isCompoundAction(input: String): Boolean {
            val lower = input.lowercase()
            val hasOpenOrLaunch = listOf(
                "open ", "launch ", "start ", "go to ", "খোলো", "খুলে", "চালু", "खोलो"
            ).any { lower.contains(it) }
            val hasSecondaryVerb = listOf(
                " and ", " then ", " after that ", " search ", " type ", " click ", " tap ",
                " play ", " scroll ", " send ", " message ", " message to ",
                " করে ", " সার্চ", " টাইপ", " ক্লিক", " লিখে", " এবং ", " তারপর ", " স্ক্রল",
                " और ", " फिर ", " सर्च", " टाइप", " स्क्रॉल"
            ).any { lower.contains(it) }
            val hasTwoScreenActions = listOf("click ", "tap ", "type ", "scroll ").count { lower.contains(it) } >= 2
            return (hasOpenOrLaunch && hasSecondaryVerb) || hasTwoScreenActions
        }
    }
}
