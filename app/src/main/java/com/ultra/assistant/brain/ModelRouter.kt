package com.ultra.assistant.brain

import android.util.Base64
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.ultra.assistant.data.AiProviderMode
import com.ultra.assistant.data.AiSpeedProfile
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.data.NetworkMonitor
import com.ultra.assistant.data.UltraSettings
import com.ultra.assistant.data.VoiceGender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "ModelRouter"

class ModelRouter(
    private val settingsRepository: LocalSettingsRepository,
    private val networkMonitor: NetworkMonitor? = null
) : AIEngine {

    // Zero HTTP disk cache to minimize storage usage and preserve strict session privacy.
    private val httpClient = OkHttpClient.Builder()
        .cache(null)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _onlineDiagnostic = MutableStateFlow("")
    val onlineDiagnosticFlow: StateFlow<String> = _onlineDiagnostic.asStateFlow()

    @Volatile
    private var firebaseAiRejectedForSession: Boolean = false

    @Volatile
    private var firebaseNeuralTtsRejectedForSession: Boolean = false

    @Volatile
    private var workerTtsUnavailableForSession: Boolean = false

    fun clearTransientDiagnostic() {
        _onlineDiagnostic.value = ""
        workerTtsUnavailableForSession = false
    }

    fun resetSessionRoutingCaches() {
        _onlineDiagnostic.value = ""
        firebaseAiRejectedForSession = false
        firebaseNeuralTtsRejectedForSession = false
        workerTtsUnavailableForSession = false
    }

    fun shouldAttemptNeuralSpeech(mode: AiProviderMode): Boolean {
        val workerEligible = mode != AiProviderMode.FIREBASE_AI && !workerTtsUnavailableForSession
        val firebaseReady = runCatching { FirebaseApp.getInstance() }.isSuccess
        val firebaseEligible = mode != AiProviderMode.CLOUDFLARE_WORKER &&
            firebaseReady &&
            !firebaseAiRejectedForSession &&
            !firebaseNeuralTtsRejectedForSession
        return workerEligible || firebaseEligible
    }

    private val decisionSchema = Schema.obj(
        properties = mapOf(
            "thoughtSummary" to Schema.string(description = "Analytical step-by-step reasoning behind the plan and answer"),
            "reply" to Schema.string(description = "Natural, warm, ChatGPT-like human conversational response in the user's language without Markdown"),
            "toolCalls" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "toolId" to Schema.string(description = "Registered tool id"),
                        "rationale" to Schema.string(description = "Why this tool is needed"),
                        "argsJson" to Schema.string(description = "JSON object string of arguments")
                    )
                ),
                description = "Zero or more ordered tool calls to execute right now"
            ),
            "nextActionNeeded" to Schema.boolean(description = "Set true ONLY if after executing these tools the assistant must observe the newly opened app screen and execute follow-up UI actions")
        ),
        optionalProperties = listOf("nextActionNeeded")
    )

    private val transcriptionSchema = Schema.obj(
        properties = mapOf(
            "transcript" to Schema.string(description = "Verbatim speech transcript in original spoken script(s): Bengali, Hindi, English, or mixed Banglish/Hinglish. Empty string if only noise or silence."),
            "detectedLanguage" to Schema.string(description = "Detected language code: bn, hi, en, or mixed")
        )
    )

    private val defaultGeminiPlanner by lazy { createPlannerModel("gemini-3.8-flash") }
    private val liteGeminiPlanner by lazy { createPlannerModel("gemini-3.5-flash-lite") }
    private val argonGeminiPlanner by lazy { createPlannerModel("gemini-4-argon") }

    private val audioTranscriberModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = transcriptionSchema
            }
        )
    }

    private val defaultStreamingModel by lazy { createStreamingModel("gemini-3.8-flash") }
    private val liteStreamingModel by lazy { createStreamingModel("gemini-3.5-flash-lite") }
    private val argonStreamingModel by lazy { createStreamingModel("gemini-4-argon") }

    private fun createPlannerModel(modelName: String) = Firebase.ai.generativeModel(
        modelName = modelName,
        generationConfig = generationConfig {
            responseMimeType = "application/json"
            responseSchema = decisionSchema
        }
    )

    private fun createStreamingModel(modelName: String) = Firebase.ai.generativeModel(
        modelName = modelName,
        systemInstruction = content {
            text(
                "You are ULTRA AI, a highly intelligent, analytical, and warm voice + chat assistant combining ChatGPT depth with Jarvis autonomy. " +
                    "For thoughtful, analytical, or multi-part questions, begin with <thought>1-2 concise sentences of logical reasoning</thought> followed immediately by a natural, warm conversational reply in the user's exact language (English, Bengali, Hindi, or mixed Banglish/Hinglish). " +
                    "Remember prior conversation turns from this session and avoid Markdown formatting (no **, #, or code fences) so voice output sounds completely human."
            )
        }
    )

    private val femaleNeuralTtsModel by lazy { createNeuralTtsModel(selectGeminiTtsVoiceName(VoiceGender.FEMALE)) }
    private val maleNeuralTtsModel by lazy { createNeuralTtsModel(selectGeminiTtsVoiceName(VoiceGender.MALE)) }

    @OptIn(PublicPreviewAPI::class)
    private fun createNeuralTtsModel(voiceName: String) = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
        modelName = GEMINI_TTS_MODEL,
        generationConfig = generationConfig {
            responseModalities = listOf(ResponseModality.AUDIO)
            speechConfig = SpeechConfig(voice = Voice(voiceName))
        }
    )

    suspend fun synthesizeNaturalSpeech(
        text: String,
        voiceGender: VoiceGender,
        languageHint: String = "Auto"
    ): Result<ByteArray> {
        val cleanText = text.trim().take(900)
        if (cleanText.isBlank()) {
            return Result.failure(IllegalArgumentException("Empty speech text"))
        }
        if (networkMonitor != null && !networkMonitor.checkCurrentConnectivity()) {
            return Result.failure(IllegalStateException("Offline"))
        }
        val settings = settingsRepository.settingsFlow.first()
        val firebaseReady = runCatching { FirebaseApp.getInstance() }.isSuccess

        // 1. Try Firebase AI neural TTS (gemini-3.1-flash-tts-preview) first in AUTO or FIREBASE_AI mode
        if (settings.aiProviderMode != AiProviderMode.CLOUDFLARE_WORKER &&
            firebaseReady &&
            !firebaseAiRejectedForSession &&
            !firebaseNeuralTtsRejectedForSession
        ) {
            val firebaseTts = try {
                val model = if (voiceGender == VoiceGender.FEMALE) femaleNeuralTtsModel else maleNeuralTtsModel
                val prompt = "Read aloud in a warm, natural, human conversational voice matching the script/language ($languageHint): $cleanText"
                val response = withTimeout(25_000L) {
                    model.generateContent(prompt)
                }
                val pcmBytes = response.candidates
                    .firstOrNull()
                    ?.content
                    ?.parts
                    ?.filterIsInstance<InlineDataPart>()
                    ?.firstOrNull()
                    ?.inlineData
                if (pcmBytes == null || pcmBytes.isEmpty()) {
                    Result.failure(IllegalStateException("Empty Gemini TTS audio"))
                } else {
                    Result.success(pcmBytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAuthOrAppCheckError(e)) {
                    firebaseNeuralTtsRejectedForSession = true
                }
                Log.w(TAG, "Gemini neural TTS unavailable, checking fallback", e)
                Result.failure(IllegalStateException(sanitizeOnlineError(e.localizedMessage ?: "Neural TTS failed")))
            }
            if (firebaseTts.isSuccess || settings.aiProviderMode == AiProviderMode.FIREBASE_AI) {
                return firebaseTts
            }
        }

        // 2. Fallback (or direct route in CLOUDFLARE_WORKER mode) to Cloudflare Worker /api/tts
        if (settings.aiProviderMode != AiProviderMode.FIREBASE_AI && !workerTtsUnavailableForSession) {
            return synthesizeViaCloudflareWorker(
                baseUrl = settings.workerEndpoint,
                authToken = settings.workerAuthToken,
                text = cleanText,
                gender = voiceGender,
                languageHint = languageHint
            )
        }

        return Result.failure(IllegalStateException("Neural TTS unavailable"))
    }

    private suspend fun synthesizeViaCloudflareWorker(
        baseUrl: String,
        authToken: String,
        text: String,
        gender: VoiceGender,
        languageHint: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = UltraSettings.normalizeWorkerEndpoint(baseUrl)
            val bodyJson = JSONObject().apply {
                put("text", text)
                put("gender", gender.name)
                put("language", languageHint)
            }
            val reqBuilder = Request.Builder()
                .url("$cleanBase/api/tts")
                .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            if (authToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${authToken.trim()}")
            }
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code in setOf(404, 405, 501, 503)) {
                        workerTtsUnavailableForSession = true
                    }
                    return@withContext Result.failure(IllegalStateException("Worker TTS HTTP ${response.code}"))
                }
                val obj = JSONObject(raw)
                val audioB64 = obj.optString("audioBase64", "").trim()
                if (!obj.optBoolean("ok", true) || audioB64.isBlank()) {
                    if (obj.optBoolean("fallback", false)) {
                        workerTtsUnavailableForSession = true
                    }
                    return@withContext Result.failure(IllegalStateException("Worker TTS empty"))
                }
                val bytes = Base64.decode(audioB64, Base64.DEFAULT)
                if (bytes.isEmpty()) {
                    Result.failure(IllegalStateException("Decoded TTS empty"))
                } else {
                    Result.success(bytes)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun transcribeMultilingualAudio(
        wavBytes: ByteArray,
        languageHint: String = "Auto"
    ): Result<Pair<String, String>> {
        if (wavBytes.size <= 44) {
            return Result.failure(IllegalArgumentException("Empty audio buffer"))
        }
        if (networkMonitor != null && !networkMonitor.checkCurrentConnectivity()) {
            val msg = "No internet connection"
            _onlineDiagnostic.value = msg
            return Result.failure(IllegalStateException(msg))
        }
        val settings = settingsRepository.settingsFlow.first()
        val firebaseReady = runCatching { FirebaseApp.getInstance() }.isSuccess

        // In AUTO or FIREBASE_AI, prioritize Firebase AI Logic (Gemini) multilingual transcription first
        if (settings.aiProviderMode != AiProviderMode.CLOUDFLARE_WORKER &&
            firebaseReady &&
            !firebaseAiRejectedForSession
        ) {
            val firebaseRes = transcribeViaFirebaseAi(wavBytes, languageHint)
            if (firebaseRes.isSuccess || settings.aiProviderMode == AiProviderMode.FIREBASE_AI) {
                return firebaseRes
            }
        }

        return if (settings.aiProviderMode != AiProviderMode.FIREBASE_AI) {
            transcribeViaCloudflareWorker(
                baseUrl = settings.workerEndpoint,
                authToken = settings.workerAuthToken,
                wavBytes = wavBytes,
                languageHint = languageHint
            )
        } else {
            Result.failure(IllegalStateException("Online speech transcription unavailable"))
        }
    }

    private suspend fun transcribeViaFirebaseAi(
        wavBytes: ByteArray,
        languageHint: String
    ): Result<Pair<String, String>> {
        return try {
            val response = withTimeout(30_000L) {
                audioTranscriberModel.generateContent(
                    content {
                        inlineData(wavBytes, "audio/wav")
                        text(
                            "Transcribe this user voice command verbatim in the exact spoken language(s) " +
                                "(English, Bengali in Bengali script or Banglish, Hindi in Devanagari or Hinglish, or mixed code-switching). " +
                                "User language preference hint: $languageHint. " +
                                "If the clip contains only background noise, fan hum, silence, or unclear audio, return an empty transcript string."
                        )
                    }
                )
            }
            val raw = response.text ?: return Result.failure(IllegalStateException("Empty transcription"))
            val obj = JSONObject(raw)
            val transcript = obj.optString("transcript", "").trim()
            val lang = obj.optString("detectedLanguage", "en").trim().ifBlank { "en" }
            _onlineDiagnostic.value = "Connected (Multilingual Audio)"
            Result.success(transcript to lang)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            val msg = "Audio transcription timed out"
            _onlineDiagnostic.value = msg
            Result.failure(IllegalStateException(msg))
        } catch (e: Exception) {
            if (isAuthOrAppCheckError(e)) {
                firebaseAiRejectedForSession = true
            }
            val msg = sanitizeOnlineError(e.localizedMessage ?: "Audio transcription failed")
            _onlineDiagnostic.value = msg
            Result.failure(IllegalStateException(msg))
        }
    }

    private suspend fun transcribeViaCloudflareWorker(
        baseUrl: String,
        authToken: String,
        wavBytes: ByteArray,
        languageHint: String
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = UltraSettings.normalizeWorkerEndpoint(baseUrl)
            val audioB64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
            val bodyJson = JSONObject().apply {
                put("audioBase64", audioB64)
                put("mimeType", "audio/wav")
                put("language", languageHint)
            }
            val reqBuilder = Request.Builder()
                .url("$cleanBase/api/transcribe")
                .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            if (authToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${authToken.trim()}")
            }
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val friendlyErr = parseWorkerError(response.code, raw)
                    _onlineDiagnostic.value = friendlyErr
                    return@withContext Result.failure(IllegalStateException(friendlyErr))
                }
                val obj = JSONObject(raw)
                if (!obj.optBoolean("ok", true)) {
                    val err = sanitizeOnlineError(obj.optString("error", "Gateway transcribe error"))
                    _onlineDiagnostic.value = err
                    return@withContext Result.failure(IllegalStateException(err))
                }
                val transcript = obj.optString("transcript", obj.optString("text", "")).trim()
                val lang = obj.optString("detectedLanguage", "en").trim().ifBlank { "en" }
                _onlineDiagnostic.value = "Connected (Worker Audio)"
                Result.success(transcript to lang)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = sanitizeOnlineError(e.message ?: "Worker transcribe failed")
            _onlineDiagnostic.value = msg
            Result.failure(IllegalStateException(msg))
        }
    }

    override suspend fun reasonAndPlan(
        userMessage: String,
        history: List<Pair<String, String>>,
        memoryContext: String,
        toolCatalogJson: String,
        screenContext: String,
        languageLabel: String
    ): Result<Pair<String, ReasoningDecision>> {
        if (networkMonitor != null && !networkMonitor.checkCurrentConnectivity()) {
            val msg = "No internet connection"
            _onlineDiagnostic.value = msg
            return Result.failure(IllegalStateException(msg))
        }
        val settings = settingsRepository.settingsFlow.first()
        val effectiveModel = resolveEffectiveModel(settings.aiSpeedProfile, settings.geminiModel)
        val timeoutMs = resolveTimeoutMs(settings.aiSpeedProfile)
        val prompt = buildPlannerPrompt(
            userMessage = userMessage,
            history = history,
            memoryContext = memoryContext,
            toolCatalogJson = toolCatalogJson,
            screenContext = screenContext,
            languageLabel = languageLabel,
            speedProfile = settings.aiSpeedProfile,
            userDisplayName = settings.userDisplayName
        )

        return routePlannerCall(
            mode = settings.aiProviderMode,
            effectiveModel = effectiveModel,
            timeoutMs = timeoutMs,
            prompt = prompt,
            workerUrl = settings.workerEndpoint,
            workerToken = settings.workerAuthToken,
            userMessage = userMessage,
            history = history,
            toolCatalogJson = toolCatalogJson,
            screenContext = screenContext,
            languageLabel = languageLabel
        )
    }

    suspend fun planNextInAppStep(
        goal: String,
        completedSteps: List<String>,
        screenContext: String,
        toolCatalogJson: String,
        languageLabel: String
    ): Result<Pair<String, ReasoningDecision>> {
        if (networkMonitor != null && !networkMonitor.checkCurrentConnectivity()) {
            val msg = "No internet connection"
            _onlineDiagnostic.value = msg
            return Result.failure(IllegalStateException(msg))
        }
        val settings = settingsRepository.settingsFlow.first()
        val effectiveModel = resolveEffectiveModel(settings.aiSpeedProfile, settings.geminiModel)
        val timeoutMs = resolveTimeoutMs(settings.aiSpeedProfile)
        val prompt = """
You are ULTRA AI's autonomous Jarvis In-App Control Agent.
User Goal: $goal
Preferred Language: $languageLabel
Completed Steps So Far:
${if (completedSteps.isEmpty()) "None" else completedSteps.joinToString("\n") { "- $it" }}

Current Live Screen UI Elements:
$screenContext

Available Registered Tools (JSON):
$toolCatalogJson

Instructions:
- Examine the live UI elements carefully to decide the NEXT immediate in-app action (`click_ui_element`, `type_text` with `submit=true` for search/send boxes, `scroll_screen`, `press_back`, or `navigate_system`).
- If the user goal is now satisfied or cannot proceed further safely, return an empty `toolCalls` array (`[]`), set `nextActionNeeded = false`, and explain the result warmly in `reply`.
- If another screen observation + step is needed after executing your chosen tool(s), set `nextActionNeeded = true`.
        """.trimIndent()

        return routePlannerCall(
            mode = settings.aiProviderMode,
            effectiveModel = effectiveModel,
            timeoutMs = timeoutMs,
            prompt = prompt,
            workerUrl = settings.workerEndpoint,
            workerToken = settings.workerAuthToken,
            userMessage = "Continue autonomous task: $goal\nCompleted: ${completedSteps.joinToString("; ")}\nScreen: $screenContext",
            history = emptyList(),
            toolCatalogJson = toolCatalogJson,
            screenContext = screenContext,
            languageLabel = languageLabel
        )
    }

    private suspend fun routePlannerCall(
        mode: AiProviderMode,
        effectiveModel: String,
        timeoutMs: Long,
        prompt: String,
        workerUrl: String,
        workerToken: String,
        userMessage: String,
        history: List<Pair<String, String>>,
        toolCatalogJson: String,
        screenContext: String,
        languageLabel: String
    ): Result<Pair<String, ReasoningDecision>> {
        return when (mode) {
            AiProviderMode.CLOUDFLARE_WORKER -> {
                callCloudflareWorkerAgent(
                    baseUrl = workerUrl,
                    authToken = workerToken,
                    effectiveModel = effectiveModel,
                    userMessage = userMessage,
                    history = history,
                    toolCatalogJson = toolCatalogJson,
                    screenContext = screenContext,
                    languageLabel = languageLabel
                )
            }
            AiProviderMode.FIREBASE_AI -> {
                callFirebaseAiPlanner(effectiveModel, timeoutMs, prompt)
            }
            AiProviderMode.AUTO -> {
                val firebaseReady = runCatching { FirebaseApp.getInstance() }.isSuccess
                if (firebaseReady && !firebaseAiRejectedForSession) {
                    val firebaseRes = callFirebaseAiPlanner(effectiveModel, timeoutMs, prompt)
                    if (firebaseRes.isSuccess) {
                        return firebaseRes
                    }
                    if (networkMonitor != null && !networkMonitor.checkCurrentConnectivity()) {
                        return firebaseRes
                    }
                    return callCloudflareWorkerAgent(
                        baseUrl = workerUrl,
                        authToken = workerToken,
                        effectiveModel = effectiveModel,
                        userMessage = userMessage,
                        history = history,
                        toolCatalogJson = toolCatalogJson,
                        screenContext = screenContext,
                        languageLabel = languageLabel
                    ).recoverCatching { gwErr ->
                        val fbErr = firebaseRes.exceptionOrNull()?.message ?: "Firebase AI unavailable"
                        throw IllegalStateException("Online AI unavailable ($fbErr; Worker: ${gwErr.message})")
                    }
                }
                callCloudflareWorkerAgent(
                    baseUrl = workerUrl,
                    authToken = workerToken,
                    effectiveModel = effectiveModel,
                    userMessage = userMessage,
                    history = history,
                    toolCatalogJson = toolCatalogJson,
                    screenContext = screenContext,
                    languageLabel = languageLabel
                )
            }
        }
    }

    private suspend fun callFirebaseAiPlanner(
        configuredModel: String,
        timeoutMs: Long,
        prompt: String
    ): Result<Pair<String, ReasoningDecision>> {
        val firebaseReady = runCatching { FirebaseApp.getInstance() }.isSuccess
        if (!firebaseReady) {
            val msg = "Firebase AI not initialized"
            _onlineDiagnostic.value = msg
            return Result.failure(IllegalStateException(msg))
        }
        val model = when (configuredModel) {
            "gemini-3.5-flash-lite" -> liteGeminiPlanner
            "gemini-4-argon" -> argonGeminiPlanner
            else -> defaultGeminiPlanner
        }
        return try {
            val response = withTimeout(timeoutMs) {
                model.generateContent(prompt)
            }
            val rawJson = response.text ?: return Result.failure(IllegalStateException("Gemini returned an empty response."))
            val decision = parseDecisionJson(rawJson)
            val label = "Online • $configuredModel"
            _onlineDiagnostic.value = "Connected ($label)"
            Result.success(label to decision)
        } catch (e: TimeoutCancellationException) {
            val msg = "Online AI timed out after ${timeoutMs / 1000}s"
            _onlineDiagnostic.value = msg
            Log.e(TAG, msg, e)
            Result.failure(IllegalStateException(msg))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isAuthOrAppCheckError(e)) {
                firebaseAiRejectedForSession = true
            }
            val msg = sanitizeOnlineError(e.localizedMessage ?: "Firebase AI error")
            _onlineDiagnostic.value = msg
            Log.e(TAG, "Firebase AI Logic request failed", e)
            Result.failure(IllegalStateException(msg))
        }
    }

    private suspend fun callCloudflareWorkerAgent(
        baseUrl: String,
        authToken: String,
        effectiveModel: String,
        userMessage: String,
        history: List<Pair<String, String>>,
        toolCatalogJson: String,
        screenContext: String,
        languageLabel: String
    ): Result<Pair<String, ReasoningDecision>> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = UltraSettings.normalizeWorkerEndpoint(baseUrl)
            val historyArray = JSONArray().apply {
                history.takeLast(10).forEach { (role, content) ->
                    put(JSONObject().put("role", role).put("content", content.take(1500)))
                }
            }
            val bodyJson = JSONObject().apply {
                put("message", userMessage)
                put("prompt", userMessage)
                put("model", effectiveModel)
                put("language", languageLabel)
                put("history", historyArray)
                put("tools", toolCatalogJson)
                if (screenContext.isNotBlank()) {
                    put("screenContext", screenContext)
                }
            }
            val bodyStr = bodyJson.toString()
            val endpoints = listOf("$cleanBase/api/voice", "$cleanBase/")
            var lastFailure: Result<Pair<String, ReasoningDecision>>? = null

            for ((idx, endpoint) in endpoints.withIndex()) {
                val reqBuilder = Request.Builder()
                    .url(endpoint)
                    .post(bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
                if (authToken.isNotBlank()) {
                    reqBuilder.header("Authorization", "Bearer ${authToken.trim()}")
                }
                httpClient.newCall(reqBuilder.build()).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val friendlyErr = parseWorkerError(response.code, raw)
                        if (idx == 0 && (response.code == 404 || response.code == 405)) {
                            lastFailure = Result.failure(IllegalStateException(friendlyErr))
                            return@use
                        }
                        _onlineDiagnostic.value = friendlyErr
                        return@withContext Result.failure(IllegalStateException(friendlyErr))
                    }
                    val parsed = parseWorkerAgentResponse(raw)
                    parsed.onSuccess { (label, _) ->
                        _onlineDiagnostic.value = "Connected ($label)"
                    }.onFailure { err ->
                        _onlineDiagnostic.value = sanitizeOnlineError(err.message ?: "Gateway error")
                    }
                    return@withContext parsed
                }
            }
            lastFailure ?: Result.failure(IllegalStateException("Cloudflare Worker unreachable"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = sanitizeOnlineError("Cloudflare Worker unreachable: ${e.message ?: "network error"}")
            _onlineDiagnostic.value = msg
            Log.w(TAG, msg)
            Result.failure(IllegalStateException(msg))
        }
    }

    suspend fun testWorkerHealth(baseUrl: String, authToken: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = UltraSettings.normalizeWorkerEndpoint(baseUrl)
            val targets = listOf("$cleanBase/health", "$cleanBase/")
            var lastErrorMsg = "Health check failed"

            for ((idx, targetUrl) in targets.withIndex()) {
                val reqBuilder = Request.Builder().url(targetUrl).get()
                if (authToken.isNotBlank()) {
                    reqBuilder.header("Authorization", "Bearer ${authToken.trim()}")
                }
                httpClient.newCall(reqBuilder.build()).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        lastErrorMsg = parseWorkerError(response.code, raw)
                        if (idx == 0 && (response.code == 404 || response.code == 405)) {
                            return@use
                        }
                        throw IllegalStateException(lastErrorMsg)
                    }
                    val summary = runCatching {
                        val json = JSONObject(raw)
                        val providers = json.optJSONObject("providers")
                        if (providers != null) {
                            "Workers AI=${providers.optBoolean("workersAi")}, Gemini=${providers.optBoolean("gemini")}, OpenAI=${providers.optBoolean("openai")}"
                        } else {
                            json.optString("service", json.optString("status", "Healthy")).ifBlank { "Healthy" }
                        }
                    }.getOrElse {
                        raw.trim().take(60).ifBlank { "Healthy" }
                    }
                    _onlineDiagnostic.value = "Health OK ($summary)"
                    return@runCatching "Cloudflare Gateway Ready • $summary"
                }
            }
            throw IllegalStateException(lastErrorMsg)
        }.onFailure { err ->
            _onlineDiagnostic.value = sanitizeOnlineError(err.message ?: "Health check failed")
        }
    }

    override fun streamConversation(
        prompt: String,
        history: List<Pair<String, String>>,
        languageLabel: String
    ): Flow<String> = flow {
        if (networkMonitor != null && !networkMonitor.checkCurrentConnectivity()) {
            val msg = "No internet connection"
            _onlineDiagnostic.value = msg
            throw IllegalStateException(msg)
        }
        val settings = settingsRepository.settingsFlow.first()
        val effectiveModel = resolveEffectiveModel(settings.aiSpeedProfile, settings.geminiModel)
        val timeoutMs = resolveTimeoutMs(settings.aiSpeedProfile)
        val firebaseReady = runCatching { FirebaseApp.getInstance() }.isSuccess

        // Prioritize Firebase AI Logic (Google AI Studio Gemini Brain) in AUTO and FIREBASE_AI; fall back to Cloudflare Worker in AUTO
        if (settings.aiProviderMode != AiProviderMode.CLOUDFLARE_WORKER &&
            firebaseReady &&
            !firebaseAiRejectedForSession
        ) {
            try {
                streamFromGemini(effectiveModel, timeoutMs, prompt, history, languageLabel).collect { emit(it) }
                return@flow
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (settings.aiProviderMode == AiProviderMode.FIREBASE_AI) {
                    throw e
                }
                Log.w(TAG, "Primary Gemini stream failed, failing over to Cloudflare Worker", e)
            }
        } else if (settings.aiProviderMode == AiProviderMode.FIREBASE_AI) {
            throw IllegalStateException("Firebase AI not initialized")
        }

        val workerRes = callCloudflareWorkerAgent(
            baseUrl = settings.workerEndpoint,
            authToken = settings.workerAuthToken,
            effectiveModel = effectiveModel,
            userMessage = prompt,
            history = history,
            toolCatalogJson = "[]",
            screenContext = "",
            languageLabel = languageLabel
        )
        workerRes.fold(
            onSuccess = { (_, decision) ->
                val prefix = if (decision.thoughtSummary.isNotBlank()) "<thought>${decision.thoughtSummary}</thought>" else ""
                emit(prefix + decision.directReply)
            },
            onFailure = { err -> throw err }
        )
    }

    private fun streamFromGemini(
        configuredModel: String,
        timeoutMs: Long,
        prompt: String,
        history: List<Pair<String, String>>,
        languageLabel: String
    ): Flow<String> = flow {
        val model = when (configuredModel) {
            "gemini-3.5-flash-lite" -> liteStreamingModel
            "gemini-4-argon" -> argonStreamingModel
            else -> defaultStreamingModel
        }
        val chatHistory = history.takeLast(12).map { (role, text) ->
            content(if (role == "user") "user" else "model") { text(text) }
        }
        val chat = model.startChat(chatHistory)
        val fullPrompt = "Language preference: $languageLabel (reply in the exact language or mixed style the user uses).\n$prompt"
        val buffer = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                chat.sendMessageStream(fullPrompt).collect { chunk ->
                    val delta = chunk.text.orEmpty()
                    if (delta.isNotEmpty()) {
                        buffer.append(delta)
                        emit(buffer.toString())
                    }
                }
            }
            _onlineDiagnostic.value = "Connected ($configuredModel)"
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            if (isAuthOrAppCheckError(e)) {
                firebaseAiRejectedForSession = true
            }
            throw e
        }
    }

    private fun resolveEffectiveModel(profile: AiSpeedProfile, savedModel: String): String {
        return if (savedModel.isNotBlank()) savedModel else profile.modelId
    }

    private fun resolveTimeoutMs(profile: AiSpeedProfile): Long = when (profile) {
        AiSpeedProfile.TURBO -> 35_000L
        AiSpeedProfile.BALANCED -> 45_000L
        AiSpeedProfile.DEEP_THINK -> 60_000L
    }

    private fun parseWorkerError(code: Int, rawBody: String): String {
        val jsonMsg = runCatching {
            val obj = JSONObject(rawBody)
            val err = obj.optString("error").trim()
            val detail = obj.optString("provider_message").ifBlank { obj.optString("detail") }.trim()
            val pCode = obj.optString("provider_code").trim()
            when {
                pCode == "credit_balance_exhausted" || code == 429 ->
                    "Cloudflare gateway quota exhausted (HTTP $code)"
                err.isNotBlank() && detail.isNotBlank() -> "$err: ${detail.take(90)}"
                err.isNotBlank() -> "$err (HTTP $code)"
                else -> "Gateway HTTP $code"
            }
        }.getOrNull()
        return sanitizeOnlineError(jsonMsg ?: "Gateway HTTP $code")
    }

    private fun sanitizeOnlineError(raw: String): String {
        val clean = raw.replace(Regex("\\s+"), " ").trim()
        return clean.take(140).ifBlank { "Online connection error" }
    }

    private fun parseDecisionJson(rawJson: String): ReasoningDecision {
        val json = JSONObject(rawJson)
        val toolCalls = mutableListOf<PlannedToolCall>()
        val array = json.optJSONArray("toolCalls")
        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val toolId = obj.optString("toolId", "").trim()
                if (toolId.isNotBlank()) {
                    toolCalls += PlannedToolCall(
                        toolId = toolId,
                        args = parseArgsJson(obj.optString("argsJson", "{}")),
                        rationale = obj.optString("rationale", "")
                    )
                }
            }
        }
        return ReasoningDecision(
            thoughtSummary = json.optString("thoughtSummary", ""),
            directReply = json.optString("reply", "").trim().ifBlank { "Ready." },
            plannedTools = toolCalls,
            memoryCandidateTitle = "",
            memoryCandidateContent = "",
            nextActionNeeded = json.optBoolean("nextActionNeeded", false)
        )
    }

    private fun parseArgsJson(argsJson: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        runCatching {
            val obj = JSONObject(argsJson.ifBlank { "{}" })
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.opt(key)?.toString().orEmpty()
            }
        }
        return map
    }

    private fun buildPlannerPrompt(
        userMessage: String,
        history: List<Pair<String, String>>,
        memoryContext: String,
        toolCatalogJson: String,
        screenContext: String,
        languageLabel: String,
        speedProfile: AiSpeedProfile,
        userDisplayName: String
    ): String {
        val historyBlock = if (history.isEmpty()) "None" else history.takeLast(10).joinToString("\n") { "${it.first}: ${it.second}" }
        val userNameNote = if (userDisplayName.isNotBlank()) "User's given name: $userDisplayName\n" else ""
        val reasoningInstruction = when (speedProfile) {
            AiSpeedProfile.TURBO -> "Reason crisply and respond rapidly."
            AiSpeedProfile.BALANCED -> "Think analytically and verify dependencies before acting."
            AiSpeedProfile.DEEP_THINK -> "Perform thorough multi-step analysis in thoughtSummary before concluding or planning actions."
        }
        return """
You are ULTRA AI, a warm, intelligent, human-like Android assistant and autonomous Jarvis task agent similar to ChatGPT.
${userNameNote}Preferred language: $languageLabel (respond naturally in Bengali, Hindi, English, or Banglish matching the user; avoid Markdown symbols like ** or # so voice playback sounds natural).
$reasoningInstruction
For tasks inside an app (e.g. "open YouTube and search...", "open Facebook and scroll...", "open Settings and turn on..."):
- First call `open_app` (or `open_device_settings`) AND set `nextActionNeeded = true` so ULTRA AI waits for the app window, inspects Accessibility nodes, and executes follow-up clicks/typing/scrolling autonomously.
- Use `read_screen_aloud` when asked to read what is on screen or summarize the visible page/email.
- Use `scroll_screen` with `'down'`, `'up'`, `'left'`, or `'right'`.
- Email safety rules: use `manage_email` (`open_email`, `read_email`, `next_email`, `go_back`, `delete_email`). NEVER delete emails proactively and NEVER bulk-delete emails; only schedule `delete_email` when the user explicitly asks to delete a specific open email (the system will enforce confirmation).
- Context-aware "Next": if user is in an email screen, advance to the next email; if listening to media, skip track; if browsing feeds/videos, scroll or tap Next.
- Never claim you completed a device action unless you schedule the corresponding tool in toolCalls.

Temporary Session Context:
$memoryContext

Current Screen Context:
$screenContext

Recent Session Conversation:
$historyBlock

Available Registered Tools (JSON):
$toolCatalogJson

User Request:
$userMessage
        """.trimIndent()
    }

    private fun isAuthOrAppCheckError(e: Throwable): Boolean {
        val msg = e.localizedMessage.orEmpty().lowercase()
        return msg.contains("app check") ||
            msg.contains("appcheck") ||
            msg.contains("403") ||
            msg.contains("401") ||
            msg.contains("permission_denied") ||
            msg.contains("unauthenticated") ||
            msg.contains("api key") ||
            msg.contains("not enabled") ||
            msg.contains("firebase app")
    }

    companion object {
        const val GEMINI_TTS_MODEL = "gemini-3.1-flash-tts-preview"

        fun selectGeminiTtsVoiceName(gender: VoiceGender): String = when (gender) {
            VoiceGender.FEMALE -> "Sulafat"
            VoiceGender.MALE -> "Puck"
        }

        fun parseWorkerAgentResponse(rawBody: String): Result<Pair<String, ReasoningDecision>> {
            val trimmed = rawBody.trim()
            if (trimmed.isBlank()) {
                return Result.failure(IllegalStateException("Cloudflare Worker returned an empty response"))
            }
            val json = runCatching { JSONObject(trimmed) }.getOrNull()
                ?: return Result.success(
                    "Online • Cloudflare Worker" to ReasoningDecision(
                        thoughtSummary = "Analyzed via Cloudflare Worker",
                        directReply = trimmed.take(2000),
                        plannedTools = emptyList(),
                        nextActionNeeded = false
                    )
                )

            if (json.has("ok") && !json.optBoolean("ok", true)) {
                val err = json.optString("error", "Gateway error").replace(Regex("\\s+"), " ").trim().take(140)
                return Result.failure(IllegalStateException(err.ifBlank { "Gateway error" }))
            }
            val explicitError = json.optString("error", "").trim()
            val nestedResult = json.optJSONObject("result")
            val nestedData = json.optJSONObject("data")
            val openAiReply = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()
                .orEmpty()

            val replyText = sequenceOf(
                json.optString("reply", ""),
                json.optString("response", ""),
                json.optString("answer", ""),
                json.optString("text", ""),
                json.optString("message", ""),
                json.optString("output_text", ""),
                nestedResult?.optString("response", "").orEmpty(),
                nestedResult?.optString("reply", "").orEmpty(),
                nestedData?.optString("reply", "").orEmpty(),
                nestedData?.optString("response", "").orEmpty(),
                openAiReply
            ).map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

            if (replyText.isEmpty() && explicitError.isNotEmpty()) {
                return Result.failure(IllegalStateException(explicitError.take(140)))
            }

            val toolCalls = mutableListOf<PlannedToolCall>()
            val toolArray = json.optJSONArray("toolCalls")
            if (toolArray != null) {
                for (i in 0 until toolArray.length()) {
                    val obj = toolArray.optJSONObject(i) ?: continue
                    val toolId = obj.optString("toolId", "").trim()
                    if (toolId.isBlank()) continue
                    val argsObj = obj.optJSONObject("args")
                    val argsObjStr = when {
                        obj.has("argsJson") -> obj.optString("argsJson", "{}")
                        argsObj != null -> argsObj.toString()
                        else -> "{}"
                    }
                    val argsMap = mutableMapOf<String, String>()
                    runCatching {
                        val a = JSONObject(argsObjStr.ifBlank { "{}" })
                        val keys = a.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            argsMap[k] = a.opt(k)?.toString().orEmpty()
                        }
                    }
                    toolCalls += PlannedToolCall(
                        toolId = toolId,
                        args = argsMap,
                        rationale = obj.optString("rationale", "Worker planned tool")
                    )
                }
            }

            val providerName = json.optString("provider", "Cloudflare Worker").ifBlank { "Cloudflare Worker" }
            val thought = sequenceOf(
                json.optString("thoughtSummary", ""),
                json.optString("thought", ""),
                json.optString("reasoning", "")
            ).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "Analyzed via $providerName"

            val decision = ReasoningDecision(
                thoughtSummary = thought,
                directReply = replyText.ifBlank { "Done." },
                plannedTools = toolCalls,
                nextActionNeeded = json.optBoolean("nextActionNeeded", false)
            )
            return Result.success("Online • Cloudflare ($providerName)" to decision)
        }
    }
}
