package com.ultra.assistant.brain

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.ultra.assistant.control.MediaVolumeController
import com.ultra.assistant.memory.MemoryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Genuine on-device Offline Assistant Engine.
 * Handles local commands, reminders, device info (battery, RAM, storage),
 * time/date, percentages, unit conversions, recursive-descent math,
 * local memory recall/saving, and transparent offline capability messaging.
 */
class OfflineAssistantEngine(
    context: Context,
    private val memoryManager: MemoryManager
) {
    private val appContext = context.applicationContext
    private val mediaController = MediaVolumeController(appContext)

    suspend fun respond(
        userInput: String,
        intent: IntentAnalysis,
        localToolDecision: ReasoningDecision?
    ): Pair<String, ReasoningDecision> {
        if (localToolDecision != null) {
            return "Offline • Local Agent" to localToolDecision
        }

        val trimmed = userInput.trim()
        val lower = trimmed.lowercase()

        // 1. Save memory locally
        if (intent.category == IntentCategory.MEMORY_SAVE) {
            val fact = trimmed
                .replace(Regex("^(please\\s+)?(remember that|remember|মনে রাখো|মনে রাখ|याद रखो|याद रखना)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
            if (fact.isNotBlank()) {
                val saved = memoryManager.saveMemory("PREFERENCE", "Offline Note", fact, source = "user")
                val reply = if (saved) {
                    localized(intent.detectedLanguage,
                        en = "Got it. I've saved that locally: \"$fact\".",
                        bn = "ঠিক আছে, আমি এটা লোকালি মনে রেখেছি: \"$fact\"।",
                        hi = "ठीक है, मैंने इसे लोकल मेमोरी में सहेज लिया है: \"$fact\"।"
                    )
                } else {
                    localized(intent.detectedLanguage,
                        en = "For your privacy, I can't save sensitive passwords, OTPs, or tokens in memory.",
                        bn = "নিরাপত্তার জন্য পাসওয়ার্ড বা ওটিপি মেমোরিতে সংরক্ষণ করা হয় না।",
                        hi = "सुरक्षा के लिए पासवर्ड या ओटीपी मेमोरी में सहेजे नहीं जाते हैं।"
                    )
                }
                return "Offline • Local Memory" to ReasoningDecision("Saved local memory note", reply, emptyList())
            }
        }

        // 2. Recall local memories
        if (lower.contains("what do you remember") || lower.contains("my memories") ||
            lower.contains("কী মনে আছে") || lower.contains("क्या याद है")
        ) {
            val memories = memoryManager.getPromptContextMemories(5)
            val reply = if (memories.isEmpty()) {
                localized(intent.detectedLanguage,
                    en = "I don't have any saved memories yet. You can say \"Remember that...\" anytime.",
                    bn = "এখনও কোনো মেমোরি সেভ করা নেই। চাইলে \"মনে রাখো...\" বলতে পারেন।",
                    hi = "अभी कोई मेमोरी सहेजी नहीं गई है। आप कभी भी \"याद रखो...\" कह सकते हैं।"
                )
            } else {
                val list = memories.joinToString(". ") { "${it.title}: ${it.content}" }
                localized(intent.detectedLanguage,
                    en = "Here is what I remember locally: $list.",
                    bn = "আমার লোকাল মেমোরিতে যা আছে: $list।",
                    hi = "मेरी लोकल मेमोरी में यह सहेजा है: $list।"
                )
            }
            return "Offline • Local Memory" to ReasoningDecision("Recalled local memories", reply, emptyList())
        }

        // 3. Time, Date, Battery, Storage / RAM
        if (lower.contains("time") || lower.contains("সময়") || lower.contains("কটা বাজে") || lower.contains("समय") || lower.contains("कितने बजे")) {
            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            val reply = localized(intent.detectedLanguage,
                en = "It's currently $timeStr.",
                bn = "এখন সময় $timeStr।",
                hi = "अभी $timeStr बज रहे हैं।"
            )
            return "Offline • Clock" to ReasoningDecision("Checked device local clock", reply, emptyList())
        }

        if (lower.contains("date") || lower.contains("today") || lower.contains("তারিখ") || lower.contains("আজ") || lower.contains("तारीख") || lower.contains("आज")) {
            val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
            val reply = localized(intent.detectedLanguage,
                en = "Today is $dateStr.",
                bn = "আজ $dateStr।",
                hi = "आज $dateStr है।"
            )
            return "Offline • Calendar" to ReasoningDecision("Checked local calendar date", reply, emptyList())
        }

        if (lower.contains("battery") || lower.contains("চার্জ") || lower.contains("ব্যাটারি") || lower.contains("बैटरी")) {
            val bat = mediaController.batterySummary()
            val reply = localized(intent.detectedLanguage,
                en = "Your battery level is $bat.",
                bn = "আপনার ফোনের ব্যাটারি এখন $bat।",
                hi = "आपके फोन की बैटरी अभी $bat है।"
            )
            return "Offline • Telemetry" to ReasoningDecision("Read battery hardware telemetry", reply, emptyList())
        }

        if (lower.contains("storage") || lower.contains("ram") || lower.contains("free space") ||
            lower.contains("স্টোরেজ") || lower.contains("মেমোরি") || lower.contains("র‍্যাম")
        ) {
            val stats = readMemoryAndStorageSummary()
            val reply = localized(intent.detectedLanguage,
                en = stats,
                bn = "ফোনের বর্তমান অবস্থা: $stats।",
                hi = "फ़ोन की स्थिति: $stats।"
            )
            return "Offline • Telemetry" to ReasoningDecision("Read storage & RAM telemetry", reply, emptyList())
        }

        // 4. Unit conversion & Percentage & Chained Math
        tryUnitConversion(trimmed)?.let { convResult ->
            val reply = localized(intent.detectedLanguage,
                en = convResult,
                bn = "হিসাব অনুযায়ী: $convResult।",
                hi = "गणना के अनुसार: $convResult।"
            )
            return "Offline • Converter" to ReasoningDecision("Offline unit conversion", reply, emptyList())
        }

        tryEvaluatePercentage(trimmed)?.let { pctResult ->
            val reply = localized(intent.detectedLanguage,
                en = "The result is $pctResult.",
                bn = "শতাংশের হিসাব অনুযায়ী উত্তর হলো $pctResult।",
                hi = "प्रतिशत के अनुसार उत्तर $pctResult है।"
            )
            return "Offline • Calculator" to ReasoningDecision("Offline percentage calculation", reply, emptyList())
        }

        tryEvaluateExpression(trimmed)?.let { resultStr ->
            val reply = localized(intent.detectedLanguage,
                en = "The answer is $resultStr.",
                bn = "উত্তর হলো $resultStr।",
                hi = "इसका उत्तर $resultStr है।"
            )
            return "Offline • Calculator" to ReasoningDecision("Evaluated arithmetic expression offline", reply, emptyList())
        }

        // 5. Friendly multilingual greetings / identity
        if (lower in setOf(
                "hi", "hello", "hey", "hey ultra", "ultra", "jarvis", "hey jarvis",
                "হ্যালো", "হাই", "কেমন আছো", "কেমন আছেন", "नमस्ते", "हेलो", "कैसे हो"
            ) || lower.contains("who are you") || lower.contains("what can you do") || lower.contains("how are you")
        ) {
            val reply = localized(intent.detectedLanguage,
                en = "Hey! I'm ULTRA AI in Offline Mode right now. Even without internet, I can open apps, control volume and flashlight, set reminders, inspect screens, read notifications, convert units, and do math. Connect to Online Mode anytime for full ChatGPT-level reasoning and autonomous multi-step control.",
                bn = "হ্যালো! আমি ULTRA AI, এখন অফলাইন মোডে আছি। ইন্টারনেট ছাড়াই আমি অ্যাপ খোলা, ভলিউম ও ফ্ল্যাশলাইট কন্ট্রোল, রিমাইন্ডার সেট, স্ক্রিন কন্ট্রোল, নোটিফিকেশন পড়া এবং হিসাব-নিকাশ করতে পারি। সম্পূর্ণ ChatGPT ও অটোনোমাস ব্রেইন ব্যবহার করতে Online Mode চালু করুন।",
                hi = "नमस्ते! मैं ULTRA AI हूँ और अभी ऑफलाइन मोड में हूँ। बिना इंटरनेट के भी मैं ऐप्स खोल सकता हूँ, वॉल्यूम और फ्लैशलाइट कंट्रोल कर सकता हूँ, रिमाइंडर सेट कर सकता हूँ और गणित हल कर सकता हूँ। पूर्ण ChatGPT बुद्धि के लिए ऑनलाइन मोड चालू करें।"
            )
            return "Offline • Local Assistant" to ReasoningDecision("Offline greeting & capability summary", reply, emptyList())
        }

        // 6. Honest disclosure for open-ended cloud knowledge queries
        val honestOfflineReply = localized(intent.detectedLanguage,
            en = "I'm currently in Offline Mode, so open-ended cloud reasoning isn't active. Tap Online Mode or check your internet connection for full AI answers — or ask me to launch apps, adjust volume, toggle flashlight, read notifications, set reminders, or calculate.",
            bn = "আমি এখন অফলাইন মোডে আছি, তাই ইন্টারনেট ছাড়া গভীর বা ওপেন-এন্ডেড প্রশ্নের উত্তর দেওয়া যাচ্ছে না। উপরে থাকা মোড বাটনে ট্যাপ করে Online Mode চালু করুন অথবা অ্যাপ খোলা, ভলিউম, ফ্ল্যাশলাইট বা রিমাইন্ডারের মতো লোকাল কমান্ড দিন।",
            hi = "मैं अभी ऑफलाइन मोड में हूँ, इसलिए बिना इंटरनेट के जटिल सवालों का जवाब नहीं दे सकता। ऊपर दिए गए मोड बटन से Online Mode चुनें या ऐप्स खोलने, वॉल्यूम, फ्लैशलाइट या रिमाइंडर जैसे कमांड दें।"
        )
        return "Offline • Local Assistant" to ReasoningDecision(
            thoughtSummary = "Offline mode active — local rule engine cannot answer open-ended cloud knowledge",
            directReply = honestOfflineReply,
            plannedTools = emptyList()
        )
    }

    private fun readMemoryAndStorageSummary(): String {
        return runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            val availRamGb = mi.availMem / (1024.0 * 1024.0 * 1024.0)
            val totalRamGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)

            val stat = StatFs(Environment.getDataDirectory().path)
            val freeStorageGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
            val totalStorageGb = (stat.blockCountLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
            String.format(
                Locale.US,
                "RAM: %.1f GB free of %.1f GB | Storage: %.1f GB free of %.1f GB",
                availRamGb, totalRamGb, freeStorageGb, totalStorageGb
            )
        }.getOrElse { "Unable to read storage/RAM telemetry." }
    }

    private fun tryEvaluatePercentage(input: String): String? {
        val m1 = Regex("(-?\\d+(?:\\.\\d+)?)\\s*%\\s*(?:of|এর|का)\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(input)
        if (m1 != null) {
            val pct = m1.groupValues[1].toDoubleOrNull() ?: return null
            val base = m1.groupValues[2].toDoubleOrNull() ?: return null
            return formatNumber(base * (pct / 100.0))
        }
        val m2 = Regex("(-?\\d+(?:\\.\\d+)?)\\s*(?:এর|का)\\s*(-?\\d+(?:\\.\\d+)?)\\s*%", RegexOption.IGNORE_CASE).find(input)
        if (m2 != null) {
            val base = m2.groupValues[1].toDoubleOrNull() ?: return null
            val pct = m2.groupValues[2].toDoubleOrNull() ?: return null
            return formatNumber(base * (pct / 100.0))
        }
        return null
    }

    private fun tryUnitConversion(input: String): String? {
        val match = Regex(
            "(-?\\d+(?:\\.\\d+)?)\\s*(km|kilometers?|miles?|mi|c|celsius|f|fahrenheit|kg|kilograms?|lb|lbs|pounds?|gb|mb|cm|m|meters?|min|minutes?|sec|seconds?)\\s+(?:to|in|into|থেকে)\\s+(km|kilometers?|miles?|mi|c|celsius|f|fahrenheit|kg|kilograms?|lb|lbs|pounds?|gb|mb|cm|m|meters?|min|minutes?|sec|seconds?)",
            RegexOption.IGNORE_CASE
        ).find(input) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val from = normalizeUnit(match.groupValues[2])
        val to = normalizeUnit(match.groupValues[3])
        val converted: Double? = when (from to to) {
            "km" to "mi" -> value * 0.621371
            "mi" to "km" -> value / 0.621371
            "c" to "f" -> (value * 9.0 / 5.0) + 32.0
            "f" to "c" -> (value - 32.0) * 5.0 / 9.0
            "kg" to "lb" -> value * 2.20462
            "lb" to "kg" -> value / 2.20462
            "gb" to "mb" -> value * 1024.0
            "mb" to "gb" -> value / 1024.0
            "cm" to "m" -> value / 100.0
            "m" to "cm" -> value * 100.0
            "min" to "sec" -> value * 60.0
            "sec" to "min" -> value / 60.0
            else -> null
        }
        return converted?.let { "${formatNumber(value)} $from = ${formatNumber(it)} $to" }
    }

    private fun normalizeUnit(raw: String): String = when (raw.lowercase()) {
        "kilometer", "kilometers", "km" -> "km"
        "mile", "miles", "mi" -> "mi"
        "c", "celsius" -> "c"
        "f", "fahrenheit" -> "f"
        "kg", "kilogram", "kilograms" -> "kg"
        "lb", "lbs", "pound", "pounds" -> "lb"
        "gb" -> "gb"
        "mb" -> "mb"
        "cm" -> "cm"
        "m", "meter", "meters" -> "m"
        "min", "minute", "minutes" -> "min"
        "sec", "second", "seconds" -> "sec"
        else -> raw.lowercase()
    }

    private fun tryEvaluateExpression(input: String): String? {
        val cleaned = input
            .replace(Regex("(what is|calculate|solve|compute|equals|=|\\?)", RegexOption.IGNORE_CASE), " ")
            .replace("plus", "+", ignoreCase = true)
            .replace("minus", "-", ignoreCase = true)
            .replace("multiplied by", "*", ignoreCase = true)
            .replace("times", "*", ignoreCase = true)
            .replace("divided by", "/", ignoreCase = true)
            .replace("into", "*", ignoreCase = true)
            .replace("×", "*")
            .replace("÷", "/")
            .replace(Regex("\\s+x\\s+", RegexOption.IGNORE_CASE), " * ")
            .trim()
        if (!cleaned.any { it.isDigit() } || !cleaned.any { it in charArrayOf('+', '-', '*', '/') }) {
            return null
        }
        if (!cleaned.all { it.isDigit() || it in charArrayOf('+', '-', '*', '/', '(', ')', '.', ' ') }) {
            return null
        }
        return runCatching {
            val parser = object {
                var pos = -1
                var ch = 0

                fun nextChar() {
                    ch = if (++pos < cleaned.length) cleaned[pos].code else -1
                }

                fun eat(charToEat: Int): Boolean {
                    while (ch == ' '.code) nextChar()
                    if (ch == charToEat) {
                        nextChar()
                        return true
                    }
                    return false
                }

                fun parse(): Double {
                    nextChar()
                    val x = parseExpression()
                    if (pos < cleaned.length) throw IllegalArgumentException("Unexpected char")
                    return x
                }

                fun parseExpression(): Double {
                    var x = parseTerm()
                    while (true) {
                        x = when {
                            eat('+'.code) -> x + parseTerm()
                            eat('-'.code) -> x - parseTerm()
                            else -> return x
                        }
                    }
                }

                fun parseTerm(): Double {
                    var x = parseFactor()
                    while (true) {
                        x = when {
                            eat('*'.code) -> x * parseFactor()
                            eat('/'.code) -> {
                                val d = parseFactor()
                                if (d == 0.0) throw ArithmeticException("Division by zero")
                                x / d
                            }
                            else -> return x
                        }
                    }
                }

                fun parseFactor(): Double {
                    if (eat('+'.code)) return parseFactor()
                    if (eat('-'.code)) return -parseFactor()
                    val x: Double
                    val startPos = pos
                    if (eat('('.code)) {
                        x = parseExpression()
                        if (!eat(')'.code)) throw IllegalArgumentException("Missing ')'")
                    } else if (ch in '0'.code..'9'.code || ch == '.'.code) {
                        while (ch in '0'.code..'9'.code || ch == '.'.code) nextChar()
                        x = cleaned.substring(startPos, pos).toDouble()
                    } else {
                        throw IllegalArgumentException("Unexpected factor")
                    }
                    return x
                }
            }
            val value = parser.parse()
            if (value.isNaN() || value.isInfinite()) null else formatNumber(value)
        }.getOrNull()
    }

    private fun formatNumber(value: Double): String {
        return if (kotlin.math.abs(value - value.toLong()) < 1e-9) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }
    }

    private fun localized(lang: String, en: String, bn: String, hi: String): String = when (lang) {
        "bn" -> bn
        "hi" -> hi
        else -> en
    }
}
