package com.ultra.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
import com.ultra.assistant.brain.AgentManager
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.voice.LanguageManager
import com.ultra.assistant.voice.WakeWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VoiceAssistantService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.ultra.assistant.START_VOICE"
        const val ACTION_STOP = "com.ultra.assistant.STOP_VOICE"
        const val ACTION_INTERRUPT = "com.ultra.assistant.INTERRUPT_VOICE"
        const val ACTION_SHOW_DOT = "SHOW_DOT"
        const val ACTION_HIDE_DOT = "HIDE_DOT"
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
    private var floatingDot: FloatingDot? = null

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settingsRepository: LocalSettingsRepository
    private lateinit var agentManager: AgentManager

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        isRunning = true
        settingsRepository = UltraRuntime.settingsRepository(this)
        agentManager = UltraRuntime.agentManager(this)
        floatingDot = FloatingDot(this)
        createNotificationChannel()
        startForeground(31, createNotification())
        tts = TextToSpeech(this, this)
        setupRecognizer()
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVoiceService()
                return START_NOT_STICKY
            }
            ACTION_INTERRUPT -> {
                interruptSpeakingAndListen()
            }
            ACTION_SHOW_DOT -> {
                floatingDot?.show()
            }
            ACTION_HIDE_DOT -> {
                floatingDot?.hide()
            }
            ACTION_START, null -> {
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
                handler.post { finishSpeaking() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                handler.post { finishSpeaking() }
            }
            override fun onError(id: String?, errorCode: Int) {
                handler.post { finishSpeaking() }
            }
        })
    }

    private fun applyVoiceSettings() {
        val engine = tts ?: return
        engine.language = LanguageManager.locale(settingsRepository.language())
        engine.setSpeechRate(settingsRepository.voiceSpeed())
        engine.setPitch(settingsRepository.voicePitch())
        val voiceName = settingsRepository.voiceName()
        if (!voiceName.isNullOrBlank()) {
            engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.voice = it }
        }
    }

    private fun interruptSpeakingAndListen() {
        runCatching { tts?.stop() }
        speaking = false
        aiRequestInProgress = false
        wakeActivated = true
        sendVoiceState("LISTENING")
        startListening()
    }

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendVoiceState("OFFLINE")
            return
        }
        runCatching { recognizer?.destroy() }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!isRunning) return
                listening = true
                sendVoiceState("LISTENING")
            }

            override fun onBeginningOfSpeech() {
                if (!isRunning) return
                listening = true
                if (speaking) {
                    runCatching { tts?.stop() }
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
                sendVoiceState("IDLE")
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    stopVoiceService()
                } else {
                    val delay = if (error == SpeechRecognizer.ERROR_CLIENT) 1200L else 700L
                    restartListening(delay)
                }
            }

            override fun onResults(results: Bundle?) {
                if (!isRunning) return
                listening = false
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (spoken.isBlank()) {
                    sendVoiceState("IDLE")
                    restartListening(450L)
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        }
        try {
            recognizer?.startListening(speechIntent)
            listening = true
            sendVoiceState("LISTENING")
        } catch (_: Exception) {
            listening = false
            restartListening(1200L)
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
            // Allow direct voice commands while service is active, or prompt if wake phrase only
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
        sendVoiceState("PROCESSING")
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
        val clean = text.replace("**", "").replace("`", "").trim()
        if (!ttsReady || clean.isBlank()) {
            sendVoiceState("IDLE")
            restartListening(450L)
            return
        }
        applyVoiceSettings()
        speaking = true
        listening = false
        sendVoiceState("SPEAKING")
        val params = Bundle()
        val res = tts?.speak(clean.take(600), TextToSpeech.QUEUE_FLUSH, params, "ultra_reply_${System.currentTimeMillis()}")
        if (res != TextToSpeech.SUCCESS) {
            finishSpeaking()
        }
    }

    private fun finishSpeaking() {
        speaking = false
        sendVoiceState("IDLE")
        restartListening(350L)
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

    private fun sendVoiceState(state: String) {
        currentState = state
        if (!isRunning) return
        sendBroadcast(Intent(BROADCAST_VOICE_STATE).setPackage(packageName).putExtra("state", state))
    }

    private fun sendRecognizedText(text: String) {
        if (!isRunning) return
        sendBroadcast(Intent(BROADCAST_VOICE_TEXT).setPackage(packageName).putExtra("text", text))
    }

    private fun sendPartialText(text: String) {
        if (!isRunning) return
        sendBroadcast(Intent(BROADCAST_VOICE_PARTIAL).setPackage(packageName).putExtra("text", text))
    }

    private fun sendVoiceLevel(level: Float) {
        if (!isRunning) return
        sendBroadcast(Intent(BROADCAST_VOICE_LEVEL).setPackage(packageName).putExtra("level", level))
    }

    private fun sendAgentReply(reply: String) {
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
            .setContentTitle("ULTRA AI Active")
            .setContentText("Voice agent listening (${settingsRepository.wakePhrase()})")
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
