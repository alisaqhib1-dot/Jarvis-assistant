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
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

class PersistentService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "PersistentService"
        private const val CHANNEL_ID = "zuraiz_persistent_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var speechIntent: Intent
    private var textToSpeech: TextToSpeech? = null
    private lateinit var deviceController: DeviceController

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListeningActive = false
    private var isStandbyFrozen = false

    override fun onCreate() {
        super.onCreate()
        deviceController = DeviceController(this)
        textToSpeech = TextToSpeech(this, this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("ZURAIZ Online — Listening"))

        initSpeechRecognizer()
        startListeningLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListeningLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.US
            textToSpeech?.setPitch(0.9f)
            textToSpeech?.setSpeechRate(1.0f)
        }
    }

    private fun speak(message: String) {
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "ZURAIZ_TTS")
    }

    private fun initSpeechRecognizer() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isListeningActive = true
                        }

                        override fun onBeginningOfSpeech() {}

                        override fun onRmsChanged(rmsdB: Float) {}

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            isListeningActive = false
                        }

                        override fun onError(error: Int) {
                            isListeningActive = false
                            restartListeningIfNeeded()
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                handleVoiceCommand(matches[0])
                            }
                            restartListeningIfNeeded()
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                val spoken = matches[0].lowercase().trim()
                                if (spoken.contains("stop") || spoken.contains("quiet")) {
                                    textToSpeech?.stop()
                                }
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing speech recognizer: ${e.message}")
            }
        }
    }

    /**
     * Central routing engine for voice commands.
     */
    private fun handleVoiceCommand(rawInput: String) {
        val command = rawInput.trim().lowercase()
        Log.d(TAG, "Voice received: $command")

        // 1. Wake from Standby: Evaluated first when frozen
        if (isStandbyFrozen) {
            if (command.contains("arise") || command.contains("wake up") || command.contains("zuraiz")) {
                exitFrozenStandby()
            }
            return
        }

        // 2. Instant speech cancellation
        if (command == "stop" || command == "quiet") {
            textToSpeech?.stop()
            return
        }

        // 3. Freeze / Standby Trigger
        if (command.contains("freeze") || command.contains("sleep")) {
            enterFrozenStandby()
            return
        }

        // 4. Security Challenge Response
        if (deviceController.isAwaitingAuthChallenge) {
            deviceController.processAuthResponse(rawInput) { reply ->
                speak(reply)
            }
            return
        }

        // 5. Screen Unlock Request Gate
        if (command.contains("unlock") || command.contains("open phone")) {
            deviceController.handleUnlockRequest { prompt ->
                speak(prompt)
            }
            return
        }

        // 6. Offline Hardware Controls & RPA
        val handledLocally = deviceController.executeOfflineCommand(command) { reply ->
            speak(reply)
        }
        if (handledLocally) return
    }

    /**
     * Low-power standby: Speech recognizer stays alive to catch 'ARISE'.
     */
    private fun enterFrozenStandby() {
        isStandbyFrozen = true
        isListeningActive = false
        speak("Entering standby mode, Boss.")
        updateNotification("ZURAIZ Frozen — Say 'ARISE' to wake")

        mainHandler.postDelayed({
            startListeningLoop()
        }, 600)
    }

    /**
     * Wakes the system back to active state.
     */
    private fun exitFrozenStandby() {
        isStandbyFrozen = false
        speak("Systems online. At your service, Sir YUNO.")
        updateNotification("ZURAIZ Online — Listening")

        mainHandler.postDelayed({
            startListeningLoop()
        }, 500)
    }

    private fun startListeningLoop() {
        mainHandler.post {
            try {
                speechRecognizer?.startListening(speechIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening: ${e.message}")
            }
        }
    }

    private fun restartListeningIfNeeded() {
        mainHandler.postDelayed({
            startListeningLoop()
        }, 350)
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ZURAIZ Core")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildForegroundNotification(statusText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ZURAIZ Background Engine",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}
