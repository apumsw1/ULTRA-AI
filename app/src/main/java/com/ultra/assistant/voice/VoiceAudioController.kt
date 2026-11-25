package com.ultra.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.ultra.assistant.data.VoiceGender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

class SentenceStreamExtractor {
    private var emittedChars = 0

    fun reset() {
        emittedChars = 0
    }

    fun consumeDelta(accumulatedVisibleReply: String): List<String> {
        if (accumulatedVisibleReply.length <= emittedChars) return emptyList()
        val pending = accumulatedVisibleReply.substring(emittedChars)
        val readySentences = mutableListOf<String>()
        val boundaryRegex = Regex("[.!?।\\n]+(?:\\s+|$)")
        var localCursor = 0

        for (match in boundaryRegex.findAll(pending)) {
            val end = match.range.last + 1
            val piece = pending.substring(localCursor, end).trim()
            if (piece.length >= 4) {
                readySentences += piece
                localCursor = end
            }
        }

        // Also flush long unbroken clauses (>180 chars) at comma/semicolon for snappy voice latency
        val remaining = pending.substring(localCursor)
        if (remaining.length > 180) {
            val clauseEnd = remaining.lastIndexOfAny(charArrayOf(',', ';', ':', '—'))
            if (clauseEnd > 40) {
                val piece = remaining.substring(0, clauseEnd + 1).trim()
                if (piece.isNotBlank()) {
                    readySentences += piece
                    localCursor += clauseEnd + 1
                }
            }
        }

        emittedChars += localCursor
        return readySentences
    }

    fun flushRemainder(accumulatedVisibleReply: String): String {
        if (accumulatedVisibleReply.length <= emittedChars) return ""
        val rem = accumulatedVisibleReply.substring(emittedChars).trim()
        emittedChars = accumulatedVisibleReply.length
        return rem
    }
}

