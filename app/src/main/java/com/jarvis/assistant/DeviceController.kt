package com.jarvis.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName

class DeviceController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- App Launch via PendingIntent ---
    fun openApp(appName: String): String {
        val clean = appName.lowercase().trim().replace(" ", "")
        val pm = context.packageManager

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

        var targetPackage = packageMap[clean]

        if (targetPackage == null) {
            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(launcherIntent, 0)
            val match = apps.firstOrNull {
                val label = it.loadLabel(pm).toString().lowercase().replace(" ", "")
                label.contains(clean) || clean.contains(label)
            }
            targetPackage = match?.activityInfo?.packageName
        }

        if (targetPackage == null) {
            return "Could not find app $appName."
        }

        val launchIntent = pm.getLaunchIntentForPackage(targetPackage) ?: return "Unable to launch $appName."
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        return try {
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent.send()
            "Opening $appName."
        } catch (_: Exception) {
            try {
                context.startActivity(launchIntent)
                "Opening $appName."
            } catch (e: Exception) {
                "Failed to open $appName: ${e.message}"
            }
        }
    }

    // --- Device Lock Fix for Realme UI ---
    fun lockDevice(): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = ComponentName(context, AdminReceiver::class.java)
            if (dpm.isAdminActive(component)) {
                mainHandler.post {
                    dpm.lockNow()
                }
                "Device locked."
            } else {
                "Admin permission required to lock device."
            }
        } catch (e: Exception) {
            "Unable to lock device."
        }
    }

    // --- Screen Wake ---
    fun wakeDevice(): String {
        return try {
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ZURAIZ:WakeLock"
            )
            wakeLock.acquire(3000)
            "Screen active."
        } catch (e: Exception) {
            "Unable to wake display."
        }
    }

    // --- Flashlight ---
    fun setFlashlight(enable: Boolean): String {
        return try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
            if (enable) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "Flashlight error."
        }
    }

    // --- Calls ---
    fun makeCall(phoneNumber: String): String {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Calling $phoneNumber."
        } catch (e: Exception) {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            "Opening dialer."
        }
    }

    // --- Audio Profiles ---
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
                "Set to Normal."
            }
        }
    }

    // --- Battery Telemetry ---
    fun getBatteryTelemetry(): String {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val status = context.registerReceiver(null, ifilter)
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
        return "Battery is at $pct percent."
    }

    // --- Directives Parser ---
    fun executeDirective(rawCommand: String, onResult: (String) -> Unit): Boolean {
        val cmd = rawCommand.lowercase().trim()

        // App Launch
        if (cmd.startsWith("open ") || cmd.startsWith("launch ")) {
            val target = cmd.removePrefix("open ").removePrefix("launch ").trim()
            onResult(openApp(target))
            return true
        }

        // Lock / Wake
        if (cmd.contains("lock screen") || cmd.contains("lock device") || cmd.contains("lock phone")) {
            onResult(lockDevice())
            return true
        }
        if (cmd.contains("wake up") || cmd.contains("turn on screen") || cmd.contains("wake screen")) {
            onResult(wakeDevice())
            return true
        }

        // Flashlight
        if (cmd.contains("flashlight") || cmd.contains("torch")) {
            val state = !cmd.contains("off")
            onResult(setFlashlight(state))
            return true
        }

        // Call
        if (cmd.startsWith("call ") || cmd.startsWith("dial ")) {
            val target = cmd.removePrefix("call ").removePrefix("dial ").trim()
            onResult(makeCall(target))
            return true
        }

        // Audio Modes
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
