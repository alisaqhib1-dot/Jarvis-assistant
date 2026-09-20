package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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

        // Fast upward flick (120ms duration) to satisfy realme UI's lock-screen threshold
        val swipePath = Path().apply {
            moveTo(540f, 1750f)
            lineTo(540f, 250f)
        }

        val swipeGesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 120))
            .build()

        dispatchGesture(swipeGesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)

                // Keypad should now be open; tap digits: 9 - 0 - 4 - 6
                handler.postDelayed({ tap(810f, 1750f) }, 600)   // 9
                handler.postDelayed({ tap(540f, 1950f) }, 900)   // 0
                handler.postDelayed({ tap(270f, 1550f) }, 1200)  // 4
                handler.postDelayed({ tap(810f, 1550f) }, 1500)  // 6
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
