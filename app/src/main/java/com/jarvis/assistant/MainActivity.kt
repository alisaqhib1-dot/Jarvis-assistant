package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etCommand: EditText
    private lateinit var btnExecute: Button
    private lateinit var btnVoice: Button
    private lateinit var deviceController: DeviceController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceController = DeviceController(this)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding(48, 80, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val tvTitle = TextView(this).apply {
            text = "A C R U X"
            textSize = 28f
            setTextColor(Color.parseColor("#00F0FF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        tvStatus = TextView(this).apply {
            text = "SYSTEM ONLINE - BACKGROUND READY"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#151D2A"))
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER
        }

        etCommand = EditText(this).apply {
            hint = "Enter directive or speak..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#151D2A"))
            setPadding(32, 32, 32, 32)
        }

        btnExecute = Button(this).apply {
            text = "EXECUTE DIRECTIVE"
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.WHITE)
        }

        btnVoice = Button(this).apply {
            text = "INITIATE BACKGROUND VOICE"
            setBackgroundColor(Color.parseColor("#00F0FF"))
            setTextColor(Color.BLACK)
        }

        val spacer1 = LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) }
        val spacer2 = LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(1, 24) }
        val spacer3 = LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(1, 24) }

        rootLayout.addView(tvTitle)
        rootLayout.addView(tvStatus)
        rootLayout.addView(spacer1)
        rootLayout.addView(etCommand)
        rootLayout.addView(spacer2)
        rootLayout.addView(btnExecute)
        rootLayout.addView(spacer3)
        rootLayout.addView(btnVoice)

        setContentView(rootLayout)

        checkAndRequestPermissions()

        btnExecute.setOnClickListener {
            val text = etCommand.text.toString().trim()
            if (text.isNotEmpty()) {
                executeDirective(text)
                etCommand.text.clear()
            }
        }

        btnVoice.setOnClickListener {
            startBackgroundService()
            tvStatus.text = "ACRUX Background Service Active"
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        } else {
            startBackgroundService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            startBackgroundService()
        }
    }

    private fun startBackgroundService() {
        val serviceIntent = Intent(this, PersistentService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun executeDirective(command: String) {
        tvStatus.text = "Processing: $command"

        val handled = deviceController.executeDirective(command) { result ->
            tvStatus.text = result
        }

        if (!handled) {
            CoroutineScope(Dispatchers.Main).launch {
                val reply = GroqClient.query(command)
                tvStatus.text = reply
            }
        }
    }
}
