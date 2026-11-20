package com.ultra.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.ultraDataStore: DataStore<Preferences> by preferencesDataStore(name = "ultra_preferences")

enum class AiProviderMode(val label: String) {
    AUTO("Auto (Firebase AI + Worker Fallback)"),
    FIREBASE_AI("Firebase AI Logic (Gemini)"),
    CLOUDFLARE_WORKER("Cloudflare Worker Gateway")
}

data class UltraSettings(
    val language: String = "বাংলা",
    val wakePhrase: String = "Hey Ultra",
    val voiceSpeed: Float = 0.96f,
    val voicePitch: Float = 1.0f,
    val aiProviderMode: AiProviderMode = AiProviderMode.AUTO,
    val geminiModel: String = "gemini-3.8-flash",
    val workerEndpoint: String = DEFAULT_WORKER_ENDPOINT,
    val cloudSyncEnabled: Boolean = true,
    val encryptCloudMemory: Boolean = true,
    val memoryTtlDays: Int = 30,
    val allowNotificationContext: Boolean = false,
    val requireSmsConfirmation: Boolean = true,
    val directSmsEnabled: Boolean = false,
    val batteryConsciousListening: Boolean = true
) {
    companion object {
        const val DEFAULT_WORKER_ENDPOINT = "https://ultra-ai-assistant.ultraloader.workers.dev"
    }
}

class LocalSettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("ultra_local_settings", Context.MODE_PRIVATE)

    // Synchronous accessors preserved for Service/Worker compatibility
    fun language(): String = prefs.getString("language", "বাংলা") ?: "বাংলা"

    fun setLanguage(v: String) {
        prefs.edit().putString("language", v).apply()
    }

    fun wakePhrase(): String = prefs.getString("wake_phrase", "Hey Ultra") ?: "Hey Ultra"

    fun voiceSpeed(): Float = prefs.getFloat("voice_speed", 0.96f).coerceIn(0.75f, 1.35f)

    fun voicePitch(): Float = prefs.getFloat("voice_pitch", 1.0f).coerceIn(0.75f, 1.35f)

    fun voiceName(): String? = prefs.getString("voice_name", null)

    fun setVoiceSpeedSync(speed: Float) {
        prefs.edit().putFloat("voice_speed", speed.coerceIn(0.75f, 1.35f)).apply()
    }

    fun setVoicePitchSync(pitch: Float) {
        prefs.edit().putFloat("voice_pitch", pitch.coerceIn(0.75f, 1.35f)).apply()
    }

    val settingsFlow: Flow<UltraSettings> = appContext.ultraDataStore.data.map { data ->
        val modeRaw = data[KEY_AI_PROVIDER] ?: AiProviderMode.AUTO.name
        val mode = enumValues<AiProviderMode>().firstOrNull { it.name == modeRaw } ?: AiProviderMode.AUTO
        UltraSettings(
            language = data[KEY_LANGUAGE] ?: language(),
            wakePhrase = data[KEY_WAKE_PHRASE] ?: wakePhrase(),
            voiceSpeed = (data[KEY_VOICE_SPEED] ?: voiceSpeed()).coerceIn(0.75f, 1.35f),
            voicePitch = (data[KEY_VOICE_PITCH] ?: voicePitch()).coerceIn(0.75f, 1.35f),
            aiProviderMode = mode,
            geminiModel = data[KEY_GEMINI_MODEL] ?: "gemini-3.8-flash",
            workerEndpoint = data[KEY_WORKER_ENDPOINT] ?: UltraSettings.DEFAULT_WORKER_ENDPOINT,
            cloudSyncEnabled = data[KEY_CLOUD_SYNC] ?: true,
            encryptCloudMemory = data[KEY_ENCRYPT_CLOUD] ?: true,
            memoryTtlDays = (data[KEY_MEMORY_TTL_DAYS] ?: 30).coerceIn(1, 365),
            allowNotificationContext = data[KEY_ALLOW_NOTIF_CTX] ?: false,
            requireSmsConfirmation = data[KEY_REQUIRE_SMS_CONFIRM] ?: true,
            directSmsEnabled = data[KEY_DIRECT_SMS] ?: false,
            batteryConsciousListening = data[KEY_BATTERY_CONSCIOUS] ?: true
        )
    }

    suspend fun updateSettings(transform: (UltraSettings) -> UltraSettings) {
        appContext.ultraDataStore.edit { data ->
            val current = UltraSettings(
                language = data[KEY_LANGUAGE] ?: language(),
                wakePhrase = data[KEY_WAKE_PHRASE] ?: wakePhrase(),
                voiceSpeed = data[KEY_VOICE_SPEED] ?: voiceSpeed(),
                voicePitch = data[KEY_VOICE_PITCH] ?: voicePitch(),
                aiProviderMode = enumValues<AiProviderMode>().firstOrNull { it.name == data[KEY_AI_PROVIDER] } ?: AiProviderMode.AUTO,
                geminiModel = data[KEY_GEMINI_MODEL] ?: "gemini-3.8-flash",
                workerEndpoint = data[KEY_WORKER_ENDPOINT] ?: UltraSettings.DEFAULT_WORKER_ENDPOINT,
                cloudSyncEnabled = data[KEY_CLOUD_SYNC] ?: true,
                encryptCloudMemory = data[KEY_ENCRYPT_CLOUD] ?: true,
                memoryTtlDays = data[KEY_MEMORY_TTL_DAYS] ?: 30,
                allowNotificationContext = data[KEY_ALLOW_NOTIF_CTX] ?: false,
                requireSmsConfirmation = data[KEY_REQUIRE_SMS_CONFIRM] ?: true,
                directSmsEnabled = data[KEY_DIRECT_SMS] ?: false,
                batteryConsciousListening = data[KEY_BATTERY_CONSCIOUS] ?: true
            )
            val next = transform(current)
            data[KEY_LANGUAGE] = next.language
            data[KEY_WAKE_PHRASE] = next.wakePhrase.trim().ifBlank { "Hey Ultra" }
            data[KEY_VOICE_SPEED] = next.voiceSpeed.coerceIn(0.75f, 1.35f)
            data[KEY_VOICE_PITCH] = next.voicePitch.coerceIn(0.75f, 1.35f)
            data[KEY_AI_PROVIDER] = next.aiProviderMode.name
            data[KEY_GEMINI_MODEL] = next.geminiModel
            data[KEY_WORKER_ENDPOINT] = next.workerEndpoint.trim().ifBlank { UltraSettings.DEFAULT_WORKER_ENDPOINT }
            data[KEY_CLOUD_SYNC] = next.cloudSyncEnabled
            data[KEY_ENCRYPT_CLOUD] = next.encryptCloudMemory
            data[KEY_MEMORY_TTL_DAYS] = next.memoryTtlDays.coerceIn(1, 365)
            data[KEY_ALLOW_NOTIF_CTX] = next.allowNotificationContext
            data[KEY_REQUIRE_SMS_CONFIRM] = next.requireSmsConfirmation
            data[KEY_DIRECT_SMS] = next.directSmsEnabled
            data[KEY_BATTERY_CONSCIOUS] = next.batteryConsciousListening

            // Keep synchronous SharedPreferences mirror updated for VoiceAssistantService
            prefs.edit()
                .putString("language", next.language)
                .putString("wake_phrase", next.wakePhrase.trim().ifBlank { "Hey Ultra" })
                .putFloat("voice_speed", next.voiceSpeed.coerceIn(0.75f, 1.35f))
                .putFloat("voice_pitch", next.voicePitch.coerceIn(0.75f, 1.35f))
                .apply()
        }
    }

    private companion object {
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_WAKE_PHRASE = stringPreferencesKey("wake_phrase")
        val KEY_VOICE_SPEED = floatPreferencesKey("voice_speed")
        val KEY_VOICE_PITCH = floatPreferencesKey("voice_pitch")
        val KEY_AI_PROVIDER = stringPreferencesKey("ai_provider_mode")
        val KEY_GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val KEY_WORKER_ENDPOINT = stringPreferencesKey("worker_endpoint")
        val KEY_CLOUD_SYNC = booleanPreferencesKey("cloud_sync_enabled")
        val KEY_ENCRYPT_CLOUD = booleanPreferencesKey("encrypt_cloud_memory")
        val KEY_MEMORY_TTL_DAYS = intPreferencesKey("memory_ttl_days")
        val KEY_ALLOW_NOTIF_CTX = booleanPreferencesKey("allow_notification_context")
        val KEY_REQUIRE_SMS_CONFIRM = booleanPreferencesKey("require_sms_confirmation")
        val KEY_DIRECT_SMS = booleanPreferencesKey("direct_sms_enabled")
        val KEY_BATTERY_CONSCIOUS = booleanPreferencesKey("battery_conscious_listening")
    }
}
