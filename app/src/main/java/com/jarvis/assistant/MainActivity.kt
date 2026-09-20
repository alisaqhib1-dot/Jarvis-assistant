package com.jarvis.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // PASTE YOUR GROQ API KEY INSIDE THE QUOTES BELOW
    private val groqApiKey = "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"

    private lateinit var recognizedTextView: TextView
    private lateinit var responseTextView: TextView
    private var isContinuousModeActive = true
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) initSpeechRecognizer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        setupSimpleUi()
        tts = TextToSpeech(this, this)

        val serviceIntent = Intent(this, JarvisService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)

        val required = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) required.add(Manifest.permission.POST_NOTIFICATIONS)

        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) initSpeechRecognizer() else permissionsLauncher.launch(missing.toTypedArray())
    }

    private fun setupSimpleUi() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismissOverlay() }
        }

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(60, 50, 60, 60)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6101827"))
                cornerRadius = 70f
            }
            setOnClickListener { }
        }

        recognizedTextView = TextView(this).apply {
            text = "Listening, Sir Yuno..."
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
        }

        responseTextView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#80D8FF"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
        }

        cardLayout.addView(recognizedTextView)
        cardLayout.addView(responseTextView)
        rootLayout.addView(cardLayout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(40, 0, 40, 70)
        })
        setContentView(rootLayout)
    }

    private fun initSpeechRecognizer() {
        mainHandler.post {
            if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (isContinuousModeActive && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                        mainHandler.postDelayed({ if (isContinuousModeActive) startListening() }, 500)
                    }
                }
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    recognizedTextView.text = spoken
                    processCommand(spoken)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening()
        }
    }

    private fun startListening() {
        mainHandler.post {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                }
                try { speechRecognizer.startListening(intent) } catch (e: Exception) { initSpeechRecognizer() }
            }
        }
    }

    private fun dismissOverlay() {
        isContinuousModeActive = false
        mainHandler.post { if (::speechRecognizer.isInitialized) speechRecognizer.stopListening() }
        finish()
    }

    private fun toggleFlashlight(state: Boolean): Boolean {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(cm.cameraIdList[0], state)
            true
        } catch (e: Exception) { false }
    }

    private fun openAppByName(appName: String): Boolean {
        val pm = packageManager
        val target = appName.lowercase()
            .replace("please", "")
            .replace("can you", "")
            .replace("open", "")
            .replace("launch", "")
            .trim()

        val directMap = mapOf(
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "insta" to "com.instagram.android",
            "youtube" to "com.google.android.youtube",
            "camera" to "com.android.camera",
            "chrome" to "com.android.chrome",
            "settings" to "com.android.settings",
            "free fire" to "com.dts.freefireth",
            "freefire" to "com.dts.freefireth"
        )

        for ((key, pkg) in directMap) {
            if (target.contains(key)) {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    return true
                }
            }
        }

        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = pm.queryIntentActivities(intent, 0)

        for (info in apps) {
            val label = info.loadLabel(pm).toString().lowercase().trim()
            val pkg = info.activityInfo.packageName.lowercase()
            if (label == target || label.contains(target) || pkg.contains(target)) {
                val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    return true
                }
            }
        }
        return false
    }

    private fun getContactPhone(name: String): Pair<String, String>? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val cleanQuery = name.trim().lowercase()
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$cleanQuery%"), null
        )
        cursor?.use {
            if (it.moveToFirst()) return Pair(it.getString(0), it.getString(1))
        }
        return null
    }

    private fun processCommand(query: String) {
        var q = query.lowercase().trim()
        q = q.removePrefix("hey acrux").removePrefix("acrux").removePrefix("hey jarvis").removePrefix("jarvis").trim()

        if (q in listOf("stop", "exit", "cancel", "dismiss", "close", "bye")) {
            dismissOverlay()
            return
        }

        when {
            // Flashlight
            q.contains("flashlight on") || q.contains("torch on") -> {
                val ok = toggleFlashlight(true)
                respond(if (ok) "Flashlight activated, Boss." else "Unable to turn on flashlight, Sir Yuno.")
            }
            q.contains("flashlight off") || q.contains("torch off") -> {
                val ok = toggleFlashlight(false)
                respond(if (ok) "Flashlight turned off, Boss." else "Unable to turn off flashlight, Sir Yuno.")
            }

            // Unlock
            q.contains("unlock") -> {
                val service = JarvisAccessibilityService.instance
                if (service != null) {
                    respond("Unlocking device now, Sir Yuno.", execute = {
                        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) km.requestDismissKeyguard(this, null)
                        finish()
                        mainHandler.postDelayed({ service.unlockDevice() }, 500)
                    })
                } else {
                    respond("Please enable ACRUX in Accessibility settings, Boss.")
                }
            }

            // YouTube
            q.contains("play ") -> {
                val song = q.substringAfter("play ").removeSuffix("on youtube").trim()
                respond("Playing $song on YouTube, Boss.", execute = {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://www.youtube.com/results?search_query=$song")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    } catch (e: Exception) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$song")))
                    }
                    mainHandler.postDelayed({ JarvisAccessibilityService.instance?.clickFirstVisibleResult() }, 2200)
                    dismissOverlay()
                })
            }

            // Camera / Photo
            q.contains("take a photo") || q.contains("take a picture") || q.contains("click a photo") || q.contains("click a picture") -> {
                respond("Capturing photo, Sir Yuno.", execute = {
                    startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    mainHandler.postDelayed({ JarvisAccessibilityService.instance?.tap(540f, 2100f) }, 2000)
                    dismissOverlay()
                })
            }

            // WhatsApp Messaging (Flexible matching)
            q.contains("whatsapp") || q.contains("message") || q.contains("msg") || q.contains("text ") -> {
                var messageContent = ""
                var contactQuery = ""

                if (q.contains(" saying ")) {
                    val parts = q.split(" saying ", limit = 2)
                    contactQuery = parts[0]
                    messageContent = parts[1]
                } else if (q.contains(" that ")) {
                    val parts = q.split(" that ", limit = 2)
                    contactQuery = parts[0]
                    messageContent = parts[1]
                }

                // Clean filler words from the target contact name
                contactQuery = contactQuery
                    .replace("send a message to", "")
                    .replace("send message to", "")
                    .replace("message to", "")
                    .replace("message", "")
                    .replace("msg to", "")
                    .replace("msg", "")
                    .replace("whatsapp to", "")
                    .replace("whatsapp", "")
                    .replace("text to", "")
                    .replace("text", "")
                    .replace("please", "")
                    .trim()

                if (contactQuery.isNotEmpty() && messageContent.isNotEmpty()) {
                    val contact = getContactPhone(contactQuery)
                    if (contact != null) {
                        var num = contact.second.replace("[^0-9+]".toRegex(), "")
                        if (!num.startsWith("+") && num.length == 10) num = "+91$num"
                        respond("Sending message to ${contact.first}, Boss.", execute = {
                            try {
                                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$num&text=${URLEncoder.encode(messageContent, "UTF-8")}")
                                startActivity(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp"); flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                                mainHandler.postDelayed({ JarvisAccessibilityService.instance?.clickWhatsAppSend() }, 1800)
                                dismissOverlay()
                            } catch (e: Exception) { respond("Unable to open WhatsApp, Sir Yuno.") }
                        })
                    } else {
                        respond("Could not find $contactQuery in contacts, Sir Yuno.")
                    }
                } else {
                    // Fallback to Groq if the structure is incomplete
                    executeGroq(query)
                }
            }

            // Phone Calls (Matches direct & conversational phrasing)
            q.contains("call ") || q.contains("dial ") -> {
                val target = when {
                    q.contains("call ") -> q.substringAfter("call ")
                    else -> q.substringAfter("dial ")
                }
                .replace("on my phone", "")
                .replace("please", "")
                .replace("for me", "")
                .trim()

                val contact = getContactPhone(target)
                if (contact != null) {
                    respond("Calling ${contact.first}, Boss.", execute = {
                        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${contact.second}")).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                        dismissOverlay()
                    })
                } else {
                    respond("Could not find $target in contacts, Sir Yuno.")
                }
            }

            // App Launching
            q.contains("open ") || q.contains("launch ") -> {
                val app = when {
                    q.contains("open ") -> q.substringAfter("open ")
                    else -> q.substringAfter("launch ")
                }.trim()

                val success = openAppByName(app)
                if (success) {
                    respond("Opening $app, Sir Yuno.")
                    mainHandler.postDelayed({ dismissOverlay() }, 1000)
                } else {
                    respond("Could not find $app installed, Boss.")
                }
            }

            // Groq AI Fallback
            else -> {
                executeGroq(query)
            }
        }
    }

    private fun executeGroq(query: String) {
        responseTextView.text = "Processing..."
        CoroutineScope(Dispatchers.IO).launch {
            val answer = callGroqWithFallback(query)
            withContext(Dispatchers.Main) { respond(answer) }
        }
    }

    private fun respond(text: String, execute: (() -> Unit)? = null) {
        responseTextView.text = text

        val isHindi = text.any { it in '\u0900'..'\u097F' }
        try {
            if (isHindi) {
                val hiVoice = tts.voices?.firstOrNull { v ->
                    v.locale.language == "hi" && !v.name.lowercase().contains("female")
                }
                if (hiVoice != null) tts.voice = hiVoice
                tts.setPitch(0.70f)
            } else {
                val maleVoice = tts.voices?.firstOrNull { v ->
                    val n = v.name.lowercase()
                    (n.contains("en-gb-x-rjs") || n.contains("male") || n.contains("voice 2") || n.contains("voice 4")) && !n.contains("female")
                }
                if (maleVoice != null) tts.voice = maleVoice
                tts.setPitch(0.58f)
            }
        } catch (e: Exception) {}

        tts.setSpeechRate(0.95f)

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (execute != null) {
                    mainHandler.post { execute() }
                } else if (isContinuousModeActive) {
                    mainHandler.postDelayed({ startListening() }, 250)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {}
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_ID")
    }

    private fun callGroqWithFallback(prompt: String): String {
        val candidateModels = listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b")
        var lastErr = ""

        for (modelName in candidateModels) {
            try {
                val url = "https://api.groq.com/openai/v1/chat/completions"
                val payload = JSONObject().apply {
                    put("model", modelNa
