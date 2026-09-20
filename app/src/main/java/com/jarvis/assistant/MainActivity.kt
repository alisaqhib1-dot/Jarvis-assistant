package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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

    private var recognizedText by mutableStateOf("Press MIC to speak")
    private var assistantResponse by mutableStateOf("")
    private var isListening by mutableStateOf(false)

    // Request permissions for Audio, Phone Calling, and Reading Contacts
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
                recognizedText = "Recognition error: $error"
            }

            override fun onResults(results: Bundle?) {
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

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer.startListening(intent)
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
            != PackageManager.PERMISSION_GRANTED) {
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

        when {
            // 1. App Launching Commands
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
                speak(reply)
            }

            // 2. Call / Dial Commands
            cleanQuery.startsWith("call ") || cleanQuery.startsWith("dial ") -> {
                val contactTarget = cleanQuery
                    .removePrefix("call ")
                    .removePrefix("dial ")
                    .trim()

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                    val reply = "Permission to place calls has not been granted, sir."
                    assistantResponse = reply
                    speak(reply)
                    return
                }

                // If user dictates digits directly (e.g. "call 9876543210")
                if (contactTarget.replace("[\\s-]".toRegex(), "").all { it.isDigit() }) {
                    val reply = "Calling $contactTarget now, sir."
                    assistantResponse = reply
                    speak(reply)
                    makePhoneCall(contactTarget)
                } else {
                    // Look up contact name in the phonebook
                    val contactMatch = getPhoneNumberForName(contactTarget)
                    if (contactMatch != null) {
                        val (name, number) = contactMatch
                        val reply = "Calling $name now, sir."
                        assistantResponse = reply
                        speak(reply)
                        makePhoneCall(number)
                    } else {
                        val reply = "I could not find $contactTarget in your contacts, sir."
                        assistantResponse = reply
                        speak(reply)
                    }
                }
            }

            // 3. Fallback to Groq AI
            else -> {
                assistantResponse = "Thinking..."
                CoroutineScope(Dispatchers.IO).launch {
                    val answer = callGroqApi(query)
                    withContext(Dispatchers.Main) {
                        assistantResponse = answer
                        speak(answer)
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
                        put("content", "You are JARVIS, Tony Stark's sophisticated, polite, and witty AI assistant. Keep all responses brief, articulate, and natural.")
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
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_TTS")
    }

    override fun onDestroy() {
        super.onDestroy()
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
                onClick = { startListening() },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isListening) Color(0xFFE53935) else Color(0xFF0288D1)
                ),
                modifier = Modifier
                    .size(90.dp)
                    .padding(bottom = 20.dp)
            ) {
                Text(
                    text = if (isListening) "..." else "MIC",
                    color = Color.White,
                    fontSize = 16.sp
                )
            }
        }
    }
}
