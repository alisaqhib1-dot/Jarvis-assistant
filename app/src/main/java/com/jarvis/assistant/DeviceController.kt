package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import java.io.File

class DeviceController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // 1. Hardware Flashlight
    fun setFlashlight(enable: Boolean): String {
        return try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
            if (enable) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "Flashlight error: ${e.message}"
        }
    }

    // 2. Direct Calling
    fun makeCall(phoneNumber: String): String {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Calling $phoneNumber."
        } catch (e: Exception) {
            // Fallback to dialer if CALL_PHONE permission not granted
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            "Opening dialer for $phoneNumber."
        }
    }

    // 3. Open Any App by Name
    fun openApp(appName: String): String {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val cleanName = appName.lowercase().trim()
        val targetApp = packages.firstOrNull {
            val label = pm.getApplicationLabel(it).toString().lowercase()
            label == cleanName || label.contains(cleanName)
        }

        return if (targetApp != null) {
            val launchIntent = pm.getLaunchIntentForPackage(targetApp.packageName)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
                "Opening $appName."
            } else {
                "Unable to launch $appName."
            }
        } else {
            "App $appName not found on device."
        }
    }

    // 4. Ringer Modes
    fun setRingerMode(mode: String): String {
        return when (mode.lowercase()) {
            "silent" -> {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                "Set to Silent."
            }
            "vibrate" -> {
                audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                "Set to Vibrate."
            }
            else -> {
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                "Set to Normal Ringing."
            }
        }
    }

    // 5. Battery
    fun getBatteryTelemetry(): String {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, ifilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        return "Battery is at $pct percent."
    }

    // 6. Intent Interceptor (Checks hardware first, returns true if handled)
    fun executeDirective(rawCommand: String, onResult: (String) -> Unit): Boolean {
        val cmd = rawCommand.lowercase().trim()

        // Flashlight
        if (cmd.contains("flashlight") || cmd.contains("torch")) {
            val state = !cmd.contains("off")
            onResult(setFlashlight(state))
            return true
        }

        // Open App
        if (cmd.startsWith("open ") || cmd.startsWith("launch ")) {
            val target = cmd.removePrefix("open ").removePrefix("launch ").trim()
            onResult(openApp(target))
            return true
        }

        // Calling
        if (cmd.startsWith("call ") || cmd.startsWith("dial ")) {
            val target = cmd.removePrefix("call ").removePrefix("dial ").trim()
            onResult(makeCall(target))
            return true
        }

        // Ringer
        if (cmd.contains("silent")) {
            onResult(setRingerMode("silent"))
            return true
        }
        if (cmd.contains("vibrate")) {
            onResult(setRingerMode("vibrate"))
            return true
        }
        if (cmd.contains("normal mode") || cmd.contains("unmute")) {
            onResult(setRingerMode("normal"))
            return true
        }

        // Battery
        if (cmd.contains("battery")) {
            onResult(getBatteryTelemetry())
            return true
        }

        return false
    }
}
