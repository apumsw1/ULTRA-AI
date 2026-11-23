package com.ultra.assistant

import com.ultra.assistant.brain.IntentCategory
import com.ultra.assistant.brain.IntentEngine
import com.ultra.assistant.brain.ResultVerifier
import com.ultra.assistant.commands.CommandParser
import com.ultra.assistant.memory.PrivacyGuard
import com.ultra.assistant.tools.PermissionRequirement
import com.ultra.assistant.tools.ToolExecutionResult
import com.ultra.assistant.voice.LanguageManager
import com.ultra.assistant.voice.WakeWordEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class UltraCoreUnitTest {

    private val intentEngine = IntentEngine()
    private val verifier = ResultVerifier()

    @Test
    fun `intent engine classifies multilingual and device control commands`() {
        val openEn = intentEngine.classify("Open YouTube")
        assertEquals(IntentCategory.APP_OPEN, openEn.category)
        assertEquals("en", openEn.detectedLanguage)

        val ytSearch = intentEngine.classify("Search YouTube for Jetpack Compose")
        assertEquals(IntentCategory.YOUTUBE_SEARCH, ytSearch.category)

        val volCtrl = intentEngine.classify("Turn volume up")
        assertEquals(IntentCategory.DEVICE_CONTROL, volCtrl.category)

        val wifiSettings = intentEngine.classify("Open Wi-Fi settings")
        assertEquals(IntentCategory.DEVICE_CONTROL, wifiSettings.category)

        val callAction = intentEngine.classify("Call Mom")
        assertEquals(IntentCategory.PHONE_CALL, callAction.category)

        val lockBn = intentEngine.classify("ফোন লক করো")
        assertEquals(IntentCategory.DEVICE_CONTROL, lockBn.category)
        assertEquals("bn", lockBn.detectedLanguage)

        val remindHi = intentEngine.classify("रिमाइंडर सेट करो")
        assertEquals(IntentCategory.REMINDER, remindHi.category)
        assertEquals("hi", remindHi.detectedLanguage)

        val smsEn = intentEngine.classify("Prepare SMS to 5551234 saying hello")
        assertEquals(IntentCategory.SMS_PREPARE, smsEn.category)
    }

    @Test
    fun `wake word engine detects and strips default and custom phrases`() {
        assertTrue(WakeWordEngine.detected("Hey Ultra open settings"))
        assertEquals("open settings", WakeWordEngine.strip("Hey Ultra open settings"))

        assertTrue(WakeWordEngine.detected("Jarvis check notifications", customPhrase = "Jarvis"))
        assertEquals("check notifications", WakeWordEngine.strip("Jarvis check notifications", customPhrase = "Jarvis"))

        assertTrue(WakeWordEngine.detected("হে আল্ট্রা গান চালাও"))
    }

    @Test
    fun `privacy guard redacts sensitive OTPs keys and blocks private sources`() {
        val otpText = "Your verification code is 492817"
        assertTrue(PrivacyGuard.containsSensitiveData(otpText))
        assertFalse(PrivacyGuard.redact(otpText).contains("492817"))

        assertFalse(PrivacyGuard.isEligibleForAutoMemory("sms", "Meet at 5pm"))
        assertFalse(PrivacyGuard.isEligibleForAutoMemory("notification", "Order shipped"))
        assertFalse(PrivacyGuard.isEligibleForAutoMemory("chat", "My token: sk-abcdefghijklmnop12345"))
        assertTrue(PrivacyGuard.isEligibleForAutoMemory("chat", "User prefers dark HUD theme"))
    }

    @Test
    fun `language manager maps Bengali Hindi and English locales`() {
        assertEquals(Locale.forLanguageTag("bn-BD"), LanguageManager.locale("বাংলা"))
        assertEquals("bn-IN", LanguageManager.speechRecognizerTag("bn"))
        assertEquals(Locale.forLanguageTag("hi-IN"), LanguageManager.locale("हिन्दी"))
        assertEquals("hi-IN", LanguageManager.speechRecognizerTag("hi"))
        assertEquals(Locale.US, LanguageManager.locale("English"))
        assertEquals("en-US", LanguageManager.speechRecognizerTag("English"))
    }

    @Test
    fun `command parser detects search and next song phrases`() {
        assertTrue(CommandParser.isSearch("search latest news"))
        assertTrue(CommandParser.isSearch("সার্চ করো আবহাওয়া"))
        assertTrue(CommandParser.isNext("পরের গান চালাও"))
        assertFalse(CommandParser.isNext("open calculator"))
    }

    @Test
    fun `result verifier highlights missing permission clearly`() {
        val summary = verifier.verify(
            decisionReply = "Checking screen",
            results = listOf(
                ToolExecutionResult(
                    toolId = "get_accessible_screen",
                    success = false,
                    summary = "Accessibility Service is not enabled.",
                    missingPermission = PermissionRequirement.ACCESSIBILITY
                )
            )
        )
        assertFalse(summary.allSucceeded)
        assertEquals(PermissionRequirement.ACCESSIBILITY, summary.missingPermission)
        assertTrue(summary.userReply.contains("Accessibility Service"))
    }

    @Test
    fun `intent engine normalizes noisy stt and detects compound multi-step actions`() {
        assertEquals("open YouTube", IntentEngine.normalizeSpeechTranscript("open u tube"))
        assertEquals("open wifi settings", IntentEngine.normalizeSpeechTranscript("open wi fi settings"))
        assertTrue(IntentEngine.isCompoundAction("Open YouTube and search relaxing piano music"))
        assertTrue(IntentEngine.isCompoundAction("YouTube খুলে গান সার্চ করো"))
        assertFalse(IntentEngine.isCompoundAction("Open YouTube"))
        assertEquals("bn", IntentEngine.detectLanguage("kemon acho tui?"))
    }
}
