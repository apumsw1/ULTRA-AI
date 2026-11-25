package com.ultra.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.ultra.assistant.permissions.PermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

data class AudioHardwareStatus(
    val noiseSuppressorAvailable: Boolean = false,
    val noiseSuppressorEnabled: Boolean = false,
    val echoCancelerAvailable: Boolean = false,
    val echoCancelerEnabled: Boolean = false,
    val agcAvailable: Boolean = false,
    val agcEnabled: Boolean = false,
    val sampleRateHz: Int = 16000,
    val clippingDetected: Boolean = false,
    val captureRunning: Boolean = false
) {
    fun summaryLabel(): String = buildString {
        append(if (noiseSuppressorEnabled) "NS: HW Active" else "NS: Gate")
        append(" • ")
        append(if (echoCancelerEnabled) "AEC: Active" else "AEC: Adaptive")
        if (clippingDetected) append(" • Clipping")
    }
}

object VoiceAudioMath {
    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 320 // 20ms at 16kHz

    fun computeRms(samples: ShortArray, count: Int = samples.size): Float {
        if (count <= 0) return 0f
        var sumSq = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sumSq += s * s
        }
        return sqrt(sumSq / count).toFloat()
    }

    fun computeDbfs(rms: Float): Float {
        if (rms <= 1f) return -60f
        return (20.0 * log10((rms / 32767.0).coerceAtLeast(1e-4))).toFloat().coerceIn(-60f, 0f)
    }

    /** Normalized visual level (0f..12f compatible with VoiceOrbCanvas & VoiceScreen meters). */
    fun rmsToVisualLevel(rms: Float): Float {
        val db = computeDbfs(rms)
        return (((db + 55f) / 48f) * 12f).coerceIn(0f, 12f)
    }

    fun detectClipping(samples: ShortArray, count: Int = samples.size): Boolean {
        var clippedCount = 0
        for (i in 0 until count) {
            if (abs(samples[i].toInt()) >= 32400) {
                clippedCount++
                if (clippedCount >= 3) return true
            }
        }
        return false
    }

    fun computeAdaptiveThreshold(
        noiseFloorRms: Float,
        assistantSpeaking: Boolean,
        echoCancelerActive: Boolean
    ): Float {
        val clampedFloor = noiseFloorRms.coerceIn(60f, 2500f)
        return if (assistantSpeaking) {
            val minGate = if (echoCancelerActive) 1150f else 1650f
            val multiplier = if (echoCancelerActive) 3.8f else 4.6f
            maxOf(clampedFloor * multiplier, minGate)
        } else {
            maxOf(clampedFloor * 2.5f, 440f)
        }
    }

    fun pcm16ToWavBytes(
        pcmBytes: ByteArray,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = 1
    ): ByteArray {
        val byteRate = sampleRate * channels * 2
        val totalDataLen = pcmBytes.size + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM subchunk size
            putShort(1.toShort()) // AudioFormat = PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * 2).toShort())
            putShort(16.toShort()) // Bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmBytes.size)
        }.array()
        return ByteArray(44 + pcmBytes.size).apply {
            System.arraycopy(header, 0, this, 0, 44)
            System.arraycopy(pcmBytes, 0, this, 44, pcmBytes.size)
        }
    }
}

