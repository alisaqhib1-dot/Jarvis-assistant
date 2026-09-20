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

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private val client = OkHttpClient()

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
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = pm.queryIntentActivities(intent, 0)
        val target = appName.lowercase().trim()

        for (info in apps) {
            val label = info.loadLabel(pm).toString().lowercase().trim()
            if (label == target || label.contains(target)) {
                val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    return true
                }
            }
        }
        return false
    }

    private fun getContactPhone(name: String): Pair<String, String>? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
        )
        cursor?.use {
            if (it.moveToFirst()) return Pair(it.getString(0), it.getString(1))
        }
        return null
    }

    private fun processCommand(query: String) {
        var q = query.lowercase().trim()
        q = q.removePrefix("hey acrux").removePrefix("acrux").removePrefix("jarvis").trim()

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
            q.contains("take a photo") || q.contains("take a picture") || q.contains("camera") -> {
                val snap = q.contains("take a")
                respond(if (snap) "Capturing photo, Sir Yuno." else "Opening camera, Boss.", execute = {
                    startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    if (snap) mainHandler.postDelayed({ JarvisAccessibilityService.instance?.tap(540f, 2100f) }, 2000)
                    dismissOverlay()
                })
            }

            // WhatsApp Messaging
            q.contains("msg ") || q.contains("message ") || q.contains("whatsapp ") -> {
                val clean = q.replace("send a message to ", "").replace("whatsapp ", "").replace("msg ", "").replace("message ", "").removeSuffix("on whatsapp").removePrefix("to ").trim()
                val parts = clean.split(" saying ", " that ", limit = 2)
                val targetName = if (parts.size > 1) parts[0].trim() else clean.substringBefore(" ").trim()
                val messageText = if (parts.size > 1) parts[1].trim() else clean.substringAfter(" ").trim()
                val contact = getContactPhone(targetName)

                if (contact != null) {
                    var num = contact.second.replace("[^0-9+]".toRegex(), "")
                    if (!num.startsWith("+") && num.length == 10) num = "+91$num"
                    respond("Sending message to ${contact.first}, Boss.", execute = {
                        try {
                            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$num&text=${URLEncoder.encode(messageText, "UTF-8")}")
                            startActivity(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp"); flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                            mainHandler.postDelayed({ JarvisAccessibilityService.instance?.clickWhatsAppSend() }, 1800)
                            dismissOverlay()
                        } catch (e: Exception) { respond("Unable to open WhatsApp, Sir Yuno.") }
                    })
                } else {
                    respond("Could not find $targetName in contacts, Sir Yuno.")
                }
            }

            // Phone Calls
            q.startsWith("call ") || q.startsWith("dial ") -> {
                val target = q.removePrefix("call ").removePrefix("dial ").trim()
                val contact = getContactPhone(target)
                if (contact != null) {
                    respond("Calling ${contact.first}, Boss.", execute = {
                        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${contact.second}")))
                    })
                } else {
                    respond("Could not find $target in contacts, Sir Yuno.")
                }
            }

            // Open App
            q.startsWith("open ") || q.startsWith("launch ") -> {
                val app = q.removePrefix("open ").removePrefix("launch ").trim()
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
                responseTextView.text = "Processing..."
                CoroutineScope(Dispatchers.IO).launch {
                    val answer = callGroq(query)
                    withContext(Dispatchers.Main) { respond(answer) }
                }
            }
        }
    }

    private fun respond(text: String, execute: (() -> Unit)? = null) {
        responseTextView.text = text

        // Dynamic Language Switching
        val isHindi = text.any { it in '\u0900'..'\u097F' }
        if (isHindi) {
            tts.language = Locale("hi", "IN")
            tts.setPitch(0.85f)
        } else {
            tts.language = Locale.UK
            tts.setPitch(0.78f)
        }

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

    private fun callGroq(prompt: String): String {
        return try {
            val payload = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are ACRUX, a tactical AI assistant. Answer concisely in 1-2 sentences. Speak naturally in whatever language the user speaks. Always address the user respectfully as 'Sir Yuno' or 'Boss'.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $groqApiKey")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val res = client.newCall(request).execute()
            val body = res.body?.string() ?: ""
            if (!res.isSuccessful) {
                return "Groq Error code ${res.code}, Boss."
            }
            JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } catch (e: Exception) {
            "Network error: ${e.message ?: "unknown"}, Boss."
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.UK
            try {
                tts.voices?.firstOrNull { it.name.lowercase().contains("en-gb-x-rjs") || it.name.lowercase().contains("male") }?.let { tts.voice = it }
            } catch (e: Exception) {}
            tts.setPitch(0.78f)
            tts.setSpeechRate(0.98f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isContinuousModeActive = false
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
    }
}
