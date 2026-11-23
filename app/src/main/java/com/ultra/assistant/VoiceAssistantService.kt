package com.ultra.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ultra.assistant.brain.AgentManager
import com.ultra.assistant.data.AiOperatingMode
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.data.NetworkMonitor
import com.ultra.assistant.permissions.PermissionManager
import com.ultra.assistant.voice.LanguageManager
import com.ultra.assistant.voice.VoiceAudioController
import com.ultra.assistant.voice.WakeWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VoiceAssistantService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.ultra.assistant.START_VOICE"
        const val ACTION_START_SINGLE_TURN = "com.ultra.assistant.START_SINGLE_TURN"
        const val ACTION_STOP = "com.ultra.assistant.STOP_VOICE"
        const val ACTION_INTERRUPT = "com.ultra.assistant.INTERRUPT_VOICE"
        const val ACTION_STOP_SPEAKING = "com.ultra.assistant.STOP_SPEAKING"
        const val ACTION_SPEAK_TEXT = "com.ultra.assistant.SPEAK_TEXT"
        const val ACTION_TEST_VOICE = "com.ultra.assistant.TEST_VOICE"
        const val ACTION_REFRESH_VOICE = "com.ultra.assistant.REFRESH_VOICE"
        const val ACTION_SHOW_DOT = "SHOW_DOT"
        const val ACTION_HIDE_DOT = "HIDE_DOT"
        const val EXTRA_SPEAK_TEXT = "text"

        const val BROADCAST_VOICE_STATE = "com.ultra.assistant.VOICE_STATE"
        const val BROADCAST_VOICE_TEXT = "com.ultra.assistant.VOICE_TEXT"
        const val BROADCAST_VOICE_PARTIAL = "com.ultra.assistant.VOICE_PARTIAL"
        const val BROADCAST_VOICE_LEVEL = "com.ultra.assistant.VOICE_LEVEL"
        const val BROADCAST_AGENT_REPLY = "com.ultra.assistant.AGENT_REPLY"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var currentState: String = "IDLE"
            private set
    }

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var speaking = false
    private var restarting = false
    private var destroyed = false
    private var aiRequestInProgress = false
    private var wakeActivated = false
    private var singleTurnOnly = false
    private var pendingSpeechText: String? = null
    private var activeFinalUtteranceId: String? = null
    private var consecutiveIdleErrors = 0
    private var floatingDot: FloatingDot? = null

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settingsRepository: LocalSettingsRepository
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var agentManager: AgentManager
    private lateinit var audioController: VoiceAudioController

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        settingsRepository = UltraRuntime.settingsRepository(this)
        networkMonitor = UltraRuntime.networkMonitor(this)
        agentManager = UltraRuntime.agentManager(this)
        audioController = VoiceAudioController(this)
        floatingDot = FloatingDot(this)
        createNotificationChannel()

        if (!PermissionManager.hasMicrophone(this)) {
            isRunning = false
            currentState = "IDLE"
            stopSelf()
            return
        }

        val fgsStarted = runCatching {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            ServiceCompat.startForeground(this, 31, createNotification(), fgsType)
        }.isSuccess

        if (!fgsStarted) {
            isRunning = false
            currentState = "IDLE"
            stopSelf()
            return
        }

        isRunning = true
        runCatching { tts = TextToSpeech(this, this) }
        if (settingsRepository.floatingAssistantEnabled() && PermissionManager.hasOverlay(this)) {
            floatingDot?.show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // Allow overlay / speak preview / voice test cleanly
        if (action == ACTION_SHOW_DOT) {
            if (PermissionManager.hasOverlay(this)) {
                floatingDot?.show()
                floatingDot?.updateState(currentState)
            }
            return START_STICKY
        }

        if (action == ACTION_HIDE_DOT) {
            floatingDot?.hide()
            return START_STICKY
        }

        if (action == ACTION_STOP) {
            stopVoiceService()
            return START_NOT_STICKY
        }

        if (action == ACTION_STOP_SPEAKING) {
            stopSpeakingOnly()
            return START_STICKY
        }

        if (action == ACTION_SPEAK_TEXT) {
            val text = intent.getStringExtra(EXTRA_SPEAK_TEXT).orEmpty()
            if (text.isNotBlank()) {
                singleTurnOnly = true
                speak(text)
            }
            return START_STICKY
        }

        if (action == ACTION_TEST_VOICE) {
            singleTurnOnly = true
            val preview = when (LanguageManager.speechRecognizerTag(settingsRepository.language())) {
                "bn-IN" -> "হ্যালো! আমি আল্ট্রা এআই। আমার কণ্ঠস্বর এখন সক্রিয়।"
                "hi-IN" -> "नमस्ते! मैं अल्ट्रा एआई हूँ। मेरी आवाज़ अब तैयार है।"
                else -> "Hello! I am ULTRA AI. This is how I sound in conversation."
            }
            speak(preview)
            return START_STICKY
        }

        if (action == ACTION_REFRESH_VOICE) {
            applyVoiceSettings()
            return START_STICKY
        }

        if (!PermissionManager.hasMicrophone(this)) {
            stopVoiceService()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_INTERRUPT -> {
                singleTurnOnly = !settingsRepository.continuousListening()
                interruptSpeakingAndListen()
            }
            ACTION_START_SINGLE_TURN -> {
                singleTurnOnly = true
                consecutiveIdleErrors = 0
                if (!listening && !aiRequestInProgress) {
                    if (speaking) {
                        interruptSpeakingAndListen()
                    } else {
                        startListening()
                    }
                }
            }
            ACTION_START, null -> {
                singleTurnOnly = !settingsRepository.continuousListening()
                consecutiveIdleErrors = 0
                if (!listening && !speaking && !aiRequestInProgress && isRunning) {
                    startListening()
                }
            }
        }
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ttsReady = true
        applyVoiceSettings()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (activeFinalUtteranceId == null || id == activeFinalUtteranceId) {
                    handler.post { finishSpeaking() }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                handler.post { finishSpeaking() }
            }

            override fun onError(id: String?, errorCode: Int) {
                handler.post { finishSpeaking() }
            }
        })
        val queued = pendingSpeechText
        if (!queued.isNullOrBlank()) {
            pendingSpeechText = null
            speak(queued)
        }
    }

    private fun applyVoiceSettings() {
        val engine = tts ?: return
        val effectiveOffline = isEffectiveOffline()
        audioController.configureTts(
            tts = engine,
            languageSetting = settingsRepository.language(),
            gender = settingsRepository.voiceGender(),
            speed = settingsRepository.voiceSpeed(),
            userPitch = settingsRepository.voicePitch(),
            preferOffline = effectiveOffline,
            explicitVoiceName = settingsRepository.voiceName()
        )
    }

    private fun isEffectiveOffline(): Boolean {
        return when (settingsRepository.operatingMode()) {
            AiOperatingMode.OFFLINE -> true
            AiOperatingMode.ONLINE -> false
            AiOperatingMode.AUTO -> !networkMonitor.isOnline.value
        }
    }

    private fun interruptSpeakingAndListen() {
        runCatching { tts?.stop() }
        audioController.abandonSpeechFocus()
        speaking = false
        activeFinalUtteranceId = null
        aiRequestInProgress = false
        wakeActivated = true
        consecutiveIdleErrors = 0
        sendVoiceState("LISTENING")
        startListening()
    }

    private fun stopSpeakingOnly() {
        runCatching { tts?.stop() }
        audioController.abandonSpeechFocus()
        speaking = false
        activeFinalUtteranceId = null
        sendVoiceState("IDLE")
    }

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendVoiceState("OFFLINE", "Speech recognizer unavailable")
            return
        }
        runCatching { recognizer?.destroy() }
        recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(this) }.getOrNull()
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!isRunning) return
                listening = true
                sendVoiceState("LISTENING")
            }

            override fun onBeginningOfSpeech() {
                if (!isRunning) return
                listening = true
                consecutiveIdleErrors = 0
                if (speaking) {
                    runCatching { tts?.stop() }
                    audioController.abandonSpeechFocus()
                    speaking = false
                }
                sendVoiceState("LISTENING")
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (isRunning) sendVoiceLevel(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (!isRunning) return
                listening = false
                sendVoiceState("PROCESSING")
            }

            override fun onError(error: Int) {
                if (!isRunning) return
                listening = false
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    stopVoiceService()
                    return
                }
                sendVoiceState("IDLE")

                if (singleTurnOnly || !settingsRepository.continuousListening()) {
                    return
                }

                // Smart exponential backoff avoids repetitive recognizer ting-ting beeps during quiet idle gaps
                consecutiveIdleErrors += 1
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    runCatching {
                        recognizer?.cancel()
                        recognizer?.destroy()
                    }
                    recognizer = null
                    restartListening(1600L)
                    return
                }

                val delay = when (consecutiveIdleErrors) {
                    1 -> 900L
                    2 -> 1800L
                    3 -> 2800L
                    else -> 4000L
                }
                if (consecutiveIdleErrors <= 8) {
                    restartListening(delay)
                }
            }

            override fun onResults(results: Bundle?) {
                if (!isRunning) return
                listening = false
                consecutiveIdleErrors = 0
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (spoken.isBlank()) {
                    sendVoiceState("IDLE")
                    if (!singleTurnOnly && settingsRepository.continuousListening()) {
                        restartListening(850L)
                    }
                } else {
                    sendRecognizedText(spoken)
                    processSpokenTranscript(spoken)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isRunning) return
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (partial.isNotBlank()) {
                    sendPartialText(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (!isRunning || destroyed || speaking || listening || aiRequestInProgress) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        if (recognizer == null) setupRecognizer()

        val language = LanguageManager.speechRecognizerTag(settingsRepository.language())
        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1750L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1150L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
            if (isEffectiveOffline()) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        try {
            recognizer?.startListening(speechIntent)
            listening = true
            sendVoiceState("LISTENING")
        } catch (_: Exception) {
            listening = false
            if (!singleTurnOnly && settingsRepository.continuousListening()) {
                restartListening(1500L)
            }
        }
    }

    private fun processSpokenTranscript(spoken: String) {
        val wakePhrase = settingsRepository.wakePhrase()
        val hasWake = WakeWordEngine.detected(spoken, wakePhrase)
        val commandText = if (hasWake) {
            WakeWordEngine.strip(spoken, wakePhrase)
        } else if (wakeActivated) {
            wakeActivated = false
            spoken
        } else {
            spoken
        }

        if (hasWake && commandText.isBlank()) {
            wakeActivated = true
            val ack = when (LanguageManager.speechRecognizerTag(settingsRepository.language())) {
                "bn-IN" -> "জি, বলুন। আমি শুনছি।"
                "hi-IN" -> "जी, बोलिए। मैं सुन रहा हूँ।"
                else -> "Yes, I am listening."
            }
            speak(ack)
            return
        }

        aiRequestInProgress = true
        sendVoiceState("PROCESSING", commandText)
        serviceScope.launch {
            val report = runCatching {
                agentManager.handleUserCommand(commandText)
            }.getOrNull()

            aiRequestInProgress = false
            if (!isRunning || destroyed) return@launch

            val reply = report?.finalReply?.takeIf { it.isNotBlank() }
                ?: "I couldn't process that command right now."
            sendAgentReply(reply)
            speak(reply)
        }
    }

    private fun speak(text: String) {
        if (!isRunning || destroyed) return
        val engine = tts
        if (!ttsReady || engine == null) {
            pendingSpeechText = text
            return
        }
        val clean = audioController.sanitizeForSpeech(text)
        if (clean.isBlank()) {
            sendVoiceState("IDLE")
            if (!singleTurnOnly && settingsRepository.continuousListening()) {
                restartListening(750L)
            }
            return
        }

        applyVoiceSettings()
        audioController.requestSpeechFocus()
        speaking = true
        listening = false
        sendVoiceState("SPEAKING", clean)

        val lastId = audioController.speakNaturally(
            tts = engine,
            rawText = clean,
            volume = settingsRepository.voiceVolume()
        )
        activeFinalUtteranceId = lastId
        if (lastId == null) {
            finishSpeaking()
        }
    }

    private fun finishSpeaking() {
        speaking = false
        activeFinalUtteranceId = null
        audioController.abandonSpeechFocus()
        sendVoiceState("IDLE")
        if (!singleTurnOnly && settingsRepository.continuousListening()) {
            restartListening(550L)
        }
    }

    private fun restartListening(delay: Long) {
        if (!isRunning || destroyed || restarting || aiRequestInProgress) return
        restarting = true
        handler.postDelayed({
            restarting = false
            if (isRunning && !destroyed && !speaking && !listening && !aiRequestInProgress) {
                startListening()
            }
        }, delay)
    }

    private fun sendVoiceState(state: String, snippet: String? = null) {
        currentState = state
        floatingDot?.updateState(state, snippet)
        if (!isRunning) return
        sendBroadcast(Intent(BROADCAST_VOICE_STATE).setPackage(packageName).putExtra("state", state))
    }

    private fun sendRecognizedText(text: String) {
        floatingDot?.updateState("PROCESSING", text)
        if (!isRunning) return
        sendBroadcast(Intent(BROADCAST_VOICE_TEXT).setPackage(packageName).putExtra("text", text))
    }

    private fun sendPartialText(text: String) {
        floatingDot?.updateState("LISTENING", text)
        if (!isRunning) return
        sendBroadcast(Intent(BROADCAST_VOICE_PARTIAL).setPackage(packageName).putExtra("text", text))
    }

    private fun sendVoiceLevel(level: Float) {
        if (!isRunning) return
        sendBroadcast(Intent(BROADCAST_VOICE_LEVEL).setPackage(packageName).putExtra("level", level))
    }

    private fun sendAgentReply(reply: String) {
        floatingDot?.updateState("SPEAKING", reply)
        if (!isRunning) return
        sendBroadcast(Intent(BROADCAST_AGENT_REPLY).setPackage(packageName).putExtra("reply", reply))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ultra_assistant",
                "ULTRA AI Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openPending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceAssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "ultra_assistant")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ULTRA AI Voice Ready")
            .setContentText("Natural conversation mode (${settingsRepository.voiceGender().label})")
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun stopVoiceService() {
        isRunning = false
        currentState = "IDLE"
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        floatingDot?.hide()
        audioController.abandonSpeechFocus()
        runCatching {
            recognizer?.cancel()
            recognizer?.destroy()
        }
        recognizer = null
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ttsReady = false
        listening = false
        speaking = false
        restarting = false
        aiRequestInProgress = false
        serviceScope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        currentState = "IDLE"
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        floatingDot?.hide()
        audioController.abandonSpeechFocus()
        runCatching {
            recognizer?.cancel()
            recognizer?.destroy()
        }
        recognizer = null
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
