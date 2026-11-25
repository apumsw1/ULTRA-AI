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
import com.google.firebase.FirebaseApp
import com.ultra.assistant.brain.AgentManager
import com.ultra.assistant.brain.IntentEngine
import com.ultra.assistant.control.ScreenController
import com.ultra.assistant.data.AiOperatingMode
import com.ultra.assistant.data.AiProviderMode
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.data.NetworkMonitor
import com.ultra.assistant.permissions.PermissionManager
import com.ultra.assistant.voice.AudioHardwareStatus
import com.ultra.assistant.voice.ContinuousAudioCaptureEngine
import com.ultra.assistant.voice.LanguageManager
import com.ultra.assistant.voice.SentenceStreamExtractor
import com.ultra.assistant.voice.VoiceAudioController
import com.ultra.assistant.voice.WakeWordEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class VoiceAssistantService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.ultra.assistant.START_VOICE"
        const val ACTION_START_SINGLE_TURN = "com.ultra.assistant.START_SINGLE_TURN"
        const val ACTION_PAUSE_SESSION = "com.ultra.assistant.PAUSE_SESSION"
        const val ACTION_STOP = "com.ultra.assistant.STOP_VOICE"
        const val ACTION_INTERRUPT = "com.ultra.assistant.INTERRUPT_VOICE"
        const val ACTION_CANCEL_AGENT = "com.ultra.assistant.CANCEL_AGENT"
        const val ACTION_STOP_SPEAKING = "com.ultra.assistant.STOP_SPEAKING"
        const val ACTION_SPEAK_TEXT = "com.ultra.assistant.SPEAK_TEXT"
        const val ACTION_TEST_VOICE = "com.ultra.assistant.TEST_VOICE"
        const val ACTION_REFRESH_VOICE = "com.ultra.assistant.REFRESH_VOICE"
        const val ACTION_SHOW_DOT = "SHOW_DOT"
        const val ACTION_HIDE_DOT = "HIDE_DOT"
        const val ACTION_SCAN_SCREEN = "com.ultra.assistant.SCAN_SCREEN"
        const val ACTION_READ_SCREEN = "com.ultra.assistant.READ_SCREEN"
        const val ACTION_SCROLL_SCREEN = "com.ultra.assistant.SCROLL_SCREEN"
        const val ACTION_SET_MODE_ONLINE = "com.ultra.assistant.SET_MODE_ONLINE"
        const val ACTION_SET_MODE_OFFLINE = "com.ultra.assistant.SET_MODE_OFFLINE"
        const val EXTRA_SPEAK_TEXT = "text"

        const val BROADCAST_VOICE_STATE = "com.ultra.assistant.VOICE_STATE"
        const val BROADCAST_VOICE_TEXT = "com.ultra.assistant.VOICE_TEXT"
        const val BROADCAST_VOICE_PARTIAL = "com.ultra.assistant.VOICE_PARTIAL"
        const val BROADCAST_VOICE_LEVEL = "com.ultra.assistant.VOICE_LEVEL"
        const val BROADCAST_AGENT_REPLY = "com.ultra.assistant.AGENT_REPLY"
        const val BROADCAST_AUDIO_STATUS = "com.ultra.assistant.AUDIO_STATUS"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isSessionListening = false
            private set

        @Volatile
        var currentState: String = "IDLE"
            private set

        @Volatile
        var latestHardwareStatus: AudioHardwareStatus = AudioHardwareStatus()
            private set

        private val _voiceLevelFlow = MutableStateFlow(0f)
        val voiceLevelFlow: StateFlow<Float> = _voiceLevelFlow.asStateFlow()
    }

    private var offlineRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @Volatile
    private var speaking = false

    @Volatile
    private var destroyed = false

    @Volatile
    private var aiRequestInProgress = false

    @Volatile
    private var streamingResponseInProgress = false

    @Volatile
    private var wakeActivated = false

    @Volatile
    private var singleTurnOnly = false

    private var pendingSpeechText: String? = null
    private var activeFinalUtteranceId: String? = null
    private val speechGenerationId = AtomicLong(0L)

    private var floatingDot: FloatingDot? = null
    private var captureEngine: ContinuousAudioCaptureEngine? = null
    private var activeAgentJob: Job? = null
    private var transcriptionJob: Job? = null
    private var neuralSpeechChannel: Channel<Pair<Long, String>>? = null
    private var neuralSpeechJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settingsRepository: LocalSettingsRepository
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var agentManager: AgentManager
    private lateinit var audioController: VoiceAudioController
    private lateinit var screenController: ScreenController

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        settingsRepository = UltraRuntime.settingsRepository(this)
        networkMonitor = UltraRuntime.networkMonitor(this)
        agentManager = UltraRuntime.agentManager(this)
        audioController = VoiceAudioController(this)
        screenController = ScreenController(this)
        floatingDot = FloatingDot(this)
        createNotificationChannel()

        if (!PermissionManager.hasMicrophone(this)) {
            isRunning = false
            isSessionListening = false
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
            isSessionListening = false
            currentState = "IDLE"
            stopSelf()
            return
        }

        isRunning = true
        runCatching { tts = TextToSpeech(this, this) }
        initAudioCaptureEngine()
        observeNetworkAndAgentPhases()

        if (settingsRepository.floatingAssistantEnabled() && PermissionManager.hasOverlay(this)) {
            floatingDot?.show()
        }
    }

    private fun observeNetworkAndAgentPhases() {
        serviceScope.launch {
            var wasOnline = networkMonitor.isOnline.value
            networkMonitor.isOnline.collect { nowOnline ->
                if (!isRunning || destroyed) return@collect
                val mode = settingsRepository.operatingMode()
                if (mode == AiOperatingMode.AUTO && isSessionListening) {
                    if (wasOnline && !nowOnline && !speaking && !aiRequestInProgress) {
                        sendVoiceState("OFFLINE")
                    } else if (!wasOnline && nowOnline && !speaking && !aiRequestInProgress) {
                        sendVoiceState("LISTENING")
                    }
                }
                wasOnline = nowOnline
            }
        }

        serviceScope.launch {
            agentManager.voicePhaseFlow.collect { phase ->
                if (!isRunning || destroyed || speaking) return@collect
                if (aiRequestInProgress && phase in setOf("THINKING", "EXECUTING_ACTION", "VERIFYING")) {
                    sendVoiceState(phase)
                }
            }
        }
    }

    private fun initAudioCaptureEngine() {
        captureEngine = ContinuousAudioCaptureEngine(
            context = this,
            onSpeechStart = {
                handler.post {
                    if (isRunning && !destroyed && isSessionListening && !speaking) {
                        sendVoiceState("USER_SPEAKING")
                    }
                }
            },
            onBargeInDetected = {
                handler.post {
                    if (isRunning && !destroyed && speaking) {
                        handleAcousticBargeIn()
                    }
                }
            },
            onUtteranceReady = { wavBytes ->
                handler.post {
                    if (isRunning && !destroyed && isSessionListening) {
                        handleCapturedUtterance(wavBytes)
                    }
                }
            },
            onAudioLevel = { visualLevel, _, _ ->
                if (isRunning && !destroyed) {
                    sendVoiceLevel(visualLevel)
                }
            },
            onHardwareStatus = { status ->
                latestHardwareStatus = status
                handler.post { sendAudioStatus(status) }
            },
            onCaptureError = { errMsg ->
                handler.post {
                    if (isRunning && !destroyed) {
                        sendVoiceState("ERROR", errMsg)
                    }
                }
            }
        ).apply {
            batteryConsciousMode = settingsRepository.batteryConsciousListening()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_SHOW_DOT) {
            if (PermissionManager.hasOverlay(this)) {
                floatingDot?.show()
                floatingDot?.updateState(
                    state = currentState,
                    isVoiceSessionActive = isSessionListening,
                    edgeGlowEnabled = settingsRepository.ambientEdgeGlowEnabled()
                )
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

        if (action == ACTION_PAUSE_SESSION) {
            pauseListeningAndSpeechKeepOverlay()
            return START_STICKY
        }

        if (action == ACTION_STOP_SPEAKING) {
            stopSpeakingOnly()
            return START_STICKY
        }

        if (action == ACTION_CANCEL_AGENT) {
            cancelActiveProcessingOnly()
            return START_STICKY
        }

        if (action == ACTION_SPEAK_TEXT) {
            val text = intent.getStringExtra(EXTRA_SPEAK_TEXT).orEmpty()
            if (text.isNotBlank()) {
                singleTurnOnly = !isSessionListening
                speak(text)
            }
            return START_STICKY
        }

        if (action == ACTION_TEST_VOICE) {
            singleTurnOnly = !isSessionListening
            val preview = when (LanguageManager.speechRecognizerTag(settingsRepository.language())) {
                "bn-IN" -> "হ্যালো! আমি আল্ট্রা এআই। আমার কণ্ঠস্বর এখন সক্রিয়।"
                "hi-IN" -> "नमस्ते! मैं अल्ट्रा एआई हूँ। मेरी आवाज़ अब तैयार है।"
                else -> "Hello! I am ULTRA AI. This is how I sound in natural conversation."
            }
            speak(preview)
            return START_STICKY
        }

        if (action == ACTION_REFRESH_VOICE) {
            applyVoiceSettings()
            captureEngine?.batteryConsciousMode = settingsRepository.batteryConsciousListening()
            return START_STICKY
        }

        if (action == ACTION_SCAN_SCREEN) {
            handleOverlayQuickCommand("Inspect screen")
            return START_STICKY
        }

        if (action == ACTION_READ_SCREEN) {
            handleOverlayQuickCommand("Read screen aloud")
            return START_STICKY
        }

        if (action == ACTION_SCROLL_SCREEN) {
            val res = screenController.scroll("down")
            if (!res.success) {
                speak(res.message)
            }
            return START_STICKY
        }

        if (action == ACTION_SET_MODE_ONLINE) {
            serviceScope.launch {
                settingsRepository.updateSettings { it.copy(operatingMode = AiOperatingMode.ONLINE) }
                sendVoiceState(if (isSessionListening) "LISTENING" else "IDLE")
                speak("Online AI mode enabled.")
            }
            return START_STICKY
        }

        if (action == ACTION_SET_MODE_OFFLINE) {
            serviceScope.launch {
                settingsRepository.updateSettings { it.copy(operatingMode = AiOperatingMode.OFFLINE) }
                sendVoiceState(if (isSessionListening) "OFFLINE" else "IDLE")
                speak("Offline AI mode enabled.")
            }
            return START_STICKY
        }

        if (!PermissionManager.hasMicrophone(this)) {
            stopVoiceService()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_INTERRUPT -> {
                singleTurnOnly = !settingsRepository.continuousListening()
                isSessionListening = true
                handleAcousticBargeIn()
            }
            ACTION_START_SINGLE_TURN -> {
                singleTurnOnly = true
                isSessionListening = true
                startContinuousCapture()
            }
            ACTION_START, null -> {
                singleTurnOnly = !settingsRepository.continuousListening()
                isSessionListening = true
                startContinuousCapture()
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
                if (!streamingResponseInProgress && (activeFinalUtteranceId == null || id == activeFinalUtteranceId)) {
                    handler.post { finishSpeaking() }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (!streamingResponseInProgress) {
                    handler.post { finishSpeaking() }
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (!streamingResponseInProgress) {
                    handler.post { finishSpeaking() }
                }
            }
        })
        val queued = pendingSpeechText
        if (!queued.isNullOrBlank()) {
            pendingSpeechText = null
            speak(queued)
        }
    }

    private fun startContinuousCapture() {
        if (!isRunning || destroyed) return
        sendVoiceState("CONNECTING")
        captureEngine?.assistantSpeaking = speaking
        captureEngine?.processingTurn = aiRequestInProgress
        captureEngine?.batteryConsciousMode = settingsRepository.batteryConsciousListening()

        val started = captureEngine?.start() == true
        if (started) {
            val initialState = if (isEffectiveOffline()) "OFFLINE" else "LISTENING"
            sendVoiceState(initialState)
        } else {
            isSessionListening = false
            sendVoiceState("ERROR", "Microphone could not start")
        }
    }

    private fun resetSpeechPipelines() {
        speechGenerationId.incrementAndGet()
        streamingResponseInProgress = false
        neuralSpeechChannel?.close()
        neuralSpeechChannel = null
        neuralSpeechJob?.cancel()
        neuralSpeechJob = null
        audioController.stopPcmPlayback()
        runCatching { tts?.stop() }
        audioController.abandonSpeechFocus()
        speaking = false
        activeFinalUtteranceId = null
        captureEngine?.assistantSpeaking = false
    }

    private fun pauseListeningAndSpeechKeepOverlay() {
        isSessionListening = false
        resetSpeechPipelines()
        agentManager.requestStop()
        activeAgentJob?.cancel()
        activeAgentJob = null
        transcriptionJob?.cancel()
        transcriptionJob = null
        aiRequestInProgress = false
        _voiceLevelFlow.value = 0f
        captureEngine?.stop()
        runCatching {
            offlineRecognizer?.cancel()
            offlineRecognizer?.destroy()
        }
        offlineRecognizer = null
        sendVoiceState("STOPPED")
    }

    private fun cancelActiveProcessingOnly() {
        speechGenerationId.incrementAndGet()
        streamingResponseInProgress = false
        agentManager.requestStop()
        activeAgentJob?.cancel()
        activeAgentJob = null
        transcriptionJob?.cancel()
        transcriptionJob = null
        aiRequestInProgress = false
        captureEngine?.processingTurn = false
        sendVoiceState(if (isSessionListening) "LISTENING" else "IDLE")
    }

    private fun handleAcousticBargeIn() {
        resetSpeechPipelines()
        agentManager.requestStop()
        activeAgentJob?.cancel()
        activeAgentJob = null
        transcriptionJob?.cancel()
        transcriptionJob = null
        aiRequestInProgress = false
        captureEngine?.processingTurn = false
        wakeActivated = true
        sendVoiceState("INTERRUPTED")
        handler.postDelayed({
            if (isRunning && !destroyed && isSessionListening && !speaking && !aiRequestInProgress) {
                sendVoiceState("USER_SPEAKING")
            }
        }, 140L)
    }

    private fun stopSpeakingOnly() {
        resetSpeechPipelines()
        sendVoiceState(if (isSessionListening) "LISTENING" else "IDLE")
    }

    private fun handleCapturedUtterance(wavBytes: ByteArray) {
        if (!isRunning || destroyed || aiRequestInProgress) return

        // If device is offline or set to Offline mode, hand off single-shot offline recognition cleanly
        if (isEffectiveOffline()) {
            runPlatformSingleTurnRecognition(preferOffline = true)
            return
        }

        val currentGen = speechGenerationId.get()
        aiRequestInProgress = true
        captureEngine?.processingTurn = true
        sendVoiceState("PROCESSING")

        transcriptionJob?.cancel()
        transcriptionJob = serviceScope.launch {
            val langHint = settingsRepository.language()
            val transcriptionResult = agentManager.modelRouter.transcribeMultilingualAudio(wavBytes, langHint)
            if (!isRunning || destroyed || speechGenerationId.get() != currentGen) {
                aiRequestInProgress = false
                captureEngine?.processingTurn = false
                return@launch
            }

            transcriptionResult.fold(
                onSuccess = { (transcript, detectedLang) ->
                    if (transcript.isBlank()) {
                        aiRequestInProgress = false
                        captureEngine?.processingTurn = false
                        sendVoiceState(if (isSessionListening) "LISTENING" else "IDLE")
                    } else {
                        if (settingsRepository.languageAutoDetect() && detectedLang != "mixed") {
                            val mapped = when (detectedLang.lowercase()) {
                                "bn" -> "বাংলা"
                                "hi" -> "हिन्दी"
                                "en" -> "English"
                                else -> null
                            }
                            if (mapped != null) settingsRepository.setLanguage(mapped)
                        }
                        sendRecognizedText(transcript)
                        processSpokenTranscript(transcript, currentGen)
                    }
                },
                onFailure = { err ->
                    aiRequestInProgress = false
                    captureEngine?.processingTurn = false
                    val msg = err.localizedMessage.orEmpty()
                    if (!isEffectiveOffline()) {
                        // Online cloud audio failed: gracefully fall back to online Android SpeechRecognizer (not forced offline!)
                        sendVoiceState("LISTENING", msg)
                        runPlatformSingleTurnRecognition(preferOffline = false)
                    } else if (settingsRepository.operatingMode() == AiOperatingMode.AUTO) {
                        sendVoiceState("RECONNECTING", msg)
                        runPlatformSingleTurnRecognition(preferOffline = true)
                    } else {
                        sendVoiceState("ERROR", msg)
                    }
                }
            )
        }
    }

    private fun runPlatformSingleTurnRecognition(preferOffline: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            val msg = if (preferOffline) {
                "Offline speech recognizer unavailable on this device."
            } else {
                "Speech recognizer unavailable on this device."
            }
            sendVoiceState(if (preferOffline) "OFFLINE" else "LISTENING", msg)
            aiRequestInProgress = false
            captureEngine?.processingTurn = false
            return
        }
        captureEngine?.pauseForOfflineRecognizer()
        runCatching { offlineRecognizer?.destroy() }
        offlineRecognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(this) }.getOrNull()
        val recognizer = offlineRecognizer ?: run {
            aiRequestInProgress = false
            captureEngine?.processingTurn = false
            if (isRunning && !destroyed && isSessionListening && !singleTurnOnly) {
                captureEngine?.resumeAfterOfflineRecognizer()
                sendVoiceState(if (isEffectiveOffline()) "OFFLINE" else "LISTENING")
            } else {
                sendVoiceState("IDLE")
            }
            return
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (isRunning) sendVoiceState(if (preferOffline) "OFFLINE" else "LISTENING")
            }

            override fun onBeginningOfSpeech() {
                if (isRunning) sendVoiceState("USER_SPEAKING")
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (isRunning) sendVoiceLevel(((rmsdB + 2f).coerceIn(0f, 12f)))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (isRunning) sendVoiceState("PROCESSING")
            }

            override fun onError(error: Int) {
                runCatching { recognizer.destroy() }
                offlineRecognizer = null
                aiRequestInProgress = false
                captureEngine?.processingTurn = false
                if (isRunning && isSessionListening && !singleTurnOnly) {
                    captureEngine?.resumeAfterOfflineRecognizer()
                    sendVoiceState(if (isEffectiveOffline()) "OFFLINE" else "LISTENING")
                } else {
                    sendVoiceState("IDLE")
                }
            }

            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                runCatching { recognizer.destroy() }
                offlineRecognizer = null
                if (spoken.isBlank()) {
                    aiRequestInProgress = false
                    captureEngine?.processingTurn = false
                    if (isRunning && isSessionListening && !singleTurnOnly) {
                        captureEngine?.resumeAfterOfflineRecognizer()
                        sendVoiceState(if (isEffectiveOffline()) "OFFLINE" else "LISTENING")
                    }
                } else {
                    sendRecognizedText(spoken)
                    if (isRunning && isSessionListening && !singleTurnOnly) {
                        captureEngine?.resumeAfterOfflineRecognizer()
                    }
                    processSpokenTranscript(spoken, speechGenerationId.get())
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (partial.isNotBlank()) sendPartialText(partial)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val tag = LanguageManager.speechRecognizerTag(settingsRepository.language())
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
        }
        runCatching {
            recognizer.startListening(intent)
        }.onFailure {
            runCatching { recognizer.destroy() }
            offlineRecognizer = null
            aiRequestInProgress = false
            captureEngine?.processingTurn = false
            if (isRunning && !destroyed && isSessionListening && !singleTurnOnly) {
                captureEngine?.resumeAfterOfflineRecognizer()
                sendVoiceState(if (isEffectiveOffline()) "OFFLINE" else "LISTENING")
            } else {
                sendVoiceState("IDLE")
            }
        }
    }

    private fun handleOverlayQuickCommand(command: String) {
        if (aiRequestInProgress) return
        val gen = speechGenerationId.get()
        processSpokenTranscript(command, gen)
    }

    private fun processSpokenTranscript(spokenRaw: String, generationAtStart: Long) {
        val spoken = IntentEngine.normalizeSpeechTranscript(spokenRaw)
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
            aiRequestInProgress = false
            captureEngine?.processingTurn = false
            val ack = when (LanguageManager.speechRecognizerTag(settingsRepository.language())) {
                "bn-IN" -> "জি, বলুন। আমি শুনছি।"
                "hi-IN" -> "जी, बोलिए। मैं सुन रहा हूँ।"
                else -> "Yes, I am listening."
            }
            speak(ack)
            return
        }

        if (IntentEngine.isStopCommand(commandText)) {
            cancelActiveProcessingOnly()
            stopSpeakingOnly()
            sendAgentReply("Stopped.")
            return
        }

        aiRequestInProgress = true
        captureEngine?.processingTurn = true
        sendVoiceState("THINKING", commandText)

        activeAgentJob?.cancel()
        activeAgentJob = serviceScope.launch {
            val streamExtractor = SentenceStreamExtractor()
            var streamedAnyChunk = false
            streamingResponseInProgress = true

            val streamCollectorJob = launch {
                agentManager.streamingReplyFlow.collect { partial ->
                    if (speechGenerationId.get() != generationAtStart || partial.isBlank()) return@collect
                    val readySentences = streamExtractor.consumeDelta(partial)
                    for (sentence in readySentences) {
                        if (speechGenerationId.get() != generationAtStart) break
                        speakStreamingSentence(
                            sentence = sentence,
                            generationId = generationAtStart,
                            isFirst = !streamedAnyChunk
                        )
                        streamedAnyChunk = true
                    }
                }
            }

            val report = try {
                agentManager.handleUserCommand(commandText)
            } catch (e: CancellationException) {
                streamCollectorJob.cancel()
                streamingResponseInProgress = false
                aiRequestInProgress = false
                captureEngine?.processingTurn = false
                throw e
            } catch (_: Exception) {
                null
            } finally {
                streamCollectorJob.cancel()
                streamingResponseInProgress = false
            }

            aiRequestInProgress = false
            captureEngine?.processingTurn = false
            if (!isRunning || destroyed || speechGenerationId.get() != generationAtStart) {
                return@launch
            }

            val reply = report?.finalReply?.takeIf { it.isNotBlank() }
                ?: "I couldn't process that command right now."
            sendAgentReply(reply)

            if (streamedAnyChunk) {
                val remainder = streamExtractor.flushRemainder(reply)
                if (remainder.isNotBlank()) {
                    speakStreamingSentence(remainder, generationAtStart, isFirst = false)
                }
                neuralSpeechChannel?.close()
                if (neuralSpeechJob?.isActive != true && tts?.isSpeaking != true) {
                    finishSpeaking()
                }
            } else {
                speak(reply)
            }
        }
    }

    private fun shouldUseNeuralTts(): Boolean {
        if (isEffectiveOffline()) return false
        return agentManager.modelRouter.shouldAttemptNeuralSpeech(settingsRepository.aiProviderMode())
    }

    private fun ensureNeuralSpeechQueue(generationId: Long): Channel<Pair<Long, String>> {
        val existing = neuralSpeechChannel
        if (existing != null && neuralSpeechJob?.isActive == true) {
            return existing
        }
        val ch = Channel<Pair<Long, String>>(Channel.UNLIMITED)
        neuralSpeechChannel = ch
        neuralSpeechJob = serviceScope.launch {
            var completedChunk = false
            var fallbackUtteranceQueued = false
            try {
                for ((itemGen, chunkText) in ch) {
                    if (!isRunning || destroyed || speechGenerationId.get() != generationId || itemGen != generationId) {
                        break
                    }
                    val settings = settingsRepository.settingsFlow.first()
                    val langHint = LanguageManager.detectScriptLanguageTag(chunkText, settings.language)
                    val synth = agentManager.modelRouter.synthesizeNaturalSpeech(
                        text = chunkText,
                        voiceGender = settings.voiceGender,
                        languageHint = langHint
                    )
                    if (!isRunning || destroyed || speechGenerationId.get() != generationId) break

                    val playedNeural = synth.getOrNull()?.let { pcmBytes ->
                        audioController.playPcm24kAudio(
                            pcmBytes = pcmBytes,
                            volume = settings.voiceVolume,
                            speed = settings.voiceSpeed,
                            shouldContinue = { isRunning && !destroyed && speechGenerationId.get() == generationId }
                        )
                    } ?: false

                    if (playedNeural) {
                        fallbackUtteranceQueued = false
                    } else if (isRunning && !destroyed && speechGenerationId.get() == generationId) {
                        // Graceful local TTS fallback if neural TTS is unavailable
                        if (speakFallbackViaPlatformTts(chunkText, generationId, isFirst = !completedChunk)) {
                            fallbackUtteranceQueued = true
                        }
                    }
                    completedChunk = true
                }
            } finally {
                if (speechGenerationId.get() == generationId &&
                    !streamingResponseInProgress &&
                    !fallbackUtteranceQueued &&
                    tts?.isSpeaking != true
                ) {
                    finishSpeaking()
                }
            }
        }
        return ch
    }

    private fun speakFallbackViaPlatformTts(clean: String, generationId: Long, isFirst: Boolean): Boolean {
        val engine = tts
        if (!ttsReady || engine == null) return false
        applyVoiceSettings(detectReplyLanguageOverride(clean))
        val uttId = audioController.speakNaturally(
            tts = engine,
            rawText = clean,
            languageSetting = settingsRepository.language(),
            gender = settingsRepository.voiceGender(),
            speed = settingsRepository.voiceSpeed(),
            userPitch = settingsRepository.voicePitch(),
            volume = settingsRepository.voiceVolume(),
            preferOffline = isEffectiveOffline(),
            explicitVoiceName = settingsRepository.voiceName(),
            flushQueue = isFirst,
            utterancePrefix = "ultra_fb_$generationId"
        )
        if (uttId != null) {
            activeFinalUtteranceId = uttId
            return true
        }
        return false
    }

    private fun speakStreamingSentence(sentence: String, generationId: Long, isFirst: Boolean) {
        if (!isRunning || destroyed || speechGenerationId.get() != generationId) return
        val clean = audioController.sanitizeForSpeech(sentence)
        if (clean.isBlank()) return

        if (isFirst) {
            audioController.requestSpeechFocus()
            speaking = true
            captureEngine?.assistantSpeaking = true
            sendVoiceState("SPEAKING", clean)
        }

        if (shouldUseNeuralTts()) {
            ensureNeuralSpeechQueue(generationId).trySend(generationId to clean)
            return
        }

        val engine = tts
        if (!ttsReady || engine == null) return
        speakFallbackViaPlatformTts(clean, generationId, isFirst = isFirst)
    }

    private fun speak(text: String) {
        if (!isRunning || destroyed) return
        val clean = audioController.sanitizeForSpeech(text)
        if (clean.isBlank()) {
            finishSpeaking()
            return
        }

        val currentGen = speechGenerationId.get()
        if (shouldUseNeuralTts()) {
            audioController.requestSpeechFocus()
            speaking = true
            captureEngine?.assistantSpeaking = true
            sendVoiceState("SPEAKING", clean)
            val ch = ensureNeuralSpeechQueue(currentGen)
            ch.trySend(currentGen to clean)
            ch.close()
            return
        }

        val engine = tts
        if (!ttsReady || engine == null) {
            pendingSpeechText = text
            return
        }
        applyVoiceSettings(detectReplyLanguageOverride(clean))
        audioController.requestSpeechFocus()
        speaking = true
        captureEngine?.assistantSpeaking = true
        sendVoiceState("SPEAKING", clean)

        val lastId = audioController.speakNaturally(
            tts = engine,
            rawText = clean,
            languageSetting = settingsRepository.language(),
            gender = settingsRepository.voiceGender(),
            speed = settingsRepository.voiceSpeed(),
            userPitch = settingsRepository.voicePitch(),
            volume = settingsRepository.voiceVolume(),
            preferOffline = isEffectiveOffline(),
            explicitVoiceName = settingsRepository.voiceName(),
            flushQueue = true,
            utterancePrefix = "ultra_reply_$currentGen"
        )
        activeFinalUtteranceId = lastId
        if (lastId == null) {
            finishSpeaking()
        }
    }

    private fun detectReplyLanguageOverride(clean: String): String? = when {
        clean.any { it in '\u0980'..'\u09FF' } -> "বাংলা"
        clean.any { it in '\u0900'..'\u097F' } -> "हिन्दी"
        else -> null
    }

    private fun finishSpeaking() {
        speaking = false
        activeFinalUtteranceId = null
        captureEngine?.assistantSpeaking = false
        audioController.abandonSpeechFocus()
        if (singleTurnOnly || !settingsRepository.continuousListening()) {
            isSessionListening = false
            captureEngine?.stop()
            sendVoiceState("IDLE")
        } else if (isSessionListening) {
            if (captureEngine?.latestStatus?.captureRunning != true) {
                captureEngine?.start()
            }
            sendVoiceState(if (isEffectiveOffline()) "OFFLINE" else "LISTENING")
        } else {
            sendVoiceState("STOPPED")
        }
    }

    private fun applyVoiceSettings(languageOverride: String? = null) {
        val engine = tts ?: return
        val effectiveOffline = isEffectiveOffline()
        val chosenLanguage = languageOverride ?: settingsRepository.language()
        audioController.configureTts(
            tts = engine,
            languageSetting = chosenLanguage,
            gender = settingsRepository.voiceGender(),
            speed = settingsRepository.voiceSpeed(),
            userPitch = settingsRepository.voicePitch(),
            preferOffline = effectiveOffline,
            explicitVoiceName = if (languageOverride == null) settingsRepository.voiceName() else null
        )
    }

    private fun isEffectiveOffline(): Boolean {
        return when (settingsRepository.operatingMode()) {
            AiOperatingMode.OFFLINE -> true
            AiOperatingMode.ONLINE -> false
            AiOperatingMode.AUTO -> !networkMonitor.checkCurrentConnectivity()
        }
    }

    private fun sendVoiceState(state: String, snippet: String? = null) {
        currentState = state
        floatingDot?.updateState(
            state = state,
            snippet = snippet,
            isVoiceSessionActive = isSessionListening,
            edgeGlowEnabled = settingsRepository.ambientEdgeGlowEnabled()
        )
        if (!isRunning) return
        sendBroadcast(Intent(BROADCAST_VOICE_STATE).setPackage(packageName).putExtra("state", state))
    }

    private fun sendAudioStatus(status: AudioHardwareStatus) {
        if (!isRunning) return
        sendBroadcast(
            Intent(BROADCAST_AUDIO_STATUS)
                .setPackage(packageName)
                .putExtra("summary", status.summaryLabel())
                .putExtra("ns_active", status.noiseSuppressorEnabled)
                .putExtra("aec_active", status.echoCancelerEnabled)
                .putExtra("clipping", status.clippingDetected)
        )
    }

    private fun sendRecognizedText(text: String) {
        floatingDot?.updateState("PROCESSING", text, isSessionListening, settingsRepository.ambientEdgeGlowEnabled())
        if (!isRunning) return
        sendBroadcast(Intent(BROADCAST_VOICE_TEXT).setPackage(packageName).putExtra("text", text))
    }

    private fun sendPartialText(text: String) {
        floatingDot?.updateState("USER_SPEAKING", text, isSessionListening, settingsRepository.ambientEdgeGlowEnabled())
        if (!isRunning) return
        sendBroadcast(Intent(BROADCAST_VOICE_PARTIAL).setPackage(packageName).putExtra("text", text))
    }

    private fun sendVoiceLevel(level: Float) {
        if (!isRunning) return
        _voiceLevelFlow.value = level
    }

    private fun sendAgentReply(reply: String) {
        floatingDot?.updateState("SPEAKING", reply, isSessionListening, settingsRepository.ambientEdgeGlowEnabled())
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
            .setContentText("Continuous natural voice (${settingsRepository.voiceGender().label})")
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun stopVoiceService() {
        isRunning = false
        isSessionListening = false
        currentState = "IDLE"
        destroyed = true
        _voiceLevelFlow.value = 0f
        resetSpeechPipelines()
        handler.removeCallbacksAndMessages(null)
        activeAgentJob?.cancel()
        activeAgentJob = null
        transcriptionJob?.cancel()
        transcriptionJob = null
        floatingDot?.hide()
        floatingDot = null
        captureEngine?.destroy()
        captureEngine = null
        runCatching {
            offlineRecognizer?.cancel()
            offlineRecognizer?.destroy()
        }
        offlineRecognizer = null
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ttsReady = false
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
        isSessionListening = false
        currentState = "IDLE"
        destroyed = true
        _voiceLevelFlow.value = 0f
        resetSpeechPipelines()
        handler.removeCallbacksAndMessages(null)
        activeAgentJob?.cancel()
        activeAgentJob = null
        transcriptionJob?.cancel()
        transcriptionJob = null
        floatingDot?.hide()
        floatingDot = null
        captureEngine?.destroy()
        captureEngine = null
        runCatching {
            offlineRecognizer?.cancel()
            offlineRecognizer?.destroy()
        }
        offlineRecognizer = null
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
