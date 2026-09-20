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

        // Start higher up (y: 1600) to clear realme's gesture nav pill, dragging straight up to y: 350
        val swipePath = Path().apply {
            moveTo(540f, 1600f)
            lineTo(540f, 350f)
        }

        val swipeGesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 100, 350))
            .build()

        dispatchGesture(swipeGesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)

                // Give the keypad 800ms to open, then tap: 9 - 0 - 4 - 6
                handler.postDelayed({ tap(810f, 1750f) }, 800)
                handler.postDelayed({ tap(540f, 1950f) }, 1100)
                handler.postDelayed({ tap(270f, 1550f) }, 1400)
                handler.postDelayed({ tap(810f, 1550f) }, 1700)
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
            .addStroke(GestureDescription.StrokeDescription(tapPath, 0, 100))
            .build()
        dispatchGesture(tapGesture, null, null)
    }
}
