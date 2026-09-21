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
    private var isListening = false

    override fun onCreate() {
        super.onCreate()
        deviceController = DeviceController(this)
        tts = TextToSpeech(this, this)
        startNotification()
        initListener()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US

            try {
                val voices = tts?.voices
                if (!voices.isNullOrEmpty()) {
                    // Look strictly for male voice tags
                    val targetVoice = voices.firstOrNull { v ->
                        val name = v.name.lowercase()
                        (name.contains("male") || name.contains("en-us-x-sfg") || name.contains("en-us-x-iol")) &&
                                !name.contains("female")
                    }
                    if (targetVoice != null) {
                        tts?.voice = targetVoice
                    }
                }
            } catch (_: Exception) {}

            // Force a deep, commanding tone regardless of default engine profile
            tts?.setPitch(0.70f)
            tts?.setSpeechRate(0.90f)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        restartListeningWithDelay(500)
        return START_STICKY
    }

    private fun startNotification() {
        val channelId = "zuraiz_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "ZURAIZ Background Engine",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ZURAIZ Active")
            .setContentText("Awaiting commands...")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun initListener() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                isListening = false
                // Prevent rapid loop crash by throttling retries
                restartListeningWithDelay(1500)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()
                if (!text.isNullOrEmpty()) {
                    handleDirective(text)
                } else {
                    restartListeningWithDelay(800)
                }
            }
        })
    }

    private fun restartListeningWithDelay(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            try {
                if (!isListening) {
                    speechRecognizer?.cancel()
                    recognizerIntent?.let { speechRecognizer?.startListening(it) }
                }
            } catch (_: Exception) {
                handler.postDelayed({ restartListeningWithDelay(1000) }, 1000)
            }
        }, delayMs)
    }

    private fun handleDirective(command: String) {
        val lower = command.lowercase()

        if (lower == "stop" || lower == "shut up" || lower == "quiet") {
            tts?.stop()
            restartListeningWithDelay(1000)
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
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ZuraizAudioID")
        // Give TTS time to speak before turning the mic back on
        restartListeningWithDelay(2500)
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
