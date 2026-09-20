package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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

    // PASTE YOUR REAL GROQ API KEY HERE (keep the double quotes)
    private val groqApiKey = "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"

    private var recognizedText by mutableStateOf("Press MIC to start conversation")
    private var assistantResponse by mutableStateOf("")
    private var isListening by mutableStateOf(false)
    private var isContinuousModeActive by mutableStateOf(false)

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

        tts = TextToSpeech(this, this)

        val requiredPermissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            initSpeechRecognizer()
        } else {
            permissionsLauncher.launch(requiredPermissions)
        }

        setContent {
            JarvisScreen()
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
                    // If no speech detected in continuous mode, retry listening after brief delay
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

    private fun stopContinuousConversation() {
        isContinuousModeActive = false
        isListening = false
        mainHandler.post {
            speechRecognizer.stopListening()
        }
        val reply = "Standing by, sir."
        assistantResponse = reply
        tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "STANDBY_ID")
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

        // 1. Exit Continuous Mode Commands
        if (cleanQuery in listOf("stop", "exit", "goodbye", "bye", "cancel", "that's all", "sleep")) {
            stopContinuousConversation()
            return
        }

        when {
            // 2. App Launching Commands
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

            // 3. Phone Call Commands
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

                // If user dictates digits directly
                if (contactTarget.replace("[\\s-]".toRegex(), "").all { it.isDigit() }) {
                    isContinuousModeActive = false // Pause continuous mode during phone call
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

            // 4. General Groq AI Response
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
                        put("content", "You are JARVIS, Tony Stark's AI assistant. Give very concise, witty, and helpful responses in 1-2 sentences.")
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

            // Listen for when TTS finishes speaking
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "JARVIS_CONTINUOUS" && isContinuousModeActive) {
                        mainHandler.postDelayed({
                            startListening()
                        }, 250) // Small pause before turning mic back on
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    // Speaks text and immediately listens again when done
    private fun speakAndListen(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_CONTINUOUS")
    }

    // Speaks text, then executes an action (like dialing a call)
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
    fun JarvisScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1325))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "JARVIS",
                color = Color(0xFF64B5F6),
                fontSize = 28.sp,
                modifier = Modifier.padding(top = 40.dp)
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = recognizedText,
                    color = Color.White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (assistantResponse.isNotEmpty()) {
                    Text(
                        text = assistantResponse,
                        color = Color(0xFFB0BEC5),
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Button(
                onClick = {
                    if (isContinuousModeActive) {
                        stopContinuousConversation()
                    } else {
                        isContinuousModeActive = true
                        startListening()
                    }
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        isListening -> Color(0xFFE53935)            // Red while hearing you
                        isContinuousModeActive -> Color(0xFF43A047) // Green when continuous mode is ON
                        else -> Color(0xFF0288D1)                   // Blue when idle
                    }
                ),
                modifier = Modifier
                    .size(90.dp)
                    .padding(bottom = 20.dp)
            ) {
                Text(
                    text = when {
                        isListening -> "Listening"
                        isContinuousModeActive -> "Active"
                        else -> "MIC"
                    },
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }
    }
}

