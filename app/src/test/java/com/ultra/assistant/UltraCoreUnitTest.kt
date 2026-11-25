package com.ultra.assistant

import com.ultra.assistant.brain.IntentCategory
import com.ultra.assistant.brain.IntentEngine
import com.ultra.assistant.brain.ResultVerifier
import com.ultra.assistant.commands.CommandParser
import com.ultra.assistant.memory.PrivacyGuard
import com.ultra.assistant.tools.PermissionRequirement
import com.ultra.assistant.tools.ToolExecutionResult
import com.ultra.assistant.voice.LanguageManager
import com.ultra.assistant.voice.SentenceStreamExtractor
import com.ultra.assistant.voice.VoiceAudioMath
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
    fun `language manager maps Bengali Hindi English Auto and code-switching`() {
        assertEquals(Locale.forLanguageTag("bn-BD"), LanguageManager.locale("বাংলা"))
        assertEquals("bn-IN", LanguageManager.speechRecognizerTag("bn"))
        assertEquals(Locale.forLanguageTag("hi-IN"), LanguageManager.locale("हिन्दी"))
        assertEquals("hi-IN", LanguageManager.speechRecognizerTag("hi"))
        assertEquals(Locale.US, LanguageManager.locale("English"))
        assertEquals("en-US", LanguageManager.speechRecognizerTag("English"))

        assertTrue(LanguageManager.isAutoLanguage("Auto"))
        assertTrue(LanguageManager.isAutoLanguage("Auto (BN/HI/EN)"))
        assertFalse(LanguageManager.isAutoLanguage("English"))
        assertEquals("বাংলা", LanguageManager.detectScriptLanguageTag("আমার নাম রফি", "Auto"))
        assertEquals("हिन्दी", LanguageManager.detectScriptLanguageTag("मेरा नाम अमन", "Auto"))

        val segments = LanguageManager.splitCodeSwitchedSegments(
            "আমি এখন Settings open করছি।",
            defaultSetting = "Auto"
        )
        assertTrue(segments.any { it.first.language == "bn" })
        assertTrue(segments.any { it.first.language == "en" })
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

    @Test
    fun `intent engine detects multilingual stop commands and extracts user name only`() {
        assertTrue(IntentEngine.isStopCommand("stop"))
        assertTrue(IntentEngine.isStopCommand("Stop speaking"))
        assertTrue(IntentEngine.isStopCommand("থামো"))
        assertTrue(IntentEngine.isStopCommand("চুপ করো"))
        assertTrue(IntentEngine.isStopCommand("रुको"))
        assertFalse(IntentEngine.isStopCommand("open youtube"))

        assertEquals("Rafi", IntentEngine.extractUserNameDeclaration("My name is Rafi"))
        assertEquals("তানভীর", IntentEngine.extractUserNameDeclaration("আমার নাম তানভীর"))
        assertEquals("अमन", IntentEngine.extractUserNameDeclaration("मेरा नाम अमन है"))
        assertEquals(null, IntentEngine.extractUserNameDeclaration("My name is something very long that is definitely a sentence"))
    }

    @Test
    fun `voice audio math computes RMS dBFS clipping thresholds and wav header`() {
        val silence = ShortArray(320) { 0 }
        assertEquals(0f, VoiceAudioMath.computeRms(silence), 0.01f)
        assertEquals(-60f, VoiceAudioMath.computeDbfs(0f), 0.01f)
        assertFalse(VoiceAudioMath.detectClipping(silence))

        val tone = ShortArray(320) { 1000 }
        assertEquals(1000f, VoiceAudioMath.computeRms(tone), 0.1f)
        val visual = VoiceAudioMath.rmsToVisualLevel(1000f)
        assertTrue(visual in 0f..12f)

        val clipped = ShortArray(10) { i -> if (i < 4) 32500 else 100 }
        assertTrue(VoiceAudioMath.detectClipping(clipped))

        val idleGate = VoiceAudioMath.computeAdaptiveThreshold(150f, assistantSpeaking = false, echoCancelerActive = false)
        val aecBargeGate = VoiceAudioMath.computeAdaptiveThreshold(150f, assistantSpeaking = true, echoCancelerActive = true)
        val noAecBargeGate = VoiceAudioMath.computeAdaptiveThreshold(150f, assistantSpeaking = true, echoCancelerActive = false)
        assertTrue(idleGate < aecBargeGate)
        assertTrue(aecBargeGate < noAecBargeGate)

        val wav = VoiceAudioMath.pcm16ToWavBytes(ByteArray(640))
        assertEquals(44 + 640, wav.size)
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        assertEquals('W'.code.toByte(), wav[8])
        assertEquals('A'.code.toByte(), wav[9])
        assertEquals('V'.code.toByte(), wav[10])
        assertEquals('E'.code.toByte(), wav[11])
    }

    @Test
    fun `sentence stream extractor emits completed sentences and flushes remainder`() {
        val extractor = SentenceStreamExtractor()
        val first = extractor.consumeDelta("Hello! How can I")
        assertEquals(listOf("Hello!"), first)

        val second = extractor.consumeDelta("Hello! How can I help you today? Let's")
        assertEquals(listOf("How can I help you today?"), second)

        val rem = extractor.flushRemainder("Hello! How can I help you today? Let's check.")
        assertEquals("Let's check.", rem)
        assertEquals("", extractor.flushRemainder("Hello! How can I help you today? Let's check."))
    }

    @Test
    fun `radial menu layout math snaps anchors to screen edges and opens semicircle inward`() {
        val leftAnchor = RadialMenuLayoutMath.computeAnchor(
            dotCenterX = 120f,
            dotCenterY = 50f,
            screenWidth = 1080f,
            screenHeight = 2400f,
            menuRadiusPx = 200f,
            edgeMarginPx = 30f,
            topSafePx = 60f,
            bottomSafePx = 80f
        )
        assertTrue(leftAnchor.isLeftEdge)
        assertTrue(leftAnchor.openRightward)
        assertEquals(30f, leftAnchor.centerX, 0.1f)
        assertEquals(260f, leftAnchor.centerY, 0.1f)

        val leftPositions = RadialMenuLayoutMath.computeItemPositions(
            items = RadialMenuLayoutMath.defaultItems,
            anchor = leftAnchor,
            orbitRadiusX = 180f,
            orbitRadiusY = 220f,
            rotationOffsetDeg = 0f
        )
        assertTrue(leftPositions.isNotEmpty())
        assertTrue(leftPositions.all { it.x >= leftAnchor.centerX - 25f })

        val rightAnchor = RadialMenuLayoutMath.computeAnchor(
            dotCenterX = 950f,
            dotCenterY = 1200f,
            screenWidth = 1080f,
            screenHeight = 2400f,
            menuRadiusPx = 200f,
            edgeMarginPx = 30f,
            topSafePx = 60f,
            bottomSafePx = 80f
        )
        assertFalse(rightAnchor.isLeftEdge)
        assertFalse(rightAnchor.openRightward)
        val rightPositions = RadialMenuLayoutMath.computeItemPositions(
            items = RadialMenuLayoutMath.defaultItems,
            anchor = rightAnchor,
            orbitRadiusX = 180f,
            orbitRadiusY = 220f,
            rotationOffsetDeg = 0f
        )
        assertTrue(rightPositions.all { it.x <= rightAnchor.centerX + 25f })
    }

    @Test
    fun `intent engine recognizes email actions horizontal scroll and screen aloud`() {
        assertEquals(IntentCategory.EMAIL_ACTION, intentEngine.classify("Read my email").category)
        assertEquals(IntentCategory.EMAIL_ACTION, intentEngine.classify("ইমেইল পড়ো").category)
        assertEquals(IntentCategory.EMAIL_ACTION, intentEngine.classify("Delete this email").category)
        assertEquals(IntentCategory.SCREEN_INSPECT, intentEngine.classify("Read screen aloud").category)
        assertEquals(IntentCategory.SCREEN_ACTION, intentEngine.classify("Swipe left").category)
    }
}
