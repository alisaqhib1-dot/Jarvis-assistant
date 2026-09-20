package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun unlockDevice() {
        val handler = Handler(Looper.getMainLooper())

        // 1. Swipe up from bottom of the screen to show PIN keypad
        val swipePath = Path().apply {
            moveTo(540f, 2100f)
            lineTo(540f, 300f)
        }
        val swipeGesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 250))
            .build()

        dispatchGesture(swipeGesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)

                // 2. Wait 700ms for PIN keypad to animate up, then tap: 9 - 0 - 4 - 6
                handler.postDelayed({ tap(810f, 1750f) }, 700)   // Digit 9
                handler.postDelayed({ tap(540f, 1950f) }, 1000)  // Digit 0
                handler.postDelayed({ tap(270f, 1550f) }, 1300)  // Digit 4
                handler.postDelayed({ tap(810f, 1550f) }, 1600)  // Digit 6
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
            }
        }, null)
    }

    private fun tap(x: Float, y: Float) {
        val tapPath = Path().apply {
            moveTo(x, y)
        }
        val tapGesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tapPath, 0, 80))
            .build()
        dispatchGesture(tapGesture, null, null)
    }
}

