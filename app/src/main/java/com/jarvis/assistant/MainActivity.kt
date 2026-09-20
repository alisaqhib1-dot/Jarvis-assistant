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
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
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

        setupSimpleUi()

        tts = TextToSpeech(this, this)

        val serviceIntent = Intent(this, JarvisService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val requiredList = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredList.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = requiredList.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            initSpeechRecognizer()
        } else {
            permissionsLauncher.launch(missing.toTypedArray())
        }
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

            val background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6101827"))
                cornerRadius = 70f
            }
            setBackground(background)
            setOnClickListener { }
        }

        recognizedTextView = TextView(this).apply {
            text = "Listening..."
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

        val cardParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(40, 0, 40, 70)
        }

        rootLayout.addView(cardLayout, cardParams)
        setContentView(rootLayout)
    }

    private fun initSpeechRecognizer() {
        mainHandler.post {
            if (::speechRecognizer.isInitialized) {
                speechRecognizer.destroy()
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (isContinuousModeActive && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                        mainHandler.postDelayed({
                            if (isContinuousModeActive) startListening()
                        }, 500)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val spokenText = matches[0]
                        recognizedTextView.text = spokenText
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
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
        mainHandler.post {
            if (::speechRecognizer.isInitialized) {
                speechRecognizer.stopListening()
            }
        }
        finish()
    }

    private fun toggleFlashlight(enable: Boolean): Boolean {
        return try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
            true
        } catch (e: Exception) {
            false
        }
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor = contentResolver.query(
            uri, projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$contactName%"), null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIdx != -1 && numIdx != -1) {
                    return Pair(it.getString(nameIdx), it.getString(numIdx))
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

        if (cleanQuery in listOf("stop", "exit", "goodbye", "bye", "cancel", "dismiss", "close")) {
            dismissOverlay()
            return
        }

        when {
            // FLASHLIGHT CONTROLS
            cleanQuery.contains("flashlight on") || cleanQuery.contains("turn on the torch") || cleanQuery.contains("torch on") || cleanQuery.contains("turn on flashlight") -> {
                val success = toggleFlashlight(true)
                val reply = if (success) "Flashlight turned on, sir." else "Unable to activate flashlight, sir."
                responseTextView.text = reply
                speakAndListen(reply)
            }

            cleanQuery.contains("flashlight off") || cleanQuery.contains("turn off the torch") || cleanQuery.contains("torch off") || cleanQuery.contains("turn off flashlight") -> {
                val success = toggleFlashlight(false)
                val reply = if (success) "Flashlight turned off, sir." else "Unable to deactivate flashlight, sir."
                responseTextView.text = reply
                speakAndListen(reply)
            }

            // UNLOCK COMMAND
            cleanQuery.contains("unlock") -> {
                val service = JarvisAccessibilityService.instance
                if (service != null) {
                    val reply = "Unlocking your device now, sir."
                    responseTextView.text = reply
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
                    val reply = "Please enable Jarvis in Accessibility settings first, sir."
                    responseTextView.text = reply
                    speakAndListen(reply)
                }
            }

            // WHATSAPP AUTOMATION
            cleanQuery.startsWith("whatsapp ") || cleanQuery.startsWith("msg ") || cleanQuery.startsWith("mssg ") || cleanQuery.startsWith("message ") -> {
                var stripped = cleanQuery
                    .removePrefix("whatsapp ")
                    .removePrefix("msg ")
                    .removePrefix("mssg ")
                    .removePrefix("message ")
                    .trim()

                if (stripped.endsWith(" on whatsapp")) {
                    stripped = stripped.removeSuffix(" on whatsapp").trim()
                }

                if (stripped.startsWith("to ")) {
                    stripped = stripped.removePrefix("to ").trim()
                }

                val match = findContactAndMessage(stripped)
                if (match != null) {
                    val (contactName, rawNumber, textToSend) = match
                    var cleanNum = rawNumber.replace("[^0-9+]".toRegex(), "")
                    if (!cleanNum.startsWith("+") && cleanNum.length == 10) {
                        cleanNum = "+91$cleanNum"
                    }

                    val reply = "Sending message to $contactName on WhatsApp, sir."
                    responseTextView.text = reply
                    speakAndExecute(reply) {
                        try {
                            val encodedMsg = URLEncoder.encode(textToSend, "UTF-8")
                            val waUri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNum&text=$encodedMsg")
                            val waIntent = Intent(Intent.ACTION_VIEW, waUri).apply {
                                setPackage("com.whatsapp")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(waIntent)

                            Handler(Looper.getMainLooper()).postDelayed({
                                JarvisAccessibilityService.instance?.clickWhatsAppSend()
                            }, 1800)

                            dismissOverlay()
                        } catch (e: Exception) {
                            responseTextView.text = "Could not open WhatsApp, sir."
                        }
                    }
                } else {
                    val reply = "I couldn't identify the contact in your address book, sir."
                    responseTextView.text = reply
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
                responseTextView.text = reply
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
                responseTextView.text = reply
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
                val appTarget = cleanQuery.removePrefix("open ").removePrefix("launch ").trim()
                val success = openAppByName(appTarget)
                val reply = if (success) "Opening $appTarget, sir." else "I could not find $appTarget on your device, sir."
                responseTextView.text = reply
                speakAndListen(reply)
            }

            // CALL COMMAND
            cleanQuery.startsWith("call ") || cleanQuery.startsWith("dial ") -> {
                val contactTarget = cleanQuery.removePrefix("call ").removePrefix("dial ").trim()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                    val reply = "Call permission has not been granted, sir."
                    responseTextView.text = reply
                    speakAndListen(reply)
                    return
                }

                if (contactTarget.replace("[\\s-]".toRegex(), "").all { it.isDigit() }) {
                    isContinuousModeActive = false
                    val reply = "Calling $contactTarget now, sir."
                    responseTextView.text = reply
                    speakAndExecute(reply) { makePhoneCall(contactTarget) }
                } else {
                    val contactMatch = getPhoneNumberForName(contactTarget)
                    if (contactMatch != null) {
                        isContinuousModeActive = false
                        val (name, number) = contactMatch
                        val reply = "Calling $name now, sir."
                        responseTextView.text = reply
                        speakAndExecute(reply) { makePhoneCall(number) }
                    } else {
                        val reply = "I could not find $contactTarget in your contacts, sir."
                        responseTextView.text = reply
                        speakAndListen(reply)
                    }
                }
            }

            // GROQ AI FALLBACK
            else -> {
                responseTextView.text = "Thinking..."
                CoroutineScope(Dispatchers.IO).launch {
                    val answer = callGroqApi(query)
                    withContext(Dispatchers.Main) {
                        responseTextView.text = answer
                        speakAndListen(answer)
                    }
                }
            }
        }
    }

    private fun findContactAndMessage(input: String): Triple<String, String, String>? {
        val delimiters = listOf(" saying ", " that ", " msg ", " message ")
        for (delimit
