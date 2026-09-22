package com.jarvis.assistant

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var deviceController: DeviceController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val frameLayout = FrameLayout(this)
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
