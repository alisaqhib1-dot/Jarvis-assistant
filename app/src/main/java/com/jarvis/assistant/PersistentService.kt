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

        // Strict peak threshold to isolate real claps from voice frequencies
        private const val CLAP_PEAK_THRESHOLD = 26000
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var speechIntent: Intent
    private var textToSpeech: TextToSpeech? = null
    private lateinit var deviceController: DeviceController

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListeningActive = false
    private var isStandbyFrozen = false

    // Acoustic clap analyzer
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
        startListeningLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isStandbyFrozen) {
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
     * Central routing engine for all voice input.
     */
    private fun handleVoiceCommand(rawInput: String) {
        val command = rawInput.trim().lowercase()
        Log.d(TAG, "Voice received: $command")

        // 1. Instant speech cancellation
        if (command == "stop" || command == "quiet") {
            textToSpeech?.stop()
            return
        }

        // 2. Freeze / Standby trigger
        if (command.contains("freeze")) {
            enterFrozenStandby()
            return
        }

        // 3. Wake trigger while in standby
        if (isStandbyFrozen) {
            if (command.contains("arise")) {
                exitFrozenStandby()
            }
            return
        }

        // 4. Security Challenge Response Gate
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

        // 6. Offline Hardware & App Automations
        val handledLocally = deviceController.executeOfflineCommand(command) { reply ->
            speak(reply)
        }
        if (handledLocally) return
    }

    /**
     * Transitions system into Frozen Standby.
     */
    private fun enterFrozenStandby() {
        isStandbyFrozen = true
        isListeningActive = false
        speak("Entering standby mode, Boss.")
        updateNotification("ZURAIZ Frozen — Say 'ARISE' to wake")

        // Free the microphone so the acoustic clap analyzer runs without conflicts
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            startClapDetectorThread()
        }
    }

    /**
     * Exits Frozen Standby and restores the speech recognition engine.
     */
    private fun exitFrozenStandby() {
        isStandbyFrozen = false
        stopClapDetectorThread()
        speak("Systems online. At your service, Sir YUNO.")
        updateNotification("ZURAIZ Online — Listening")

        mainHandler.postDelayed({
            startListeningLoop()
        }, 500)
    }

    private fun startListeningLoop() {
        if (isStandbyFrozen) return
        mainHandler.post {
            try {
                speechRecognizer?.startListening(speechIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening: ${e.message}")
            }
        }
    }

    private fun restartListeningIfNeeded() {
        if (!isStandbyFrozen) {
            mainHandler.postDelayed({
                startListeningLoop()
            }, 350)
        }
    }

    /**
     * Acoustic pulse reader with crest-factor analysis to isolate real claps from voices.
     */
    private fun startClapDetectorThread() {
        if (isClapDetectorRunning) return

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
                        var totalEnergy: Long = 0

                        for (i in 0 until read) {
                            val sample = abs(buffer[i].toInt())
                            if (sample > maxPeak) maxPeak = sample
                            totalEnergy += sample
                        }

                        val avgEnergy = totalEnergy / read
                        // Sharp crest verification: Real claps have an intense peak with very low average energy
                        val isSharpTransient = maxPeak > CLAP_PEAK_THRESHOLD && (maxPeak / (avgEnergy + 1)) > 5

                        if (isSharpTransient) {
                            val now = System.currentTimeMillis()
                            // Requires two distinct sharp spikes between 150ms and 550ms apart
                            if (now - lastClapTime in 150..550) {
                                mainHandler.post {
                                    exitFrozenStandby()
                                    speak("Hey boss, it seems you are happy today.")
                                }
                                lastClapTime = 0
                            } else {
                                lastClapTime = now
                            }
                        }
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Clap detector exception: ${e.message}")
        }
    }

    private fun stopClapDetectorThread() {
        isClapDetectorRunning = false
        try {
            clapAudioRecord?.stop()
            clapAudioRecord?.release()
            clapAudioRecord = null
        } catch (_: Exception) {}
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
        stopClapDetectorThread()
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}
