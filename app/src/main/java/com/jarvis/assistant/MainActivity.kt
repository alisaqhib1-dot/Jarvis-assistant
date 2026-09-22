package com.jarvis.assistant

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var deviceController: DeviceController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Dynamic programmatic layout to guarantee 0 resource ID errors
        val frameLayout = android.widget.FrameLayout(this)
        setContentView(frameLayout)

        deviceController = DeviceController(this)

        if (!deviceController.canDrawOverlays()) {
            Toast.makeText(this, "Grant overlay permission to ZURAIZ", Toast.LENGTH_LONG).show()
            deviceController.requestOverlayPermission()
        } else {
            deviceController.showHud("ZURAIZ ACTIVE")
        }
    }
}
