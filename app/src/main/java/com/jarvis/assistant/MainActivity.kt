package com.jarvis.assistant

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var deviceController: DeviceController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceController = DeviceController(this)

        // Check and ask for overlay permission immediately on startup
        if (!deviceController.canDrawOverlays()) {
            Toast.makeText(this, "Grant 'Display over other apps' to ZURAIZ", Toast.LENGTH_LONG).show()
            deviceController.requestOverlayPermission()
        }

        // Test buttons if present in your activity_main.xml
        findViewById<Button?>(R.id.btnShowHud)?.setOnClickListener {
            deviceController.showHud("ZURAIZ ACTIVE")
        }

        findViewById<Button?>(R.id.btnHideHud)?.setOnClickListener {
            deviceController.hideHud()
        }
    }
}
