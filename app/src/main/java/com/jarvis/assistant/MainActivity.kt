package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var btnToggleService: Button
    private lateinit var btnAccessibilitySettings: Button

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startAssistantService()
        } else {
            Toast.makeText(this, "Permissions required for ZURAIZ security and listening.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Programmatic lightweight UI to eliminate layout XML resource dependencies
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 100, 50, 50)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        statusTextView = TextView(this).apply {
            text = "ZURAIZ CORE\nStatus: Initializing..."
            textSize = 20f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 50)
        }

        btnToggleService = Button(this).apply {
            text = "Start Core Service"
            setOnClickListener {
                checkPermissionsAndStart()
            }
        }

        btnAccessibilitySettings = Button(this).apply {
            text = "Enable Accessibility"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }

        layout.addView(statusTextView)
        layout.addView(btnToggleService)
        layout.addView(btnAccessibilitySettings)

        setContentView(layout)

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startAssistantService()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun startAssistantService() {
        val serviceIntent = Intent(this, PersistentService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        statusTextView.text = "ZURAIZ CORE\nStatus: Online & Guarding"
        btnToggleService.text = "Service Running"
    }
}
