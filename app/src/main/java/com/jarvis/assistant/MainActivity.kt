package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var deviceController: DeviceController

    private lateinit var tvHeader: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSpeak: Button
    private lateinit var btnSend: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceController = DeviceController(this)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#090D16"))
            isFillViewport = true
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 70, 50, 50)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        tvHeader = TextView(this).apply {
            text = "A C R U X"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00E5FF"))
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 10)
        }
        rootLayout.addView(tvHeader)

        val tvSub = TextView(this).apply {
            text = "TACTICAL SYSTEM ONLINE"
            textSize = 11f
            setTextColor(Color.parseColor("#5A6E85"))
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 60)
        }
        rootLayout.addView(tvSub)

        val cardDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#121826"))
            cornerRadius = 24f
            setStroke(2, Color.parseColor("#1E293B"))
        }

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable
            setPadding(40, 40, 40, 40)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 60)
            layoutParams = params
        }

        tvStatus = TextView(this).apply {
            text = "Standing by for command..."
            textSize = 16f
            setTextColor(Color.parseColor("#E2E8F0"))
            gravity = Gravity.CENTER
            setLineSpacing(1.2f, 1.2f)
        }
        statusCard.addView(tvStatus)
        rootLayout.addView(statusCard)

        val inputDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#141C2E"))
            cornerRadius = 20f
            setStroke(2, Color.parseColor("#27354A"))
        }

        etInput = EditText(this).apply {
            hint = "Ask ACRUX or issue system directive..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = inputDrawable
            setPadding(36, 32, 36, 32)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 24)
            layoutParams = params
        }
        rootLayout.addView(etInput)

        val sendBtnDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#1E293B"))
            cornerRadius = 20f
            setStroke(2, Color.parseColor("#334155"))
        }

        btnSend = Button(this).apply {
            text = "EXECUTE DIRECTIVE"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#CBD5E1"))
            background = sendBtnDrawable
            setPadding(0, 30, 0, 30)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 20)
            layoutParams = params
        }
        rootLayout.addView(btnSend)

        val voiceBtnDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#00E5FF"))
            cornerRadius = 20f
        }

        btnSpeak = Button(this).apply {
            text = "● INITIATE VOICE"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#090D16"))
            background = voiceBtnDrawable
            setPadding(0, 32, 0, 32)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
        }
        rootLayout.addView(btnSpeak)

        scrollView.addView(rootLayout)
        setContentView(scrollView)

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
                tvStatus.text = "Listening for audio stream..."
                tvStatus.setTextColor(Color.parseColor("#00E5FF"))
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                tvStatus.text = "Processing directive..."
                tvStatus.setTextColor(Color.parseColor("#94A3B8"))
            }
            override fun onError(error: Int) {
                tvStatus.text = "Standing by. Tap Initiate Voice to retry."
                tvStatus.setTextColor(Color.parseColor("#EF4444"))
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val query = matches[0]
                    tvStatus.text = query
                    tvStatus.setTextColor(Color.WHITE)
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
                speak("Directive logged: $command")
            }
        }
    }

    private fun speak(text: String) {
        tvStatus.text = text
        tvStatus.setTextColor(Color.WHITE)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UTTERANCE_ID")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setPitch(0.95f)
            tts.setSpeechRate(0.95f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        speechRecognizer.destroy()
    }
}
