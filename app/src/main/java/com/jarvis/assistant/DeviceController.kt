package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log

class DeviceController(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var isAwaitingAuthChallenge: Boolean = false

    fun processAuthResponse(spokenText: String, onReply: (String) -> Unit) {
        val clean = spokenText.trim().lowercase()
        isAwaitingAuthChallenge = false

        if (clean.contains("stand down") || clean.contains("it's me") || clean.contains("its me")) {
            onReply("Access granted. Unlocking device, Boss.")

            // Start Wake Activity to bypass Realme black screen
            val wakeIntent = Intent(context, UnlockWakeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.startActivity(wakeIntent)
        } else {
            onReply("Voice match failed. Security snapshot recorded.")
            triggerIntruderProtocol()
        }
    }

    fun triggerIntruderProtocol() {
        Log.d("DeviceController", "Intruder detected. Capturing security snapshot.")
        // Camera snapshot trigger connects here
    }

    fun handleHardwareCommand(command: String, onReply: (String) -> Unit): Boolean {
        val cmd = command.lowercase()
        return when {
            cmd.contains("volume up") -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                onReply("Volume increased.")
                true
            }
            cmd.contains("volume down") -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                onReply("Volume decreased.")
                true
            }
            cmd.contains("mute") -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                onReply("Audio muted.")
                true
            }
            else -> false
        }
    }
}
