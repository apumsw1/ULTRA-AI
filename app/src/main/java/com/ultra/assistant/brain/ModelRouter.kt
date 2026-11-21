package com.ultra.assistant.brain

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.ultra.assistant.data.AiProviderMode
import com.ultra.assistant.data.LocalSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
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
    private val settingsRepository: LocalSettingsRepository
) : AIEngine {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val decisionSchema = Schema.obj(
        properties = mapOf(
            "thoughtSummary" to Schema.string(description = "Brief reasoning summary"),
            "reply" to Schema.string(description = "Natural concise response in user's language without Markdown"),
            "toolCalls" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "toolId" to Schema.string(description = "Registered tool id"),
                        "rationale" to Schema.string(description = "Why this tool is needed"),
                        "argsJson" to Schema.string(description = "JSON object string of arguments")
                    )
                ),
                description = "Zero or more ordered tool calls to satisfy the request"
            ),
            "memoryTitle" to Schema.string(description = "Optional short user preference/fact title to remember"),
            "memoryContent" to Schema.string(description = "Optional non-sensitive user preference/fact content")
        ),
        optionalProperties = listOf("memoryTitle", "memoryContent")
    )

    private val defaultGeminiPlanner by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = decisionSchema
            }
        )
    }

    private val liteGeminiPlanner by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = decisionSchema
            }
        )
    }

    private val argonGeminiPlanner by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-4-argon",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = decisionSchema
            }
        )
    }

    private val streamingGeminiModel by lazy {
        Firebase.ai.generativeModel(modelName = "gemini-3.8-flash")
    }

    override suspend fun reasonAndPlan(
        userMessage: String,
        history: List<Pair<String, String>>,
        memoryContext: String,
        toolCatalogJson: String,
        screenContext: String,
        languageLabel: String
    ): Result<Pair<String, ReasoningDecision>> {
        val settings = settingsRepository.settingsFlow.first()
        val prompt = buildPlannerPrompt(userMessage, history, memoryContext, toolCatalogJson, screenContext, languageLabel)

        return when (settings.aiProviderMode) {
            AiProviderMode.CLOUDFLARE_WORKER -> {
                val workerRes = callCloudflareWorkerAgent(settings.workerEndpoint, userMessage, history, toolCatalogJson, languageLabel)
                if (workerRes.isSuccess) workerRes else callFirebaseAiPlanner(settings.geminiModel, prompt)
            }
            AiProviderMode.FIREBASE_AI -> {
                callFirebaseAiPlanner(settings.geminiModel, prompt)
            }
            AiProviderMode.AUTO -> {
                val aiRes = callFirebaseAiPlanner(settings.geminiModel, prompt)
                if (aiRes.isSuccess) aiRes else callCloudflareWorkerAgent(settings.workerEndpoint, userMessage, history, toolCatalogJson, languageLabel)
            }
        }
    }

    private suspend fun callFirebaseAiPlanner(
        configuredModel: String,
        prompt: String
    ): Result<Pair<String, ReasoningDecision>> {
        val firebaseReady = runCatching { com.google.firebase.FirebaseApp.getInstance() }.isSuccess
        if (!firebaseReady) {
            return Result.failure(IllegalStateException("Firebase AI is not configured in this build (missing google-services.json)."))
        }
        val model = when (configuredModel) {
            "gemini-3.5-flash-lite" -> liteGeminiPlanner
            "gemini-4-argon" -> argonGeminiPlanner
            else -> defaultGeminiPlanner
        }
        return try {
            val response = withTimeout(60_000L) {
                model.generateContent(prompt)
            }
            val rawJson = response.text ?: return Result.failure(IllegalStateException("Gemini returned empty text."))
            val decision = parseDecisionJson(rawJson)
            Result.success("Firebase AI ($configuredModel)" to decision)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini planning timed out", e)
            Result.failure(IllegalStateException("AI request timed out after 60s. Please retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI Logic request failed", e)
            Result.failure(e)
        }
    }

    private suspend fun callCloudflareWorkerAgent(
        baseUrl: String,
        userMessage: String,
        history: List<Pair<String, String>>,
        toolCatalogJson: String,
        languageLabel: String
    ): Result<Pair<String, ReasoningDecision>> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = baseUrl.trim().trimEnd('/')
            val endpoint = "$cleanBase/api/voice"
            val historyArray = JSONArray().apply {
                history.takeLast(8).forEach { (role, content) ->
                    put(JSONObject().put("role", role).put("content", content.take(1200)))
                }
            }
            val bodyJson = JSONObject().apply {
                put("message", userMessage)
                put("language", languageLabel)
                put("history", historyArray)
                put("tools", toolCatalogJson)
            }
            val request = Request.Builder()
                .url(endpoint)
                .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("Worker HTTP ${response.code}"))
                }
                val json = JSONObject(raw)
                if (!json.optBoolean("ok", false)) {
                    return@withContext Result.failure(IllegalStateException(json.optString("error", "Worker error")))
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
                val decision = ReasoningDecision(
                    thoughtSummary = json.optString("thoughtSummary", "Cloudflare Worker Gateway response"),
                    directReply = reply.ifBlank { "Done." },
                    plannedTools = toolCalls
                )
                Result.success("Cloudflare Worker" to decision)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cloudflare Worker fallback failed: ${e.message}")
            Result.failure(e)
        }
    }

    override fun streamConversation(
        prompt: String,
        history: List<Pair<String, String>>,
        languageLabel: String
    ): Flow<String> = flow {
        val firebaseReady = runCatching { com.google.firebase.FirebaseApp.getInstance() }.isSuccess
        if (!firebaseReady) {
            emit("Firebase AI is unavailable in this build. Configure google-services.json or use Cloudflare Worker mode.")
            return@flow
        }
        val fullPrompt = "You are ULTRA AI, a helpful multilingual assistant ($languageLabel). Keep replies concise and natural.\n$prompt"
        val chatHistory = history.takeLast(6).map { (role, text) ->
            content(if (role == "user") "user" else "model") { text(text) }
        }
        val chat = streamingGeminiModel.startChat(chatHistory)
        val buffer = StringBuilder()
        withTimeout(60_000L) {
            chat.sendMessageStream(fullPrompt).collect { chunk ->
                val delta = chunk.text.orEmpty()
                if (delta.isNotEmpty()) {
                    buffer.append(delta)
                    emit(buffer.toString())
                }
            }
        }
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
            memoryCandidateTitle = json.optString("memoryTitle", "").trim(),
            memoryCandidateContent = json.optString("memoryContent", "").trim()
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
        languageLabel: String
    ): String {
        val historyBlock = if (history.isEmpty()) "None" else history.takeLast(6).joinToString("\n") { "${it.first}: ${it.second}" }
        return """
You are ULTRA AI, a JARVIS-style Android AI Agent.
Preferred language: $languageLabel (respond in the user's language: Bengali, Hindi, or English; avoid Markdown in spoken replies).
Never claim you completed a device action unless you schedule the corresponding tool in toolCalls.
Never invent shell commands or arbitrary code. Only use registered tools from the catalog.
Do not store sensitive OTPs, passwords, or private SMS/notification contents in memoryTitle/memoryContent.

Active Memories:
$memoryContext

Current Screen Context:
$screenContext

Recent Conversation:
$historyBlock

Available Registered Tools (JSON):
$toolCatalogJson

User Request:
$userMessage
        """.trimIndent()
    }
}
