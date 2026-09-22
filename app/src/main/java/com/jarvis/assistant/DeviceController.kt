package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

class DeviceController(private val context: Context) {

    // Show or update the floating HUD with custom subtitle text
    fun showHud(subtitle: String = "ZURAIZ ONLINE") {
        if (canDrawOverlays()) {
            val intent = Intent(context, HudOverlayService::class.java).apply {
                putExtra("SUBTITLE_TEXT", subtitle)
            }
            context.startService(intent)
        } else {
            requestOverlayPermission()
        }
    }

    // Dismiss the floating HUD
    fun hideHud() {
        val intent = Intent(context, HudOverlayService::class.java)
        context.stopService(intent)
    }

    // Wake device and unlock screen
    fun wakeAndUnlock() {
        val intent = Intent(context, UnlockWakeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }

    // Check if Realme allows drawing over other apps
    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    // Open Realme settings if permission is missing
    fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