class VoiceAudioController(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    private var activePcmTrack: AudioTrack? = null
    private val pcmTrackLock = Any()

    companion object {
        const val GEMINI_TTS_SAMPLE_RATE = 24_000

        /** Keep Android TTS pitch natural (no artificial 0.87x/1.05x robotic formant shift). */
        fun normalizeFallbackPitch(userPitch: Float): Float = userPitch.coerceIn(0.85f, 1.2f)
    }

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
        runCatching { tts.language = locale }
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

        // Avoid artificial 0.87x/1.05x formant shifting that sounds robotic — native voice selection handles gender
        val effectivePitch = normalizeFallbackPitch(userPitch)
        tts.setPitch(effectivePitch)
    }

    suspend fun playPcm24kAudio(
        pcmBytes: ByteArray,
        volume: Float = 0.95f,
        speed: Float = 1.0f,
        shouldContinue: () -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (pcmBytes.size < 4 || !shouldContinue()) return@withContext false
        val minBuf = AudioTrack.getMinBufferSize(
            GEMINI_TTS_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val bufferBytes = maxOf(minBuf * 2, 8192)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(GEMINI_TTS_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }.getOrNull() ?: return@withContext false

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return@withContext false
        }

        synchronized(pcmTrackLock) {
            stopPcmPlaybackLocked()
            activePcmTrack = track
        }

        try {
            val clampedVol = volume.coerceIn(0.1f, 1.0f)
            runCatching { track.setVolume(clampedVol) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && speed != 1.0f) {
                runCatching {
                    track.playbackParams = track.playbackParams.setSpeed(speed.coerceIn(0.75f, 1.35f))
                }
            }
            track.play()

            // Stream in ~40ms chunks (1920 bytes at 24kHz mono PCM16) for immediate barge-in cancellation
            val chunkSize = 3840
            var offset = 0
            while (offset < pcmBytes.size && currentCoroutineContext().isActive && shouldContinue()) {
                val len = minOf(chunkSize, pcmBytes.size - offset)
                val written = track.write(pcmBytes, offset, len, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            if (currentCoroutineContext().isActive && shouldContinue()) {
                runCatching { track.stop() }
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        } finally {
            synchronized(pcmTrackLock) {
                if (activePcmTrack === track) {
                    activePcmTrack = null
                }
            }
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                track.flush()
            }
            runCatching { track.release() }
        }
    }

    fun stopPcmPlayback() {
        synchronized(pcmTrackLock) {
            stopPcmPlaybackLocked()
        }
    }

    private fun stopPcmPlaybackLocked() {
        activePcmTrack?.let { track ->
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                track.flush()
            }
        }
        activePcmTrack = null
    }

    fun speakNaturally(
        tts: TextToSpeech,
        rawText: String,
        languageSetting: String = "English",
        gender: VoiceGender = VoiceGender.FEMALE,
        speed: Float = 1.0f,
        userPitch: Float = 1.0f,
        volume: Float = 0.95f,
        preferOffline: Boolean = false,
        explicitVoiceName: String? = null,
        flushQueue: Boolean = true,
        utterancePrefix: String = "ultra_reply"
    ): String? {
        val clean = sanitizeForSpeech(rawText)
        if (clean.isBlank()) return null

        val sentences = splitNaturalSentences(clean, maxChunkChars = 220)
        if (sentences.isEmpty()) return null

        val clampedVolume = volume.coerceIn(0.1f, 1.0f)
        var lastUtteranceId: String? = null
        var isFirst = flushQueue
        val allVoices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
        var lastAppliedLocaleLang = ""

        sentences.forEachIndexed { sIndex, sentence ->
            val segments = LanguageManager.splitCodeSwitchedSegments(sentence, languageSetting)
            segments.forEachIndexed { segIndex, (segLocale, segText) ->
                if (segText.isBlank()) return@forEachIndexed
                if (segLocale.language != lastAppliedLocaleLang) {
                    val voice = selectVoice(
                        voices = allVoices,
                        locale = segLocale,
                        gender = gender,
                        preferOffline = preferOffline,
                        explicitVoiceName = explicitVoiceName
                    )
                    if (voice != null) {
                        runCatching { tts.voice = voice }
                    } else {
                        runCatching { tts.language = segLocale }
                    }
                    lastAppliedLocaleLang = segLocale.language
                }

                val queueMode = if (isFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                isFirst = false
                val uttId = "${utterancePrefix}_${System.currentTimeMillis()}_${sIndex}_$segIndex"
                lastUtteranceId = uttId
                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, clampedVolume)
                }
                val result = tts.speak(segText, queueMode, params, uttId)
                if (result != TextToSpeech.SUCCESS) {
                    return null
                }
            }
            if (sIndex < sentences.lastIndex && lastUtteranceId != null) {
                tts.playSilentUtterance(130L, TextToSpeech.QUEUE_ADD, "${lastUtteranceId}_pause")
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
        val bounded = text.take(1600)
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
                it.name == explicitVoiceName &&
                    it.locale.language.equals(locale.language, ignoreCase = true) &&
                    (!preferOffline || !it.isNetworkConnectionRequired)
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
        val neuralHints = listOf("neural", "wavenet", "journey", "studio", "network", "hd")
        val targetHints = if (gender == VoiceGender.FEMALE) femaleHints else maleHints

        return matchingLang
            .sortedByDescending { voice ->
                var score = 0
                val lowerName = voice.name.lowercase()
                val featuresStr = voice.features?.joinToString(" ")?.lowercase().orEmpty()
                if (voice.locale.country.equals(locale.country, ignoreCase = true)) score += 4
                if (voice.quality >= Voice.QUALITY_VERY_HIGH) score += 7
                else if (voice.quality >= Voice.QUALITY_HIGH) score += 5
                else if (voice.quality >= Voice.QUALITY_NORMAL) score += 2
                if (!preferOffline) {
                    if (voice.isNetworkConnectionRequired) score += 4
                    if (neuralHints.any { lowerName.contains(it) || featuresStr.contains(it) }) score += 6
                } else {
                    if (!voice.isNetworkConnectionRequired) score += 8
                }
                if (targetHints.any { lowerName.contains(it) || featuresStr.contains(it) }) score += 10
                score
            }
            .firstOrNull()
    }
}
