package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

class PersistentService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "PersistentService"
        private const val CHANNEL_ID = "zuraiz_persistent_channel"
        private const val NOTIFICATION_ID = 1001
        private const val UTTERANCE_ID = "ZURAIZ_REPLY"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var speechIntent: Intent
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private lateinit var deviceController: DeviceController

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isSpeaking = false
    private var isStandbyFrozen = false

    override fun onCreate() {
        super.onCreate()
        deviceController = DeviceController(this)
        textToSpeech = TextToSpeech(this, this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("ZURAIZ Online — Listening"))

        initSpeechRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isTtsReady && !isSpeaking) {
            startListeningLoop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.US
            textToSpeech?.setPitch(0.9f)
            textToSpeech?.setSpeechRate(1.0f)

            // Force output directly through media stream so it cannot be muted by the mic
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            textToSpeech?.setAudioAttributes(audioAttributes)

            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    // Resume listening immediately after speech finishes
                    mainHandler.postDelayed({
                        startListeningLoop()
                    }, 200)
                }

                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    mainHandler.postDelayed({
                        startListeningLoop()
                    }, 200)
                }
            })

            isTtsReady = true
            Log.d(TAG, "TextToSpeech successfully initialized.")
            startListeningLoop()
        } else {
            Log.e(TAG, "TextToSpeech initialization failed with status: $status")
        }
    }

    private fun speak(message: String) {
        if (!isTtsReady) {
            Log.w(TAG, "TTS not ready yet. Dropped message: $message")
            return
        }

        mainHandler.post {
            try {
                // Stop listening immediately to release the microphone and audio ducking
                speechRecognizer?.stopListening()
                isSpeaking = true

                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
                }

                textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
            } catch (e: Exception) {
                Log.e(TAG, "Speak error: ${e.message}")
                isSpeaking = false
                startListeningLoop()
            }
        }
    }

    private fun initSpeechRecognizer() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.d(TAG, "Mic ready for speech.")
                        }

                        override fun onBeginningOfSpeech() {}

                        override fun onRmsChanged(rmsdB: Float) {}

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {}

                        override fun onError(error: Int) {
                            Log.d(TAG, "Speech error code: $error")
                            restartListeningIfNeeded()
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                handleVoiceCommand(matches[0])
                            } else {
                                restartListeningIfNeeded()
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                val spoken = matches[0].lowercase().trim()
                                if (spoken == "stop" || spoken == "quiet") {
                                    textToSpeech?.stop()
                                    isSpeaking = false
                                    startListeningLoop()
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
                Log.e(TAG, "Error initializing recognizer: ${e.message}")
            }
        }
    }

    private fun handleVoiceCommand(rawInput: String) {
        val command = rawInput.trim().lowercase()
        Log.d(TAG, "Voice command received: $command")

        // 1. Wake from Standby
        if (isStandbyFrozen) {
            if (command.contains("arise") || command.contains("wake up") || command.contains("zuraiz")) {
                exitFrozenStandby()
            } else {
                restartListeningIfNeeded()
            }
            return
        }

        // 2. Standby Trigger
        if (command.contains("freeze") || command.contains("sleep")) {
            enterFrozenStandby()
            return
        }

        // 3. Security Challenge Response
        if (deviceController.isAwaitingAuthChallenge) {
            deviceController.processAuthResponse(rawInput) { reply ->
                speak(reply)
            }
            return
        }

        // 4. Unlock Command Gate
        if (command.contains("unlock") || command.contains("open phone")) {
            deviceController.handleUnlockRequest { prompt ->
                speak(prompt)
            }
            return
        }

        // 5. Offline Hardware Controls & RPA
        val handled = deviceController.executeOfflineCommand(command) { reply ->
            speak(reply)
        }

        if (!handled) {
            restartListeningIfNeeded()
        }
    }

    private fun enterFrozenStandby() {
        isStandbyFrozen = true
        updateNotification("ZURAIZ Frozen — Say 'ARISE' to wake")
        speak("Entering standby mode, Boss.")
    }

    private fun exitFrozenStandby() {
        isStandbyFrozen = false
        updateNotification("ZURAIZ Online — Listening")
        speak("Systems online. At your service, Sir YUNO.")
    }

    private fun startListeningLoop() {
        if (isSpeaking) return

        mainHandler.post {
            try {
                speechRecognizer?.startListening(speechIntent)
            } catch (e: Exception) {
                Log.e(TAG, "startListeningLoop error: ${e.message}")
            }
        }
    }

    private fun restartListeningIfNeeded() {
        if (!isSpeaking) {
            mainHandler.postDelayed({
                startListeningLoop()
            }, 300)
        }
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
