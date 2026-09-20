package com.jarvis.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private val client = OkHttpClient()

    // PASTE YOUR REAL GROQ API KEY INSIDE THE QUOTES BELOW
    private val groqApiKey = "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"

    private var recognizedText by mutableStateOf("Listening...")
    private var assistantResponse by mutableStateOf("")
    private var isListening by mutableStateOf(false)
    private var isContinuousModeActive by mutableStateOf(true)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            initSpeechRecognizer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        tts = TextToSpeech(this, this)

        val serviceIntent = Intent(this, JarvisService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val requiredPermissionsList = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissionsList.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val requiredPermissions = requiredPermissionsList.toTypedArray()
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            initSpeechRecognizer()
        } else {
            permissionsLauncher.launch(requiredPermissions)
        }

        setContent {
            JarvisSiriOverlay()
        }
    }

    private fun initSpeechRecognizer() {
        mainHandler.post {
            if (::speechRecognizer.isInitialized) {
                speechRecognizer.destroy()
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    if (isContinuousModeActive && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                        mainHandler.postDelayed({
                            if (isContinuousModeActive) startListening()
                        }, 500)
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val spokenText = matches[0]
                        recognizedText = spokenText
                        processCommand(spokenText)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening()
        }
    }

    private fun startListening() {
        mainHandler.post {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                }
                try {
                    speechRecognizer.startListening(intent)
                } catch (e: Exception) {
                    initSpeechRecognizer()
                }
            }
        }
    }

    private fun dismissOverlay() {
        isContinuousModeActive = false
        isListening = false
        mainHandler.post {
            speechRecognizer.stopListening()
        }
        finish()
    }

    private fun openAppByName(appName: String): Boolean {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(intent, 0)
        val target = appName.lowercase().trim()

        for (resolveInfo in apps) {
            val label = resolveInfo.loadLabel(pm).toString().lowercase().trim()
            if (label == target || label.contains(target) || target.contains(label)) {
                val launchIntent = pm.getLaunchIntentForPackage(resolveInfo.activityInfo.packageName)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    return true
                }
            }
        }
        return false
    }

    private fun getPhoneNumberForName(contactName: String): Pair<String, String>? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$contactName%")

        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIndex != -1 && numberIndex != -1) {
                    val foundName = it.getString(nameIndex)
                    val foundNumber = it.getString(numberIndex)
                    return Pair(foundName, foundNumber)
                }
            }
        }
        return null
    }

    private fun makePhoneCall(number: String) {
        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$number")
        }
        startActivity(callIntent)
    }

    private fun processCommand(query: String) {
        val cleanQuery = query.lowercase().trim()

        if (cleanQuery in listOf("stop", "exit", "goodbye", "bye", "cancel", "that's all", "dismiss", "close")) {
            dismissOverlay()
            return
        }

        when {
            // UNLOCK COMMAND
            cleanQuery.contains("unlock") -> {
                val service = JarvisAccessibilityService.instance
                if (service != null) {
                    val reply = "Unlocking your device now, sir."
                    assistantResponse = reply
                    speakAndExecute(reply) {
                        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            km.requestDismissKeyguard(this@MainActivity, null)
                        }
                        finish()
                        Handler(Looper.getMainLooper()).postDelayed({
                            service.unlockDevice()
                        }, 500)
                    }
                } else {
                    val reply = "Please enable Jarvis in your Accessibility settings first, sir."
                    assistantResponse = reply
                    speakAndListen(reply)
                }
            }

            // YOUTUBE PLAY COMMAND
            cleanQuery.startsWith("play ") -> {
                val songQuery = cleanQuery
                    .removePrefix("play ")
                    .removeSuffix("on youtube")
                    .trim()

                val reply = "Playing $songQuery on YouTube, sir."
                assistantResponse = reply
                speakAndExecute(reply) {
                    val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://www.youtube.com/results?search_query=$songQuery")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        startActivity(ytIntent)
                    } catch (e: Exception) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$songQuery")))
                    }

                    Handler(Looper.getMainLooper()).postDelayed({
                        JarvisAccessibilityService.instance?.clickFirstVisibleResult()
                    }, 2200)

                    dismissOverlay()
                }
            }

            // CAMERA / TAKE PHOTO COMMAND
            cleanQuery.contains("open camera") || cleanQuery.contains("take a picture") || cleanQuery.contains("take a photo") -> {
                val isCapture = cleanQuery.contains("take a")
                val reply = if (isCapture) "Taking a photo now, sir." else "Opening camera, sir."
                assistantResponse = reply
                speakAndExecute(reply) {
                    val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(cameraIntent)

                    if (isCapture) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            JarvisAccessibilityService.instance?.tap(540f, 2100f)
                        }, 2000)
                    }

                    dismissOverlay()
                }
            }

            // GENERIC APP LAUNCH
            cleanQuery.startsWith("open ") || cleanQuery.startsWith("launch ") -> {
                val appTarget = cleanQuery
                    .removePrefix("open ")
                    .removePrefix("launch ")
                    .trim()

                val success = openAppByName(appTarget)
                val reply = if (success) {
                    "Opening $appTarget, sir."
                } else {
                    "I could not find $appTarget on your device, sir."
                }
                assistantResponse = reply
                speakAndListen(reply)
            }

            // CALL COMMAND
            cleanQuery.startsWith("call ") || cleanQuery.startsWith("dial ") -> {
                val contactTarget = cleanQuery
                    .removePrefix("call ")
                    .removePrefix("dial ")
                    .trim()

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                    val reply = "Permission to place calls has not been granted, sir."
                    assistantResponse = reply
                    speakAndListen(reply)
                    return
                }

                if (contactTarget.replace("[\\s-]".toRegex(), "").all { it.isDigit() }) {
                    isContinuousModeActive = false
                    val reply = "Calling $contactTarget now, sir."
                    assistantResponse = reply
                    speakAndExecute(reply) { makePhoneCall(contactTarget) }
                } else {
                    val contactMatch = getPhoneNumberForName(contactTarget)
                    if (contactMatch != null) {
                        isContinuousModeActive = false
                        val (name, number) = contactMatch
                        val reply = "Calling $name now, sir."
                        assistantResponse = reply
                        speakAndExecute(reply) { makePhoneCall(number) }
                    } else {
                        val reply = "I could not find $contactTarget in your contacts, sir."
                        assistantResponse = reply
                        speakAndListen(reply)
                    }
                }
            }

            // GROQ AI FALLBACK
            else -> {
                assistantResponse = "Thinking..."
                CoroutineScope(Dispatchers.IO).launch {
                    val answer = callGroqApi(query)
                    withContext(Dispatchers.Main) {
                        assistantResponse = answer
                        speakAndListen(answer)
                    }
                }
            }
        }
    }

    private fun callGroqApi(prompt: String): String {
        return try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val payload = JSONObject().apply {
                put("model", "openai/gpt-oss-20b")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are JARVIS, Tony Stark's AI assistant. Keep responses very brief, articulate, and natural (1 to 2 sentences max).")
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
                .post(payload.toString().toRequestBody(mediaType))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return "Groq Error ${response.code}: $responseBody"
            }

            val jsonResponse = JSONObject(responseBody)
            jsonResponse
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            "Error: ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.UK
            tts.setPitch(0.92f)
            tts.setSpeechRate(1.05f)

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "JARVIS_CONTINUOUS" && isContinuousModeActive) {
                        mainHandler.postDelayed({
                            startListening()
                        }, 250)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    private fun speakAndListen(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_CONTINUOUS")
    }

    private fun speakAndExecute(text: String, action: () -> Unit) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "JARVIS_EXECUTE") {
                    mainHandler.post { action() }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_EXECUTE")
    }

    override fun onDestroy() {
        super.onDestroy()
        isContinuousModeActive = false
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    @Composable
    fun JarvisSiriOverlay() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { dismissOverlay() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(Color(0xFF, 0x10, 0x18, 0x27), RoundedCornerShape(32.dp))
                    .cli
