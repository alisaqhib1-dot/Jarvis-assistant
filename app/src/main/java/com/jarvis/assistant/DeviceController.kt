package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.content.IntentFilter

class DeviceController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun openApp(appName: String): String {
        val clean = appName.lowercase().trim().replace(" ", "")
        val pm = context.packageManager

        // Direct package mapping for fast, guaranteed resolution
        val packageMap = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "instagram" to "com.instagram.android",
            "settings" to "com.android.settings",
            "camera" to "com.android.camera",
            "gallery" to "com.google.android.apps.photos",
            "maps" to "com.google.android.apps.maps",
            "playstore" to "com.android.vending",
            "telegram" to "org.telegram.messenger"
        )

        val target = packageMap[clean]
        if (target != null) {
            val intent = pm.getLaunchIntentForPackage(target)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.startActivity(intent)
                return "Opening $appName."
            }
        }

        // Fallback: search through all launchable apps installed on the device
        return try {
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(intent, 0)
            val match = apps.firstOrNull {
                val label = it.loadLabel(pm).toString().lowercase().replace(" ", "")
                label.contains(clean) || clean.contains(label)
            }

            if (match != null) {
                val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(launchIntent)
                    "Opening $appName."
                } else {
                    "Unable to launch $appName."
                }
            } else {
                "Could not find app $appName."
            }
        } catch (e: Exception) {
            "Error opening app: ${e.message}"
        }
    }

    fun setFlashlight(enable: Boolean): String {
        return try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
            if (enable) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "Flashlight error."
        }
    }

    fun makeCall(phoneNumber: String): String {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            "Calling $phoneNumber."
        } catch (e: Exception) {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(dialIntent)
            "Opening dialer for $phoneNumber."
        }
    }

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

    fun getBatteryTelemetry(): String {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val status = context.registerReceiver(null, ifilter)
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
        return "Battery is at $pct percent."
    }

    fun executeDirective(rawCommand: String, onResult: (String) -> Unit): Boolean {
        val cmd = rawCommand.lowercase().trim()

        if (cmd.contains("flashlight") || cmd.contains("torch")) {
            val state = !cmd.contains("off")
            onResult(setFlashlight(state))
            return true
        }

        if (cmd.startsWith("open ") || cmd.startsWith("launch ")) {
            val target = cmd.removePrefix("open ").removePrefix("launch ").trim()
            onResult(openApp(target))
            return true
        }

        if (cmd.startsWith("call ") || cmd.startsWith("dial ")) {
            val target = cmd.removePrefix("call ").removePrefix("dial ").trim()
            onResult(makeCall(target))
            return true
        }

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

        if (cmd.contains("battery")) {
            onResult(getBatteryTelemetry())
            return true
        }

        return false
    }
}
