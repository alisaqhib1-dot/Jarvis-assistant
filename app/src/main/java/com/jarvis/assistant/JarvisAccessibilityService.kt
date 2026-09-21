package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Calibrated Keypad Coordinates for PIN: 9 -> 0 -> 4 -> 6
    private val pinCoordinates = listOf(
        Pair(799.5f, 1606.1f), // Key 9
        Pair(557.7f, 1836.5f), // Key 0
        Pair(264.7f, 1391.9f), // Key 4
        Pair(842.0f, 1325.9f)  // Key 6
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Reserved for in-app node inspections (WhatsApp/Instagram automation)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Wakes the screen, performs an upward drag to open the keypad,
     * and sequentially taps the PIN coordinates.
     */
    fun performAutoUnlock(onComplete: (() -> Unit)? = null) {
        wakeDeviceScreen()

        // Give the screen display 200ms to power on before swiping
        mainHandler.postDelayed({
            dispatchSwipeUp {
                // Wait 350ms for lockscreen transition and keypad render
                mainHandler.postDelayed({
                    dispatchPinSequence(0, onComplete)
                }, 350)
            }
        }, 200)
    }

    /**
     * Wakes the physical display using PowerManager flags.
     */
    private fun wakeDeviceScreen() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null && !powerManager.isInteractive) {
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "Zuraiz:UnlockWakeLock"
            )
            wakeLock.acquire(3000)
        }
    }

    /**
     * Simulates an upward vertical flick to pull up the PIN entry pad.
     */
    private fun dispatchSwipeUp(onSwipeFinished: () -> Unit) {
        val swipePath = Path().apply {
            moveTo(540f, 1900f) // Start drag from bottom-center
            lineTo(540f, 500f)  // End drag at upper-center
        }

        val swipeStroke = GestureDescription.StrokeDescription(swipePath, 0, 250)
        val gesture = GestureDescription.Builder().addStroke(swipeStroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onSwipeFinished()
            }
        }, null)
    }

    /**
     * Recursively injects tap gestures at each digit coordinate with a delay.
     */
    private fun dispatchPinSequence(index: Int, onComplete: (() -> Unit)?) {
        if (index >= pinCoordinates.size) {
            onComplete?.invoke()
            return
        }

        val targetCoord = pinCoordinates[index]
        val tapPath = Path().apply {
            moveTo(targetCoord.first, targetCoord.second)
        }

        val tapStroke = GestureDescription.StrokeDescription(tapPath, 0, 50)
        val tapGesture = GestureDescription.Builder().addStroke(tapStroke).build()

        dispatchGesture(tapGesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                mainHandler.postDelayed({
                    dispatchPinSequence(index + 1, onComplete)
                }, 120)
            }
        }, null)
    }
}
