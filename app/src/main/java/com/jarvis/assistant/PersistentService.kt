package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlin.math.abs

class PersistentService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "PersistentService"
        private const val CHANNEL_ID = "zuraiz_persistent_channel"
        private const val NOTIFICATION_ID = 1001
        
        // Audio threshold for clap spike detection
        private const val CLAP_AMPLITUDE_THRESHOLD = 18000
    }

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechIntent: Intent
    private var textToSpeech: TextToSpeech? = null
    private lateinit var deviceController: DeviceController

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListeningActive = false
    private var isStandbyFrozen = false

    // Clap detection fields
    private var clapAudioRecord: AudioRecord? = null
    private var isClapDetectorRunning = false
    private var lastClapTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        deviceController = DeviceController(this)
        textToSpeech = TextToSpeech(this, this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("ZURAIZ Online — Listening"))

        initSpeechRecognizer()
        startClapDetectorThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListeningLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.US
            textToSpeech?.setPitch(0.9f) // Authoritative tone
            textToSpeech?.setSpeechRate(1.0f)
        }
    }

    /**
     * Speaks text output. Instantly cancellable.
     */
    private fun speak(message: String) {
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "ZURAIZ_TTS")
    }

    /**
     * Initializes Android's native continuous SpeechRecognizer engine.
     */
    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
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
                    // Instant TTS Cutoff on "Stop" or "Quiet"
                    if (spoken.contains("stop") || spoken.contains("quiet")) {
                        textToSpeech?.stop()
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    /**
     * Central routing engine for incoming voice commands and state triggers.
     */
    private fun handleVoiceCommand(rawInput: String) {
        val command = rawInput.trim().lowercase()
        Log.d(TAG, "Voice input registered: $command")

        // 1. Instant speech cancellation
        if (command == "stop" || command == "quiet") {
            textToSpeech?.stop()
            return
        }

        // 2. Standby & Wake State Engine
        if (command.contains("freeze")) {
            isStandbyFrozen = true
            speechRecognizer.stopListening()
            speak("Entering standby mode, Boss.")
            updateNotification("ZURAIZ Frozen — Say 'ARISE' to wake")
            return
        }

        if (isStandbyFrozen) {
            if (command.contains("arise")) {
                isStandbyFrozen = false
                speak("Systems online. At your service, Sir YUNO.")
                updateNotification("ZURAIZ Online — Listening")
            }
            return
        }

        // 3. Security Unlock & Challenge Gate
        if (deviceController.isAwaitingAuthChallenge) {
            deviceController.processAuthResponse(rawInput) { reply ->
                speak(reply)
            }
            return
        }

        if (command.contains("unlock") || command.contains("open phone")) {
            deviceController.handleUnlockRequest { prompt ->
                speak(prompt)
            }
            return
        }

        // Unhandled commands pass through here
    }

    private fun startListeningLoop() {
        if (!isListeningActive && !isStandbyFrozen) {
            mainHandler.post {
                try {
                    speechRecognizer.startListening(speechIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "SpeechRecognizer start error: ${e.message}")
                }
            }
        }
    }

    private fun restartListeningIfNeeded() {
        if (!isStandbyFrozen) {
            mainHandler.postDelayed({
                startListeningLoop()
            }, 300)
        }
    }

    /**
     * Acoustic background analyzer for double-clap identification.
     */
    private fun startClapDetectorThread() {
        val bufferSize = AudioRecord.getMinBufferSize(
            8000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            clapAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                8000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            isClapDetectorRunning = true
            clapAudioRecord?.startRecording()

            Thread {
                val buffer = ShortArray(bufferSize)
                while (isClapDetectorRunning) {
                    val read = clapAudioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) {
                        var maxPeak = 0
                        for (i in 0 until read) {
                            val sample = abs(buffer[i].toInt())
                            if (sample > maxPeak) maxPeak = sample
                        }

                        if (maxPeak > CLAP_AMPLITUDE_THRESHOLD) {
                            val now = System.currentTimeMillis()
                            // Double-clap registered within 500ms window
                            if (now - lastClapTime in 150..550) {
                                mainHandler.post {
                                    speak("Hey boss, it seems you are happy today.")
                                    if (isStandbyFrozen) {
                                        isStandbyFrozen = false
                                        startListeningLoop()
                                    }
                                }
                                lastClapTime = 0
                            } else {
                                lastClapTime = now
                            }
                        }
                    }
                }
            }.start()
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission not granted for clap detection: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing clap detector: ${e.message}")
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
        isClapDetectorRunning = false
        clapAudioRecord?.stop()
        clapAudioRecord?.release()
        speechRecognizer.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}
