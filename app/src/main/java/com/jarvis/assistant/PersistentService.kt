package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat

class PersistentService : Service() {

    private lateinit var deviceController: DeviceController
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isFrozen = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        deviceController = DeviceController(this)
        startForegroundServiceNotification()

        mainHandler.post {
            initSpeechRecognizer()
        }
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                // Restart listening immediately if speech engine resets or times out
                restartListening()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val command = matches[0]
                    processVoiceCommand(command)
                }
                restartListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startListening()
    }

    private fun startListening() {
        mainHandler.post {
            try {
                speechRecognizer?.startListening(recognizerIntent)
            } catch (e: Exception) {
                // Keep resilient against engine reset crashes
            }
        }
    }

    private fun restartListening() {
        mainHandler.postDelayed({
            startListening()
        }, 400)
    }

    private fun processVoiceCommand(rawCommand: String) {
        val cmd = rawCommand.trim().lowercase()

        // 1. Freeze / Arise protocol
        if (cmd.contains("freeze")) {
            isFrozen = true
            deviceController.showHud("ZURAIZ FROZEN")
            return
        }

        if (cmd.contains("arise")) {
            isFrozen = false
            deviceController.showHud("ZURAIZ AWAKE: SIR YUNO")
            return
        }

        if (isFrozen) return

        // 2. Unlock & Wake trigger
        if (cmd.contains("stand down it's me") || cmd.contains("stand down its me") || cmd.contains("unlock")) {
            deviceController.showHud("AUTHENTICATED: SIR YUNO")
            deviceController.wakeAndUnlock()
            return
        }

        // 3. HUD Display commands
        if (cmd.contains("show hud")) {
            deviceController.showHud("ZURAIZ ONLINE")
            return
        }

        if (cmd.contains("hide hud") || cmd.contains("dismiss")) {
            deviceController.hideHud()
            return
        }

        // 4. Default: Live Subtitle Echo
        deviceController.showHud("HEARD: $rawCommand")
    }

    private fun startForegroundServiceNotification() {
        val channelId = "zuraiz_service_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Zuraiz Core",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ZURAIZ Active")
            .setContentText("Listening to commands")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
    }
}
