package com.jarvis.assistant

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import java.io.File

class DeviceController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // --- 1. Flashlight Control ---
    fun setFlashlight(enable: Boolean): String {
        return try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
            if (enable) "Flashlight turned on." else "Flashlight turned off."
        } catch (e: CameraAccessException) {
            "Failed to toggle flashlight: ${e.message}"
        }
    }

    // --- 2. Silent / Ringer Mode ---
    fun setRingerMode(mode: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !notificationManager.isNotificationPolicyAccessGranted) {
            return "Do Not Disturb permission required. Please grant access in settings."
        }

        return when (mode.lowercase()) {
            "silent" -> {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                "Device set to Silent."
            }
            "vibrate" -> {
                audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                "Device set to Vibrate."
            }
            "normal", "ring" -> {
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                "Device set to Normal Ringing."
            }
            else -> "Unknown ringer mode."
        }
    }

    // --- 3. Screen Brightness (0 - 255) ---
    fun setBrightness(level: Int): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
            return "Write Settings permission required to adjust brightness."
        }

        val safeLevel = level.coerceIn(0, 255)
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            safeLevel
        )
        return "Brightness set to $safeLevel."
    }

    // --- 4. Battery Telemetry ---
    fun getBatteryTelemetry(): String {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, ifilter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val temp = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

        return "Battery: $batteryPct% | Charging: $isCharging | Temperature: ${temp}°C"
    }

    // --- 5. Storage Cache Cleaner ---
    fun cleanAppCache(): String {
        return try {
            val cacheDir = context.cacheDir
            val sizeBefore = getDirSize(cacheDir)
            deleteDir(cacheDir)
            val freedMb = (sizeBefore / (1024 * 1024)).toString()
            "Cache cleaned. Freed approximately $freedMb MB."
        } catch (e: Exception) {
            "Failed to clean cache: ${e.message}"
        }
    }

    private fun getDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        var size: Long = 0
        for (file in dir.listFiles() ?: emptyArray()) {
            size += if (file.isDirectory) getDirSize(file) else file.length()
        }
        return size
    }

    private fun deleteDir(dir: File?): Boolean {
        if (dir != null && dir.isDirectory) {
            val children = dir.list() ?: emptyArray()
            for (child in children) {
                val success = deleteDir(File(dir, child))
                if (!success) return false
            }
            return dir.delete()
        } else if (dir != null && dir.isFile) {
            return dir.delete()
        }
        return false
    }
}

