package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
                val availableVoices = tts?.voices ?: emptySet()
                
                // Target adult male voice models installed on Android TTS engines
                val maleVoice = availableVoices.firstOrNull { voice ->
                    val name = voice.name.lowercase()
                    (name.contains("en-us-x-sfg") || 
                     name.contains("en-us-x-iol") || 
                     name.contains("en-us-x-tpc") || 
                     name.contains("male")) && 
                     !name.contains("female")
                }

                if (maleVoice != null) {
                    tts?.voice = maleVoice
                    tts?.setPitch(0.85f)
                    tts?.setSpeechRate(0.92f)
                } else {
                    tts?.setPitch(0.80f)
                    tts?.setSpeechRate(0.90f)
                }
            } catch (_: Exception) {
                tts?.setPitch(0.80f)
                tts?.setSpeechRate(0.90f)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListening()
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
            .setContentText("Listening for commands...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun initListener() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
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
                startListening()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()
                if (!text.isNullOrEmpty()) {
                    handleDirective(text)
                }
                startListening()
            }
        })
    }

    private fun startListening() {
        try {
            recognizerIntent?.let { speechRecognizer?.startListening(it) }
        } catch (_: Exception) {}
    }

    private fun handleDirective(command: String) {
        val lower = command.lowercase()

        // Immediate stop trigger
        if (lower == "stop" || lower == "shut up" || lower == "quiet") {
            tts?.stop()
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
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