class ContinuousAudioCaptureEngine(
    private val context: Context,
    private val onSpeechStart: () -> Unit,
    private val onBargeInDetected: () -> Unit,
    private val onUtteranceReady: (wavBytes: ByteArray) -> Unit,
    private val onAudioLevel: (visualLevel: Float, dbfs: Float, clipping: Boolean) -> Unit,
    private val onHardwareStatus: (AudioHardwareStatus) -> Unit,
    private val onCaptureError: (String) -> Unit
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var noiseSuppressor: NoiseSuppressor? = null

    @Volatile
    private var echoCanceler: AcousticEchoCanceler? = null

    @Volatile
    private var agc: AutomaticGainControl? = null

    @Volatile
    var assistantSpeaking: Boolean = false

    @Volatile
    var processingTurn: Boolean = false

    @Volatile
    var batteryConsciousMode: Boolean = true

    @Volatile
    private var paused = false

    @Volatile
    var latestStatus: AudioHardwareStatus = AudioHardwareStatus()
        private set

    fun start(): Boolean {
        if (captureJob?.isActive == true) {
            paused = false
            return true
        }
        if (!PermissionManager.hasMicrophone(context)) {
            onCaptureError("Microphone permission not granted")
            return false
        }

        val record = createAudioRecord() ?: run {
            onCaptureError("Unable to initialize microphone hardware stream")
            return false
        }
        audioRecord = record
        attachAudioEffects(record.audioSessionId)

        val started = runCatching {
            record.startRecording()
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrDefault(false)

        if (!started) {
            releaseHardware()
            onCaptureError("Microphone hardware stream could not start")
            return false
        }

        paused = false
        latestStatus = latestStatus.copy(captureRunning = true)
        onHardwareStatus(latestStatus)

        captureJob = engineScope.launch {
            runCaptureLoop(record)
        }
        return true
    }

    fun pauseForOfflineRecognizer() {
        paused = true
        stopAndReleaseOnlyHardware()
    }

    fun resumeAfterOfflineRecognizer(): Boolean {
        paused = false
        return start()
    }

    fun stop() {
        paused = true
        captureJob?.cancel()
        captureJob = null
        releaseHardware()
        latestStatus = latestStatus.copy(captureRunning = false)
        onHardwareStatus(latestStatus)
    }

    fun destroy() {
        stop()
        engineScope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val minBytes = AudioRecord.getMinBufferSize(
            VoiceAudioMath.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(VoiceAudioMath.FRAME_SAMPLES * 4)
        val bufferBytes = maxOf(minBytes * 2, VoiceAudioMath.SAMPLE_RATE * 2 / 4)

        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val candidate = runCatching {
                AudioRecord(
                    source,
                    VoiceAudioMath.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
            }.getOrNull()
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            }
            runCatching { candidate?.release() }
        }
        return null
    }

    private fun attachAudioEffects(sessionId: Int) {
        val nsAvail = runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)
        val aecAvail = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
        val agcAvail = runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false)

        if (nsAvail) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (aecAvail) {
            echoCanceler = runCatching {
                AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (agcAvail) {
            agc = runCatching {
                AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }

        latestStatus = AudioHardwareStatus(
            noiseSuppressorAvailable = nsAvail,
            noiseSuppressorEnabled = noiseSuppressor?.enabled == true,
            echoCancelerAvailable = aecAvail,
            echoCancelerEnabled = echoCanceler?.enabled == true,
            agcAvailable = agcAvail,
            agcEnabled = agc?.enabled == true,
            sampleRateHz = VoiceAudioMath.SAMPLE_RATE,
            clippingDetected = false,
            captureRunning = true
        )
    }

    private fun runCaptureLoop(record: AudioRecord) {
        val frameShorts = ShortArray(VoiceAudioMath.FRAME_SAMPLES)
        val frameBytes = ByteArray(VoiceAudioMath.FRAME_SAMPLES * 2)
        val preRollFrames = ArrayDeque<ByteArray>(24)
        val utteranceStream = ByteArrayOutputStream(32_000)

        var noiseFloorRms = 180f
        var consecutiveSpeechFrames = 0
        var consecutiveSilenceFrames = 0
        var speechActive = false
        var activeSpeechFramesTotal = 0
        var lastLevelDispatchMs = 0L

        try {
            while (engineScope.isActive && !paused && audioRecord === record) {
                val read = record.read(frameShorts, 0, frameShorts.size)
                if (read <= 0) continue

                ByteBuffer.wrap(frameBytes, 0, read * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .put(frameShorts, 0, read)
                val currentFrameCopy = frameBytes.copyOf(read * 2)

                val rms = VoiceAudioMath.computeRms(frameShorts, read)
                val dbfs = VoiceAudioMath.computeDbfs(rms)
                val visualLevel = VoiceAudioMath.rmsToVisualLevel(rms)
                val clipped = VoiceAudioMath.detectClipping(frameShorts, read)
                val now = System.currentTimeMillis()
                val levelInterval = if (batteryConsciousMode && !speechActive) 90L else 50L
                if (now - lastLevelDispatchMs >= levelInterval) {
                    lastLevelDispatchMs = now
                    onAudioLevel(visualLevel, dbfs, clipped)
                    if (clipped != latestStatus.clippingDetected) {
                        latestStatus = latestStatus.copy(clippingDetected = clipped)
                        onHardwareStatus(latestStatus)
                    }
                }

                // Keep pre-roll in RAM even before onset so first syllable is never clipped
                if (!speechActive) {
                    if (preRollFrames.size >= 20) preRollFrames.removeFirst()
                    preRollFrames.addLast(currentFrameCopy)
                }

                val threshold = VoiceAudioMath.computeAdaptiveThreshold(
                    noiseFloorRms = noiseFloorRms,
                    assistantSpeaking = assistantSpeaking,
                    echoCancelerActive = latestStatus.echoCancelerEnabled
                )
                val isHotFrame = rms >= threshold

                if (!speechActive) {
                    if (!isHotFrame) {
                        noiseFloorRms = (noiseFloorRms * 0.94f) + (rms * 0.06f)
                        consecutiveSpeechFrames = 0
                    } else {
                        consecutiveSpeechFrames++
                        val requiredOnsetFrames = if (assistantSpeaking) 5 else 3
                        if (consecutiveSpeechFrames >= requiredOnsetFrames) {
                            speechActive = true
                            consecutiveSilenceFrames = 0
                            activeSpeechFramesTotal = consecutiveSpeechFrames
                            utteranceStream.reset()
                            preRollFrames.forEach { utteranceStream.write(it) }
                            preRollFrames.clear()

                            if (assistantSpeaking) {
                                onBargeInDetected()
                            } else if (!processingTurn) {
                                onSpeechStart()
                            }
                        }
                    }
                } else {
                    utteranceStream.write(currentFrameCopy)
                    if (isHotFrame) {
                        consecutiveSilenceFrames = 0
                        activeSpeechFramesTotal++
                    } else {
                        consecutiveSilenceFrames++
                    }

                    val silenceCutoffFrames = 36 // ~720ms post-speech pause
                    val maxUtteranceBytes = VoiceAudioMath.SAMPLE_RATE * 2 * 18 // 18s RAM cap
                    if (consecutiveSilenceFrames >= silenceCutoffFrames ||
                        utteranceStream.size() >= maxUtteranceBytes
                    ) {
                        val pcmBytes = utteranceStream.toByteArray()
                        utteranceStream.reset()
                        speechActive = false
                        consecutiveSpeechFrames = 0
                        consecutiveSilenceFrames = 0

                        // Require >= 240ms of actual speech to avoid coughing/click false triggers
                        if (activeSpeechFramesTotal >= 7 && pcmBytes.size >= 8000) {
                            val wav = VoiceAudioMath.pcm16ToWavBytes(pcmBytes)
                            onUtteranceReady(wav)
                        }
                        activeSpeechFramesTotal = 0
                    }
                }
            }
        } catch (_: Throwable) {
            // Exits cleanly when stopped or released
        } finally {
            utteranceStream.reset()
            preRollFrames.clear()
        }
    }

    private fun stopAndReleaseOnlyHardware() {
        captureJob?.cancel()
        captureJob = null
        releaseHardware()
        latestStatus = latestStatus.copy(captureRunning = false)
        onHardwareStatus(latestStatus)
    }

    private fun releaseHardware() {
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { agc?.release() }
        agc = null
        runCatching {
            audioRecord?.stop()
        }
        runCatching {
            audioRecord?.release()
        }
        audioRecord = null
    }
}
