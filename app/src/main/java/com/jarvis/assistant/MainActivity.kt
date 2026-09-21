package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 2001
    }

    private lateinit var statusTextView: TextView
    private lateinit var btnToggleService: Button
    private lateinit var btnAccessibilitySettings: Button

    private val requiredPermissions: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return list.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Native programmatic UI
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
        if (hasAllPermissions()) {
            startAssistantService()
        } else {
            requestPermissions(requiredPermissions, PERMISSION_REQUEST_CODE)
        }
    }

    private fun hasAllPermissions(): Boolean {
        for (perm in requiredPermissions) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                startAssistantService()
            } else {
                Toast.makeText(this, "Permissions required for ZURAIZ security and listening.", Toast.LENGTH_LONG).show()
            }
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
