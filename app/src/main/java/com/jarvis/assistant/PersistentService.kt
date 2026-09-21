package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.speech.tts.Voice
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class PersistentService : Service(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var tts: TextToSpeech? = null
    private lateinit var deviceController: DeviceController
    private val scope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    
    private var isExecuting = false
    private var isSpeaking = false

    override fun onCreate() {
        super.onCreate()
        deviceController = DeviceController(this)
        tts = TextToSpeech(this, this)
        startNotification()
        setupSpeechEngine()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US

            try {
                val voices = tts?.voices
                if (!voices.isNullOrEmpty()) {
                    // Forcefully hunt for any installed male voice token
                    val targetVoice = voices.firstOrNull { v ->
                        val n = v.name.lowercase()
                        (n.contains("en-us-x-sfg") || n.contains("en-us-x-iol") || n.contains("male")) && 
                        !n.contains("female")
                    }
                    if (targetVoice != null) {
                        tts?.voice = targetVoice
                    }
                }
            } catch (_: Exception) {}

            // Deep masculine tone
            tts?.setPitch(0.65f)
            tts?.setSpeechRate(0.88f)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scheduleNextListen(500)
        return START_STICKY
    }

    private fun startNotification() {
        val channelId = "zuraiz_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "ZURAIZ Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ZURAIZ Online")
            .setContentText("Tactical systems standing by.")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun setupSpeechEngine() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                // Ignore silent timeouts, restart smoothly after a pause without spamming
                if (!isSpeaking && !isExecuting) {
                    scheduleNextListen(1200)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()
                if (!text.isNullOrEmpty()) {
                    processIncomingInput(text)
                } else {
                    scheduleNextListen(1000)
                }
            }
        })
    }

    private fun scheduleNextListen(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!isSpeaking && !isExecuting) {
                try {
                    speechRecognizer?.cancel()
                    recognizerIntent?.let { speechRecognizer?.startListening(it) }
                } catch (_: Exception) {
                    scheduleNextListen(2000)
                }
            }
        }, delayMs)
    }

    private fun processIncomingInput(command: String) {
        isExecuting = true
        speechRecognizer?.cancel()

        val lower = command.lowercase()
        if (lower == "stop" || lower == "shut up" || lower == "quiet") {
            tts?.stop()
            isExecuting = false
            scheduleNextListen(800)
            return
        }

        val handled = deviceController.executeDirective(command) { message ->
            speakOut(message)
        }

        if (!handled) {
            scope.launch {
                val reply = GroqClient.query(command)
                speakOut(reply)
            }
        }
    }

    private fun speakOut(text: String) {
        isSpeaking = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ZuraizAudio")
        
        // Wait until speech finishes before listening again to prevent self-triggering
        val estimatedDuration = (text.length * 75L) + 1500L
        handler.postDelayed({
            isSpeaking = false
            isExecuting = false
            scheduleNextListen(500)
        }, estimatedDuration)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
