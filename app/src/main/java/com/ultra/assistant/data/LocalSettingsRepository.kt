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

enum class AiOperatingMode(val label: String) {
    AUTO("Auto (Online + Offline Fallback)"),
    ONLINE("Online AI Mode"),
    OFFLINE("Offline Local Mode")
}

enum class VoiceGender(val label: String) {
    FEMALE("Female Voice"),
    MALE("Male Voice")
}

enum class ChatBackgroundStyle(val label: String) {
    NEURAL_COSMOS("Neural Cosmos"),
    QUANTUM_GRID("Quantum Grid"),
    AURORA_SILK("Aurora Silk"),
    PURE_OBSIDIAN("Pure Obsidian")
}

enum class UltraThemeStyle(val label: String) {
    CYBER_CYAN("Cyber Cyan"),
    EMERALD_PULSE("Emerald Pulse"),
    AURORA_VIOLET("Aurora Violet")
}

data class UltraSettings(
    val language: String = "English",
    val wakePhrase: String = "Hey Ultra",
    val voiceGender: VoiceGender = VoiceGender.FEMALE,
    val voiceSpeed: Float = 1.0f,
    val voicePitch: Float = 1.0f,
    val voiceVolume: Float = 0.95f,
    val operatingMode: AiOperatingMode = AiOperatingMode.AUTO,
    val aiProviderMode: AiProviderMode = AiProviderMode.AUTO,
    val geminiModel: String = "gemini-3.8-flash",
    val workerEndpoint: String = DEFAULT_WORKER_ENDPOINT,
    val backgroundStyle: ChatBackgroundStyle = ChatBackgroundStyle.NEURAL_COSMOS,
    val uiThemeStyle: UltraThemeStyle = UltraThemeStyle.CYBER_CYAN,
    val floatingAssistantEnabled: Boolean = false,
    val continuousListening: Boolean = true,
    val ambientEdgeGlowEnabled: Boolean = true,
    val cloudSyncEnabled: Boolean = true,
    val encryptCloudMemory: Boolean = true,
    val memoryTtlDays: Int = 30,
    val allowNotificationContext: Boolean = false,
    val requireSmsConfirmation: Boolean = true,
    val requireCallConfirmation: Boolean = true,
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

    // Synchronous accessors preserved for VoiceAssistantService & Broadcast/Overlay updates
    fun language(): String = prefs.getString("language", "English") ?: "English"

    fun setLanguage(v: String) {
        prefs.edit().putString("language", v).apply()
    }

    fun wakePhrase(): String = prefs.getString("wake_phrase", "Hey Ultra") ?: "Hey Ultra"

    fun voiceGender(): VoiceGender =
        enumValues<VoiceGender>().firstOrNull { it.name == prefs.getString("voice_gender", VoiceGender.FEMALE.name) }
            ?: VoiceGender.FEMALE

    fun voiceSpeed(): Float = prefs.getFloat("voice_speed", 1.0f).coerceIn(0.75f, 1.35f)

    fun voicePitch(): Float = prefs.getFloat("voice_pitch", 1.0f).coerceIn(0.75f, 1.35f)

    fun voiceVolume(): Float = prefs.getFloat("voice_volume", 0.95f).coerceIn(0.1f, 1.0f)

    fun continuousListening(): Boolean = prefs.getBoolean("continuous_listening", true)

    fun floatingAssistantEnabled(): Boolean = prefs.getBoolean("floating_assistant", false)

    fun ambientEdgeGlowEnabled(): Boolean = prefs.getBoolean("ambient_edge_glow", true)

    fun operatingMode(): AiOperatingMode =
        enumValues<AiOperatingMode>().firstOrNull { it.name == prefs.getString("operating_mode", AiOperatingMode.AUTO.name) }
            ?: AiOperatingMode.AUTO

    fun voiceName(): String? = prefs.getString("voice_name", null)

    fun setVoiceSpeedSync(speed: Float) {
        prefs.edit().putFloat("voice_speed", speed.coerceIn(0.75f, 1.35f)).apply()
    }

    fun setVoicePitchSync(pitch: Float) {
        prefs.edit().putFloat("voice_pitch", pitch.coerceIn(0.75f, 1.35f)).apply()
    }

    fun setFloatingAssistantEnabledSync(enabled: Boolean) {
        prefs.edit().putBoolean("floating_assistant", enabled).apply()
    }

    val settingsFlow: Flow<UltraSettings> = appContext.ultraDataStore.data.map { data ->
        parseSettings(data)
    }

    suspend fun updateSettings(transform: (UltraSettings) -> UltraSettings) {
        appContext.ultraDataStore.edit { data ->
            val current = parseSettings(data)
            val next = transform(current)
            data[KEY_LANGUAGE] = next.language
            data[KEY_WAKE_PHRASE] = next.wakePhrase.trim().ifBlank { "Hey Ultra" }
            data[KEY_VOICE_GENDER] = next.voiceGender.name
            data[KEY_VOICE_SPEED] = next.voiceSpeed.coerceIn(0.75f, 1.35f)
            data[KEY_VOICE_PITCH] = next.voicePitch.coerceIn(0.75f, 1.35f)
            data[KEY_VOICE_VOLUME] = next.voiceVolume.coerceIn(0.1f, 1.0f)
            data[KEY_OPERATING_MODE] = next.operatingMode.name
            data[KEY_AI_PROVIDER] = next.aiProviderMode.name
            data[KEY_GEMINI_MODEL] = next.geminiModel
            data[KEY_WORKER_ENDPOINT] = next.workerEndpoint.trim().ifBlank { UltraSettings.DEFAULT_WORKER_ENDPOINT }
            data[KEY_BG_STYLE] = next.backgroundStyle.name
            data[KEY_THEME_STYLE] = next.uiThemeStyle.name
            data[KEY_FLOATING_ASSISTANT] = next.floatingAssistantEnabled
            data[KEY_CONTINUOUS_LISTENING] = next.continuousListening
            data[KEY_AMBIENT_EDGE_GLOW] = next.ambientEdgeGlowEnabled
            data[KEY_CLOUD_SYNC] = next.cloudSyncEnabled
            data[KEY_ENCRYPT_CLOUD] = next.encryptCloudMemory
            data[KEY_MEMORY_TTL_DAYS] = next.memoryTtlDays.coerceIn(1, 365)
            data[KEY_ALLOW_NOTIF_CTX] = next.allowNotificationContext
            data[KEY_REQUIRE_SMS_CONFIRM] = next.requireSmsConfirmation
            data[KEY_REQUIRE_CALL_CONFIRM] = next.requireCallConfirmation
            data[KEY_DIRECT_SMS] = next.directSmsEnabled
            data[KEY_BATTERY_CONSCIOUS] = next.batteryConsciousListening

            prefs.edit()
                .putString("language", next.language)
                .putString("wake_phrase", next.wakePhrase.trim().ifBlank { "Hey Ultra" })
                .putString("voice_gender", next.voiceGender.name)
                .putFloat("voice_speed", next.voiceSpeed.coerceIn(0.75f, 1.35f))
                .putFloat("voice_pitch", next.voicePitch.coerceIn(0.75f, 1.35f))
                .putFloat("voice_volume", next.voiceVolume.coerceIn(0.1f, 1.0f))
                .putString("operating_mode", next.operatingMode.name)
                .putBoolean("continuous_listening", next.continuousListening)
                .putBoolean("floating_assistant", next.floatingAssistantEnabled)
                .putBoolean("ambient_edge_glow", next.ambientEdgeGlowEnabled)
                .apply()
        }
    }

    private fun parseSettings(data: Preferences): UltraSettings {
        val provider = enumValues<AiProviderMode>().firstOrNull { it.name == data[KEY_AI_PROVIDER] }
            ?: AiProviderMode.AUTO
        val opMode = enumValues<AiOperatingMode>().firstOrNull { it.name == data[KEY_OPERATING_MODE] }
            ?: operatingMode()
        val gender = enumValues<VoiceGender>().firstOrNull { it.name == data[KEY_VOICE_GENDER] }
            ?: voiceGender()
        val bg = enumValues<ChatBackgroundStyle>().firstOrNull { it.name == data[KEY_BG_STYLE] }
            ?: ChatBackgroundStyle.NEURAL_COSMOS
        val theme = enumValues<UltraThemeStyle>().firstOrNull { it.name == data[KEY_THEME_STYLE] }
            ?: UltraThemeStyle.CYBER_CYAN
        return UltraSettings(
            language = data[KEY_LANGUAGE] ?: language(),
            wakePhrase = data[KEY_WAKE_PHRASE] ?: wakePhrase(),
            voiceGender = gender,
            voiceSpeed = (data[KEY_VOICE_SPEED] ?: voiceSpeed()).coerceIn(0.75f, 1.35f),
            voicePitch = (data[KEY_VOICE_PITCH] ?: voicePitch()).coerceIn(0.75f, 1.35f),
            voiceVolume = (data[KEY_VOICE_VOLUME] ?: voiceVolume()).coerceIn(0.1f, 1.0f),
            operatingMode = opMode,
            aiProviderMode = provider,
            geminiModel = data[KEY_GEMINI_MODEL] ?: "gemini-3.8-flash",
            workerEndpoint = data[KEY_WORKER_ENDPOINT] ?: UltraSettings.DEFAULT_WORKER_ENDPOINT,
            backgroundStyle = bg,
            uiThemeStyle = theme,
            floatingAssistantEnabled = data[KEY_FLOATING_ASSISTANT] ?: floatingAssistantEnabled(),
            continuousListening = data[KEY_CONTINUOUS_LISTENING] ?: continuousListening(),
            ambientEdgeGlowEnabled = data[KEY_AMBIENT_EDGE_GLOW] ?: ambientEdgeGlowEnabled(),
            cloudSyncEnabled = data[KEY_CLOUD_SYNC] ?: true,
            encryptCloudMemory = data[KEY_ENCRYPT_CLOUD] ?: true,
            memoryTtlDays = (data[KEY_MEMORY_TTL_DAYS] ?: 30).coerceIn(1, 365),
            allowNotificationContext = data[KEY_ALLOW_NOTIF_CTX] ?: false,
            requireSmsConfirmation = data[KEY_REQUIRE_SMS_CONFIRM] ?: true,
            requireCallConfirmation = data[KEY_REQUIRE_CALL_CONFIRM] ?: true,
            directSmsEnabled = data[KEY_DIRECT_SMS] ?: false,
            batteryConsciousListening = data[KEY_BATTERY_CONSCIOUS] ?: true
        )
    }

    private companion object {
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_WAKE_PHRASE = stringPreferencesKey("wake_phrase")
        val KEY_VOICE_GENDER = stringPreferencesKey("voice_gender")
        val KEY_VOICE_SPEED = floatPreferencesKey("voice_speed")
        val KEY_VOICE_PITCH = floatPreferencesKey("voice_pitch")
        val KEY_VOICE_VOLUME = floatPreferencesKey("voice_volume")
        val KEY_OPERATING_MODE = stringPreferencesKey("operating_mode")
        val KEY_AI_PROVIDER = stringPreferencesKey("ai_provider_mode")
        val KEY_GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val KEY_WORKER_ENDPOINT = stringPreferencesKey("worker_endpoint")
        val KEY_BG_STYLE = stringPreferencesKey("background_style")
        val KEY_THEME_STYLE = stringPreferencesKey("ui_theme_style")
        val KEY_FLOATING_ASSISTANT = booleanPreferencesKey("floating_assistant")
        val KEY_CONTINUOUS_LISTENING = booleanPreferencesKey("continuous_listening")
        val KEY_AMBIENT_EDGE_GLOW = booleanPreferencesKey("ambient_edge_glow")
        val KEY_CLOUD_SYNC = booleanPreferencesKey("cloud_sync_enabled")
        val KEY_ENCRYPT_CLOUD = booleanPreferencesKey("encrypt_cloud_memory")
        val KEY_MEMORY_TTL_DAYS = intPreferencesKey("memory_ttl_days")
        val KEY_ALLOW_NOTIF_CTX = booleanPreferencesKey("allow_notification_context")
        val KEY_REQUIRE_SMS_CONFIRM = booleanPreferencesKey("require_sms_confirmation")
        val KEY_REQUIRE_CALL_CONFIRM = booleanPreferencesKey("require_call_confirmation")
        val KEY_DIRECT_SMS = booleanPreferencesKey("direct_sms_enabled")
        val KEY_BATTERY_CONSCIOUS = booleanPreferencesKey("battery_conscious_listening")
    }
}
