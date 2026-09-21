package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
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
        setContentView(R.layout.activity_main)

        deviceController = DeviceController(this)
        tvStatus = findViewById(R.id.tvStatus)
        etCommand = findViewById(R.id.etCommand)
        btnExecute = findViewById(R.id.btnExecute)
        btnVoice = findViewById(R.id.btnVoice)

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
            tvStatus.text = "ACRUX Listening active"
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
        val lower = command.lowercase()
        val hardwareResult = when {
            "flashlight on" in lower || "torch on" in lower -> deviceController.setFlashlight(true)
            "flashlight off" in lower || "torch off" in lower -> deviceController.setFlashlight(false)
            "silent" in lower -> deviceController.setRingerMode("silent")
            "vibrate" in lower -> deviceController.setRingerMode("vibrate")
            "ring" in lower || "normal mode" in lower -> deviceController.setRingerMode("normal")
            "battery" in lower -> deviceController.getBatteryTelemetry()
            "clean cache" in lower || "clear cache" in lower -> deviceController.cleanAppCache()
            else -> null
        }

        if (hardwareResult != null) {
            tvStatus.text = hardwareResult
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val reply = GroqClient.query(command)
            tvStatus.text = reply
        }
    }
}
