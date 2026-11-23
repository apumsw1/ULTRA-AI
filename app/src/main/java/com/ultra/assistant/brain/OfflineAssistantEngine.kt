package com.ultra.assistant.brain

import android.content.Context
import com.ultra.assistant.control.MediaVolumeController
import com.ultra.assistant.memory.MemoryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Genuine on-device Offline Assistant Engine.
 * Handles local commands, reminders, device info, time/date, basic arithmetic,
 * local memory recall/saving, and transparent offline capability messaging without faking LLM answers.
 */
class OfflineAssistantEngine(
    context: Context,
    private val memoryManager: MemoryManager
) {
    private val mediaController = MediaVolumeController(context.applicationContext)

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
                return "Offline • Local Memory" to ReasoningDecision("Saved local memory", reply, emptyList())
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

        // 3. Time, Date, Battery
        if (lower.contains("time") || lower.contains("সময়") || lower.contains("কটা বাজে") || lower.contains("समय") || lower.contains("कितने बजे")) {
            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            val reply = localized(intent.detectedLanguage,
                en = "It's currently $timeStr.",
                bn = "এখন সময় $timeStr।",
                hi = "अभी $timeStr बज रहे हैं।"
            )
            return "Offline • Clock" to ReasoningDecision("Local time check", reply, emptyList())
        }

        if (lower.contains("date") || lower.contains("today") || lower.contains("তারিখ") || lower.contains("আজ") || lower.contains("तारीख") || lower.contains("आज")) {
            val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
            val reply = localized(intent.detectedLanguage,
                en = "Today is $dateStr.",
                bn = "আজ $dateStr।",
                hi = "आज $dateStr है।"
            )
            return "Offline • Calendar" to ReasoningDecision("Local date check", reply, emptyList())
        }

        if (lower.contains("battery") || lower.contains("চার্জ") || lower.contains("ব্যাটারি") || lower.contains("बैटरी")) {
            val bat = mediaController.batterySummary()
            val reply = localized(intent.detectedLanguage,
                en = "Your battery level is $bat.",
                bn = "আপনার ফোনের ব্যাটারি এখন $bat।",
                hi = "आपके फोन की बैटरी अभी $bat है।"
            )
            return "Offline • Telemetry" to ReasoningDecision("Local battery check", reply, emptyList())
        }

        // 4. Local arithmetic evaluator (safe simple expressions)
        tryEvaluateSimpleMath(trimmed)?.let { resultStr ->
            val reply = localized(intent.detectedLanguage,
                en = "The answer is $resultStr.",
                bn = "উত্তর হলো $resultStr।",
                hi = "इसका उत्तर $resultStr है।"
            )
            return "Offline • Calculator" to ReasoningDecision("Local arithmetic evaluation", reply, emptyList())
        }

        // 5. Friendly multilingual greetings / identity
        if (lower in setOf("hi", "hello", "hey", "hey ultra", "ultra", "হ্যালো", "হাই", "কেমন আছো", "नमस्ते", "हेलो", "कैसे हो") ||
            lower.contains("who are you") || lower.contains("what can you do") || lower.contains("how are you")
        ) {
            val reply = localized(intent.detectedLanguage,
                en = "Hey! I'm ULTRA AI running in Offline Local Mode right now. I can open apps, control volume and flashlight, set reminders, read notifications, manage calls or drafts, and check device status without internet. Connect to the internet for full AI chat.",
                bn = "হ্যালো! আমি ULTRA AI, এখন অফলাইন লোকাল মোডে আছি। ইন্টারনেট ছাড়াই আমি অ্যাপ খোলা, ভলিউম ও ফ্ল্যাশলাইট কন্ট্রোল, রিমাইন্ডার সেট, নোটিফিকেশন পড়া এবং ডিভাইস কন্ট্রোল করতে পারি।",
                hi = "नमस्ते! मैं ULTRA AI हूँ और अभी ऑफलाइन लोकल मोड में काम कर रहा हूँ। बिना इंटरनेट के मैं ऐप्स खोल सकता हूँ, वॉल्यूम और फ्लैशलाइट नियंत्रित कर सकता हूँ, रिमाइंडर सेट कर सकता हूँ और नोटिफिकेशन पढ़ सकता हूँ।"
            )
            return "Offline • Local Assistant" to ReasoningDecision("Offline greeting & capability summary", reply, emptyList())
        }

        // 6. Honest disclosure for open-ended cloud knowledge queries
        val honestOfflineReply = localized(intent.detectedLanguage,
            en = "I'm currently in Offline Mode, so I don't have access to cloud AI reasoning for open-ended questions. You can connect to the internet or switch to Online Mode in Settings, or ask me to open apps, adjust volume, toggle flashlight, set reminders, or open device settings.",
            bn = "আমি এখন অফলাইন মোডে আছি, তাই ইন্টারনেট ছাড়া সাধারণ জ্ঞান বা গভীর প্রশ্নের উত্তর দিতে পারছি না। অনলাইন এআই ব্যবহার করতে ইন্টারনেট সংযোগ দিন, অথবা অ্যাপ খোলা, ভলিউম, ফ্ল্যাশলাইট বা রিমাইন্ডারের মতো লোকাল কমান্ড দিন।",
            hi = "मैं अभी ऑफलाइन मोड में हूँ, इसलिए बिना इंटरनेट के जटिल या सामान्य ज्ञान के सवालों का जवाब नहीं दे सकता। ऑनलाइन AI के लिए इंटरनेट कनेक्ट करें, या ऐप्स खोलने, वॉल्यूम, फ्लैशलाइट या रिमाइंडर जैसे लोकल कमांड दें।"
        )
        return "Offline • Local Assistant" to ReasoningDecision(
            thoughtSummary = "Offline mode active — cannot run cloud LLM inference",
            directReply = honestOfflineReply,
            plannedTools = emptyList()
        )
    }

    private fun tryEvaluateSimpleMath(input: String): String? {
        val cleaned = input
            .replace("what is", "", ignoreCase = true)
            .replace("calculate", "", ignoreCase = true)
            .replace("plus", "+", ignoreCase = true)
            .replace("minus", "-", ignoreCase = true)
            .replace("times", "*", ignoreCase = true)
            .replace("multiplied by", "*", ignoreCase = true)
            .replace("divided by", "/", ignoreCase = true)
            .replace("x", "*", ignoreCase = true)
            .replace("?", "")
            .trim()
        val match = Regex("^(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(-?\\d+(?:\\.\\d+)?)$").find(cleaned)
            ?: return null
        val a = match.groupValues[1].toDoubleOrNull() ?: return null
        val op = match.groupValues[2]
        val b = match.groupValues[3].toDoubleOrNull() ?: return null
        val res = when (op) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> if (b == 0.0) return null else a / b
            else -> return null
        }
        return if (res % 1.0 == 0.0) res.toLong().toString() else String.format(Locale.US, "%.2f", res)
    }

    private fun localized(lang: String, en: String, bn: String, hi: String): String = when (lang) {
        "bn" -> bn
        "hi" -> hi
        else -> en
    }
}
