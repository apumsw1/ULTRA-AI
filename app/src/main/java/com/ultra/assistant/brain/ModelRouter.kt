package com.ultra.assistant.brain

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.ultra.assistant.data.AiProviderMode
import com.ultra.assistant.data.AiSpeedProfile
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.data.NetworkMonitor
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
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _onlineDiagnostic = MutableStateFlow("")
    val onlineDiagnosticFlow: StateFlow<String> = _onlineDiagnostic.asStateFlow()

    fun clearTransientDiagnostic() {
        _onlineDiagnostic.value = ""
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

    private val defaultGeminiPlanner by lazy { createPlannerModel("gemini-3.8-flash") }
    private val liteGeminiPlanner by lazy { createPlannerModel("gemini-3.5-flash-lite") }
    private val argonGeminiPlanner by lazy { createPlannerModel("gemini-4-argon") }

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
                val workerRes = callCloudflareWorkerAgent(
                    baseUrl = workerUrl,
                    authToken = workerToken,
                    userMessage = userMessage,
                    history = history,
                    toolCatalogJson = toolCatalogJson,
                    screenContext = screenContext,
                    languageLabel = languageLabel
                )
                if (workerRes.isSuccess) workerRes else callFirebaseAiPlanner(effectiveModel, timeoutMs, prompt)
            }
            AiProviderMode.FIREBASE_AI -> {
                callFirebaseAiPlanner(effectiveModel, timeoutMs, prompt)
            }
            AiProviderMode.AUTO -> {
                val aiRes = callFirebaseAiPlanner(effectiveModel, timeoutMs, prompt)
                if (aiRes.isSuccess) {
                    aiRes
                } else {
                    if (networkMonitor != null && !networkMonitor.checkCurrentConnectivity()) {
                        return aiRes
                    }
                    callCloudflareWorkerAgent(
                        baseUrl = workerUrl,
                        authToken = workerToken,
                        userMessage = userMessage,
                        history = history,
                        toolCatalogJson = toolCatalogJson,
                        screenContext = screenContext,
                        languageLabel = languageLabel
                    ).recoverCatching { workerErr ->
                        val aiErr = aiRes.exceptionOrNull()?.message ?: "Firebase AI unavailable"
                        throw IllegalStateException("Online AI temporarily unavailable ($aiErr; Gateway: ${workerErr.message})")
                    }
                }
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
            val msg = sanitizeOnlineError(e.localizedMessage ?: "Firebase AI error")
            _onlineDiagnostic.value = msg
            Log.e(TAG, "Firebase AI Logic request failed", e)
            Result.failure(IllegalStateException(msg))
        }
    }

    private suspend fun callCloudflareWorkerAgent(
        baseUrl: String,
        authToken: String,
        userMessage: String,
        history: List<Pair<String, String>>,
        toolCatalogJson: String,
        screenContext: String,
        languageLabel: String
    ): Result<Pair<String, ReasoningDecision>> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = baseUrl.trim().trimEnd('/')
            if (cleanBase.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Cloudflare Worker URL is empty"))
            }
            val endpoint = "$cleanBase/api/voice"
            val historyArray = JSONArray().apply {
                history.takeLast(10).forEach { (role, content) ->
                    put(JSONObject().put("role", role).put("content", content.take(1500)))
                }
            }
            val bodyJson = JSONObject().apply {
                put("message", userMessage)
                put("language", languageLabel)
                put("history", historyArray)
                put("tools", toolCatalogJson)
                if (screenContext.isNotBlank()) {
                    put("screenContext", screenContext)
                }
            }
            val reqBuilder = Request.Builder()
                .url(endpoint)
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
                val json = JSONObject(raw)
                if (!json.optBoolean("ok", false)) {
                    val err = sanitizeOnlineError(json.optString("error", "Gateway error"))
                    _onlineDiagnostic.value = err
                    return@withContext Result.failure(IllegalStateException(err))
                }
                val reply = json.optString("reply", "").trim()
                val toolCalls = mutableListOf<PlannedToolCall>()
                val toolArray = json.optJSONArray("toolCalls")
                if (toolArray != null) {
                    for (i in 0 until toolArray.length()) {
                        val obj = toolArray.optJSONObject(i) ?: continue
                        val argsMap = parseArgsJson(obj.optString("argsJson", "{}"))
                        toolCalls += PlannedToolCall(
                            toolId = obj.optString("toolId"),
                            args = argsMap,
                            rationale = obj.optString("rationale", "Worker planned tool")
                        )
                    }
                }
                val providerName = json.optString("provider", "Cloudflare Worker")
                val decision = ReasoningDecision(
                    thoughtSummary = json.optString("thoughtSummary", "Analyzed via $providerName"),
                    directReply = reply.ifBlank { "Done." },
                    plannedTools = toolCalls,
                    nextActionNeeded = json.optBoolean("nextActionNeeded", false)
                )
                val label = "Online • Cloudflare ($providerName)"
                _onlineDiagnostic.value = "Connected ($label)"
                Result.success(label to decision)
            }
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
            val cleanBase = baseUrl.trim().trimEnd('/')
            require(cleanBase.startsWith("http://") || cleanBase.startsWith("https://")) {
                "Worker URL must start with https://"
            }
            val reqBuilder = Request.Builder().url("$cleanBase/health").get()
            if (authToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${authToken.trim()}")
            }
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(parseWorkerError(response.code, raw))
                }
                val json = JSONObject(raw)
                val providers = json.optJSONObject("providers")
                val summary = if (providers != null) {
                    "Workers AI=${providers.optBoolean("workersAi")}, Gemini=${providers.optBoolean("gemini")}, OpenAI=${providers.optBoolean("openai")}"
                } else {
                    json.optString("service", "Healthy")
                }
                _onlineDiagnostic.value = "Health OK ($summary)"
                "Cloudflare Gateway Ready • $summary"
            }
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
        if (!firebaseReady || settings.aiProviderMode == AiProviderMode.CLOUDFLARE_WORKER) {
            val workerRes = callCloudflareWorkerAgent(
                baseUrl = settings.workerEndpoint,
                authToken = settings.workerAuthToken,
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
                onFailure = { err ->
                    if (firebaseReady && settings.aiProviderMode == AiProviderMode.AUTO) {
                        streamFromGemini(effectiveModel, timeoutMs, prompt, history, languageLabel).collect { emit(it) }
                    } else {
                        throw err
                    }
                }
            )
            return@flow
        }

        try {
            streamFromGemini(effectiveModel, timeoutMs, prompt, history, languageLabel).collect { emit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Streaming via Firebase AI failed, checking fallback", e)
            if (settings.aiProviderMode == AiProviderMode.AUTO &&
                (networkMonitor == null || networkMonitor.checkCurrentConnectivity())
            ) {
                val workerRes = callCloudflareWorkerAgent(
                    baseUrl = settings.workerEndpoint,
                    authToken = settings.workerAuthToken,
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
                    onFailure = { throw e }
                )
            } else {
                throw e
            }
        }
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
    }

    private fun resolveEffectiveModel(profile: AiSpeedProfile, savedModel: String): String {
        return if (savedModel.isNotBlank()) savedModel else profile.modelId
    }

    private fun resolveTimeoutMs(profile: AiSpeedProfile): Long = when (profile) {
        AiSpeedProfile.TURBO -> 30_000L
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
- First call `open_app` (or `open_device_settings`) AND set `nextActionNeeded = true` so ULTRA AI waits for the app window, inspects Accessibility nodes, and executes the follow-up clicks/typing/scrolling autonomously.
- For direct YouTube video queries where fast deep-link works, `search_youtube` may also be used.
Never claim you completed a device action unless you schedule the corresponding tool in toolCalls.

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
}
