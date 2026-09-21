package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessibility"
        var instance: JarvisAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Calibrated Keypad Coordinates for PIN: 9 -> 0 -> 4 -> 6
    private val pinCoordinates = listOf(
        Pair(799.5f, 1606.1f), // Digit 9
        Pair(557.7f, 1836.5f), // Digit 0
        Pair(264.7f, 1391.9f), // Digit 4
        Pair(842.0f, 1325.9f)  // Digit 6
    )

    var pendingWhatsAppMessage: String? = null
    var pendingInstagramMessage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "JarvisAccessibilityService connected and ready.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.source == null) return

        val packageName = event.packageName?.toString() ?: ""

        if (packageName == "com.whatsapp" && pendingWhatsAppMessage != null) {
            handleWhatsAppAutomatedSend(rootInActiveWindow)
        }

        if (packageName == "com.instagram.android" && pendingInstagramMessage != null) {
            handleInstagramAutomatedSend(rootInActiveWindow)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Wakes the display, performs an upward drag to open the keypad,
     * waits for the keypad animation, and enters 9046.
     */
    fun performAutoUnlock(onComplete: (() -> Unit)? = null) {
        wakeDeviceScreen()

        // Wait 400ms for the display to power on and settle
        mainHandler.postDelayed({
            dispatchSwipeUp {
                // Wait 600ms for the keypad transition to fully appear on screen
                mainHandler.postDelayed({
                    dispatchPinSequence(0, onComplete)
                }, 600)
            }
        }, 400)
    }

    /**
     * Reliable screen wake for modern Android displays.
     */
    private fun wakeDeviceScreen() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (!powerManager.isInteractive) {
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "Zuraiz:ScreenWake"
            )
            wakeLock.acquire(4000)
        }
    }

    /**
     * Executes a vertical swipe starting above the bottom navigation area (1600f) up to 350f.
     */
    private fun dispatchSwipeUp(onSwipeFinished: () -> Unit) {
        val swipePath = Path().apply {
            moveTo(540f, 1600f)
            lineTo(540f, 350f)
        }

        val swipeStroke = GestureDescription.StrokeDescription(swipePath, 0, 320)
        val gesture = GestureDescription.Builder().addStroke(swipeStroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Swipe up completed successfully.")
                onSwipeFinished()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.e(TAG, "Swipe up was cancelled by system.")
            }
        }, null)
    }

    /**
     * Sequentially taps the calibrated 9-0-4-6 digits.
     */
    private fun dispatchPinSequence(index: Int, onComplete: (() -> Unit)?) {
        if (index >= pinCoordinates.size) {
            Log.d(TAG, "PIN entry complete.")
            onComplete?.invoke()
            return
        }

        val targetCoord = pinCoordinates[index]
        val tapPath = Path().apply {
            moveTo(targetCoord.first, targetCoord.second)
        }

        // 70ms tap duration ensures the touch listener catches it
        val tapStroke = GestureDescription.StrokeDescription(tapPath, 0, 70)
        val tapGesture = GestureDescription.Builder().addStroke(tapStroke).build()

        dispatchGesture(tapGesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                // 150ms delay between numbers gives the keypad time to register each digit
                mainHandler.postDelayed({
                    dispatchPinSequence(index + 1, onComplete)
                }, 150)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.e(TAG, "Tap cancelled at index $index")
            }
        }, null)
    }

    private fun handleWhatsAppAutomatedSend(rootNode: AccessibilityNodeInfo?) {
        val textToSend = pendingWhatsAppMessage ?: return
        if (rootNode == null) return

        val inputNodes = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")
        if (inputNodes.isNotEmpty()) {
            val inputField = inputNodes[0]
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToSend)
            }
            inputField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            mainHandler.postDelayed({
                val sendNodes = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
                if (sendNodes.isNotEmpty()) {
                    sendNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    pendingWhatsAppMessage = null
                }
            }, 250)
        }
    }

    private fun handleInstagramAutomatedSend(rootNode: AccessibilityNodeInfo?) {
        val textToSend = pendingInstagramMessage ?: return
        if (rootNode == null) return

        val inputNodes = rootNode.findAccessibilityNodeInfosByViewId("com.instagram.android:id/row_thread_composer_edittext")
        if (inputNodes.isNotEmpty()) {
            val inputField = inputNodes[0]
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToSend)
            }
            inputField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            mainHandler.postDelayed({
                val sendNodes = rootNode.findAccessibilityNodeInfosByViewId("com.instagram.android:id/row_thread_composer_button_send")
                if (sendNodes.isNotEmpty()) {
                    sendNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    pendingInstagramMessage = null
                }
            }, 250)
        }
    }
}
