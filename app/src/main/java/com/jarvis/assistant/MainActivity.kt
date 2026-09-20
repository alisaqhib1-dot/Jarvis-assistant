package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var deviceController: DeviceController

    private lateinit var tvStatus: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSpeak: Button
    private lateinit var btnSend: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceController = DeviceController(this)

        tvStatus = findViewById(R.id.tvStatus)
        etInput = findViewById(R.id.etInput)
        btnSpeak = findViewById(R.id.btnSpeak)
        btnSend = findViewById(R.id.btnSend)

        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        setupSpeechRecognizer()
        requestImmortalPermissions()

        btnSpeak.setOnClickListener {
            startListening()
        }

        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                handleUserCommand(text)
                etInput.text.clear()
            }
        }
    }

    private fun requestImmortalPermissions() {
        // Request microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }

        // Request battery optimization exemption for 24/7 background lock
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        // Request write settings permission for brightness control
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                tvStatus.text = "Listening..."
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                tvStatus.text = "Processing..."
            }
            override fun onError(error: Int) {
                tvStatus.text = "Tap to speak"
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val query = matches[0]
                    tvStatus.text = query
                    handleUserCommand(query)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer.startListening(intent)
    }

    private fun handleUserCommand(command: String) {
        val lower = command.lowercase(Locale.ROOT)

        when {
            // Flashlight
            lower.contains("turn on flashlight") || lower.contains("torch on") -> {
                val response = deviceController.setFlashlight(true)
                speak(response)
            }
            lower.contains("turn off flashlight") || lower.contains("torch off") -> {
                val response = deviceController.setFlashlight(false)
                speak(response)
            }

            // Silent & Sound Modes
            lower.contains("silent") || lower.contains("mute") -> {
                val response = deviceController.setRingerMode("silent")
                speak(response)
            }
            lower.contains("vibrate") -> {
                val response = deviceController.setRingerMode("vibrate")
                speak(response)
            }
            lower.contains("normal mode") || lower.contains("unmute") || lower.contains("ring mode") -> {
                val response = deviceController.setRingerMode("normal")
                speak(response)
            }

            // Battery Telemetry
            lower.contains("battery") || lower.contains("telemetry") -> {
                val response = deviceController.getBatteryTelemetry()
                speak(response)
            }

            // Cache & Storage Junk
            lower.contains("clean cache") || lower.contains("clear junk") || lower.contains("clean storage") -> {
                val response = deviceController.cleanAppCache()
                speak(response)
            }

            // Screen Brightness
            lower.contains("brightness") -> {
                val numbers = Regex("\\d+").findAll(lower).map { it.value.toInt() }.toList()
                val level = if (numbers.isNotEmpty()) numbers[0] else 128
                val response = deviceController.setBrightness(level)
                speak(response)
            }

            // WhatsApp Dispatch fallback
            lower.startsWith("send whatsapp") || lower.startsWith("whatsapp") -> {
                speak("Opening WhatsApp")
                JarvisAccessibilityService.instance?.clickWhatsAppSend()
            }

            // Unlock phone
            lower.contains("unlock") -> {
                JarvisAccessibilityService.instance?.unlockDevice()
            }

            else -> {
                speak("Command received: $command")
            }
        }
    }

    private fun speak(text: String) {
        tvStatus.text = text
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UTTERANCE_ID")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        speechRecognizer.destroy()
    }
}
