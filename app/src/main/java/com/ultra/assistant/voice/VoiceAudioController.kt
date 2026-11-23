package com.ultra.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.ultra.assistant.data.VoiceGender
import java.util.Locale

class VoiceAudioController(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null

    fun requestSpeechFocus(): Boolean {
        val am = audioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    fun abandonSpeechFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(null) }
        }
    }

    fun configureTts(
        tts: TextToSpeech,
        languageSetting: String,
        gender: VoiceGender,
        speed: Float,
        userPitch: Float,
        preferOffline: Boolean,
        explicitVoiceName: String? = null
    ) {
        val locale = LanguageManager.locale(languageSetting)
        tts.language = locale
        tts.setSpeechRate(speed.coerceIn(0.75f, 1.35f))

        val selectedVoice = selectVoice(
            voices = tts.voices.orEmpty(),
            locale = locale,
            gender = gender,
            preferOffline = preferOffline,
            explicitVoiceName = explicitVoiceName
        )
        if (selectedVoice != null) {
            runCatching { tts.voice = selectedVoice }
        }

        // Apply subtle warm pitch differentiation even if the device lacks separate male/female voice packs
        val genderFactor = when (gender) {
            VoiceGender.FEMALE -> 1.06f
            VoiceGender.MALE -> 0.86f
        }
        val effectivePitch = (userPitch * genderFactor).coerceIn(0.68f, 1.45f)
        tts.setPitch(effectivePitch)
    }

    fun speakNaturally(
        tts: TextToSpeech,
        rawText: String,
        volume: Float,
        utterancePrefix: String = "ultra_reply"
    ): String? {
        val clean = sanitizeForSpeech(rawText)
        if (clean.isBlank()) return null

        val chunks = splitNaturalSentences(clean, maxChunkChars = 220)
        if (chunks.isEmpty()) return null

        val clampedVolume = volume.coerceIn(0.1f, 1.0f)
        var lastUtteranceId: String? = null

        chunks.forEachIndexed { index, chunk ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val uttId = "${utterancePrefix}_${System.currentTimeMillis()}_$index"
            lastUtteranceId = uttId
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, clampedVolume)
            }
            val result = tts.speak(chunk, queueMode, params, uttId)
            if (result != TextToSpeech.SUCCESS) {
                return null
            }
            if (index < chunks.lastIndex) {
                tts.playSilentUtterance(150L, TextToSpeech.QUEUE_ADD, "${uttId}_pause")
            }
        }
        return lastUtteranceId
    }

    fun sanitizeForSpeech(raw: String): String {
        return raw
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[#>*•]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun splitNaturalSentences(text: String, maxChunkChars: Int): List<String> {
        val bounded = text.take(1500)
        val rawSentences = bounded
            .split(Regex("(?<=[.!?।\\n])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (rawSentences.isEmpty()) return listOf(bounded)

        val merged = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in rawSentences) {
            if (sentence.length > maxChunkChars) {
                if (current.isNotEmpty()) {
                    merged += current.toString().trim()
                    current.clear()
                }
                val clauses = sentence.split(Regex("(?<=[,;:])\\s+"))
                for (clause in clauses) {
                    if (current.length + clause.length + 1 > maxChunkChars && current.isNotEmpty()) {
                        merged += current.toString().trim()
                        current.clear()
                    }
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(clause.take(maxChunkChars))
                }
            } else if (current.length + sentence.length + 1 > maxChunkChars && current.isNotEmpty()) {
                merged += current.toString().trim()
                current.clear()
                current.append(sentence)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }
        if (current.isNotEmpty()) {
            merged += current.toString().trim()
        }
        return merged
    }

    private fun selectVoice(
        voices: Set<Voice>,
        locale: Locale,
        gender: VoiceGender,
        preferOffline: Boolean,
        explicitVoiceName: String?
    ): Voice? {
        if (voices.isEmpty()) return null

        if (!explicitVoiceName.isNullOrBlank()) {
            voices.firstOrNull {
                it.name == explicitVoiceName && (!preferOffline || !it.isNetworkConnectionRequired)
            }?.let { return it }
        }

        val langCode = locale.language.lowercase()
        val matchingLang = voices.filter { voice ->
            voice.locale.language.equals(langCode, ignoreCase = true) &&
                (!preferOffline || !voice.isNetworkConnectionRequired)
        }.ifEmpty {
            voices.filter { it.locale.language.equals(langCode, ignoreCase = true) }
        }

        if (matchingLang.isEmpty()) return null

        val femaleHints = listOf("female", "woman", "sfg", "tpf", "iog", "tpc", "x-bn-a", "x-bn-b", "x-hia", "x-hid", "neural2-c", "neural2-f")
        val maleHints = listOf("male", "man", "iom", "iol", "tpd", "x-bn-d", "x-hib", "x-hic", "neural2-d", "neural2-a", "neural2-j")
        val targetHints = if (gender == VoiceGender.FEMALE) femaleHints else maleHints

        return matchingLang
            .sortedByDescending { voice ->
                var score = 0
                val lowerName = voice.name.lowercase()
                val featuresStr = voice.features?.joinToString(" ")?.lowercase().orEmpty()
                if (voice.locale.country.equals(locale.country, ignoreCase = true)) score += 3
                if (voice.quality >= Voice.QUALITY_HIGH) score += 4
                else if (voice.quality >= Voice.QUALITY_NORMAL) score += 2
                if (!preferOffline && voice.isNetworkConnectionRequired) score += 1
                if (preferOffline && !voice.isNetworkConnectionRequired) score += 5
                if (targetHints.any { lowerName.contains(it) || featuresStr.contains(it) }) score += 10
                score
            }
            .firstOrNull()
    }
}
