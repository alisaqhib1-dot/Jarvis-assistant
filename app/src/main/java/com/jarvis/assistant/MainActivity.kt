package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var deviceController: DeviceController

    private lateinit var tvStatus: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSpeak: Button
    private lateinit var btnSend: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceController = DeviceController(this)

        // Build UI programmatically to avoid R resource linking errors
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
        }

        tvStatus = TextView(this).apply {
            text = "ACRUX Online"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        rootLayout.addView(tvStatus)

        etInput = EditText(this).apply {
            hint = "Type a command..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(30, 30, 30, 30)
        }
        rootLayout.addView(etInput)

        btnSend = Button(this).apply {
            text = "Execute Command"
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
        }
        rootLayout.addView(btnSend)

        btnSpeak = Button(this).apply {
            text = "Voice Input"
            setBackgroundColor(Color.parseColor("#007ACC"))
            setTextColor(Color.WHITE)
        }
        rootLayout.addView(btnSpeak)

        setContentView(rootLayout)

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

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
                tvStatus.text = "Tap Voice Input to speak"
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
            lower.contains("turn on flashlight") || lower.contains("torch on") -> {
                val response = deviceController.setFlashlight(true)
                speak(response)
            }
            lower.contains("turn off flashlight") || lower.contains("torch off") -> {
                val response = deviceController.setFlashlight(false)
                speak(response)
            }
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
            lower.contains("battery") || lower.contains("telemetry") -> {
                val response = deviceController.getBatteryTelemetry()
                speak(response)
            }
            lower.contains("clean cache") || lower.contains("clear junk") || lower.contains("clean storage") -> {
                val response = deviceController.cleanAppCache()
                speak(response)
            }
            lower.contains("brightness") -> {
                val numbers = Regex("\\d+").findAll(lower).map { it.value.toInt() }.toList()
                val level = if (numbers.isNotEmpty()) numbers[0] else 128
                val response = deviceController.setBrightness(level)
                speak(response)
            }
            lower.startsWith("send whatsapp") || lower.startsWith("whatsapp") -> {
                speak("Opening WhatsApp")
                JarvisAccessibilityService.instance?.clickWhatsAppSend()
            }
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
