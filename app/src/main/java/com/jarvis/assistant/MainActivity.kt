package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private val client = OkHttpClient()

    private val groqApiKey = "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"

    private var recognizedText by mutableStateOf("Tap the button and speak...")
    private var assistantResponse by mutableStateOf("")
    private var isListening by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startListening()
        } else {
            recognizedText = "Audio permission denied."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textToSpeech = TextToSpeech(this, this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "JARVIS",
                            fontSize = 32.sp,
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.padding(top = 32.dp)
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = recognizedText,
                                fontSize = 18.sp,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = assistantResponse,
                                fontSize = 16.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        Button(
                            onClick = { checkPermissionAndListen() },
                            modifier = Modifier
                                .padding(bottom = 32.dp)
                                .size(80.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isListening) Color.Red else Color(0xFF0284C7)
                            )
                        ) {
                            Text(if (isListening) "STOP" else "MIC")
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionAndListen() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    recognizedText = "Listening..."
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    recognizedText = "Error listening: $error"
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val query = matches[0]
                        recognizedText = query
                        handleAssistantQuery(query)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        speechRecognizer?.startListening(intent)
    }

    private fun handleAssistantQuery(query: String) {
        if (query.lowercase().startsWith("call ")) {
            val name = query.substring(5).trim()
            assistantResponse = "Calling feature triggered for $name"
            speak(assistantResponse)
            return
        }

        assistantResponse = "Thinking..."
        CoroutineScope(Dispatchers.IO).launch {
            val answer = callGroqApi(query)
            withContext(Dispatchers.Main) {
                assistantResponse = answer
                speak(answer)
            }
        }
    }

    private fun callGroqApi(prompt: String): String {
        return try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val payload = JSONObject().apply {
           put("model", "gemma2-9b-it")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are Jarvis, a precise and concise voice assistant.")
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

            val json = JSONObject(responseBody)
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            "Error contacting assistant: ${e.localizedMessage}"
        }
    }

    private fun speak(text: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.UK
            textToSpeech?.setSpeechRate(0.92f)
            textToSpeech?.setPitch(0.78f)

            textToSpeech?.voices?.find { voice ->
                voice.locale.language == "en" &&
                (voice.name.lowercase().contains("male") || voice.name.lowercase().contains("#male"))
            }?.let { maleVoice ->
                textToSpeech?.voice = maleVoice
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}
