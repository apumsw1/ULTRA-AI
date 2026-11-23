package com.ultra.assistant.control

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent

class MediaVolumeController(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    @Volatile
    private var torchEnabled = false

    fun adjustVolume(action: String, levelPercent: Int? = null): Result<String> {
        val am = audioManager ?: return Result.failure(IllegalStateException("AudioManager unavailable."))
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val current = am.getStreamVolume(stream)

        return runCatching {
            when (action.lowercase()) {
                "up", "increase", "louder" -> {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    val updated = am.getStreamVolume(stream)
                    "Media volume increased (${(updated * 100) / max}%)."
                }
                "down", "decrease", "quieter", "lower" -> {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    val updated = am.getStreamVolume(stream)
                    "Media volume lowered (${(updated * 100) / max}%)."
                }
                "mute", "silent" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    } else {
                        am.setStreamVolume(stream, 0, AudioManager.FLAG_SHOW_UI)
                    }
                    "Media audio muted."
                }
                "unmute" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    } else if (current == 0) {
                        am.setStreamVolume(stream, (max * 0.5f).toInt().coerceAtLeast(1), AudioManager.FLAG_SHOW_UI)
                    }
                    "Media audio unmuted."
                }
                "set" -> {
                    val pct = (levelPercent ?: 50).coerceIn(0, 100)
                    val target = ((pct / 100f) * max).toInt().coerceIn(0, max)
                    am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
                    "Media volume set to $pct%."
                }
                else -> {
                    "Current media volume is ${(current * 100) / max}%."
                }
            }
        }
    }

    fun controlMediaPlayback(command: String): Result<String> {
        val am = audioManager ?: return Result.failure(IllegalStateException("AudioManager unavailable."))
        val keyCode = when (command.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return Result.failure(IllegalArgumentException("Unsupported media command '$command'."))
        }
        return runCatching {
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            "Sent media control: ${command.replace('_', ' ')}."
        }
    }

    fun setFlashlight(enable: Boolean?): Result<String> {
        val cm = cameraManager ?: return Result.failure(IllegalStateException("CameraManager unavailable."))
        return runCatching {
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Result.failure(IllegalStateException("No flashlight hardware detected on this device."))

            val nextState = enable ?: !torchEnabled
            cm.setTorchMode(cameraId, nextState)
            torchEnabled = nextState
            if (nextState) "Flashlight turned on." else "Flashlight turned off."
        }
    }

    fun batterySummary(): String {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        return if (pct >= 0) {
            "$pct%${if (charging) " (charging)" else ""}"
        } else {
            "Unavailable"
        }
    }
}
