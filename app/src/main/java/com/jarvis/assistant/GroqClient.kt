package com.jarvis.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GroqClient {

    // Keep your actual Groq key here
    private const val API_KEY = "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"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "openai/gpt-oss-20b"
    
    

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """
        You are ACRUX, an advanced, highly intelligent tactical AI assistant operating directly inside an Android system.
        Give direct, concise, sharp, and helpful answers. Avoid unnecessary pleasantries or fluff.
        Keep responses suitable for voice speech playback (usually 1-3 sentences unless detailed technical explanation is requested).
    """.trimIndent()

    suspend fun query(userInput: String): String = withContext(Dispatchers.IO) {
        try {
            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userInput)
                })
            }

            val requestJson = JSONObject().apply {
                put("model", MODEL)
                put("messages", messagesArray)
                put("temperature", 0.6)
                put("max_tokens", 250)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("Authorization", "Bearer $API_KEY")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && !responseBody.isNullOrEmpty()) {
                val jsonResponse = JSONObject(responseBody)
                val choices = jsonResponse.getJSONArray("choices")
                if (choices.length() > 0) {
                    val message = choices.getJSONObject(0).getJSONObject("message")
                    return@withContext message.getString("content").trim()
                }
            }
            
            // Extract exact error detail from Groq if available
            val errorDetail = try {
                val errJson = JSONObject(responseBody ?: "")
                errJson.getJSONObject("error").getString("message")
            } catch (e: Exception) {
                "Code: ${response.code}"
            }
            "Communication relay error: $errorDetail"
        } catch (e: Exception) {
            e.printStackTrace()
            "Neural link unreachable: ${e.localizedMessage ?: "Unknown error"}"
        }
    }
}
