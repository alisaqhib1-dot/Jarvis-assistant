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
     * Wakes the display with full hardware illumination, swipes up, and enters 9046.
     */
    fun performAutoUnlock(onComplete: (() -> Unit)? = null) {
        wakeDeviceScreen()

        // Wait 650ms for display backlight and touch digitizer to power on
        mainHandler.postDelayed({
            dispatchSwipeUp {
                // Wait 750ms for PIN keypad transition to settle
                mainHandler.postDelayed({
                    dispatchPinSequence(0, onComplete)
                }, 750)
            }
        }, 650)
    }

    /**
     * Forces the physical display to turn on from sleep.
     */
    private fun wakeDeviceScreen() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
            "Zuraiz:ScreenWakeForce"
        )
        wakeLock.acquire(5000)
    }

    /**
     * Executes a vertical swipe starting at (540, 1600) up to 350f over 320ms.
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
                Log.e(TAG, "Swipe up cancelled by system.")
            }
        }, null)
    }

    /**
     * Sequentially taps the calibrated 9-0-4-6 digits.
     */
    private fun dispatchPinSequence(index: Int, onComplete: (() -> Unit)?) {
        if (index >= pinCoordinates.size) {
            Log.d(TAG, "PIN sequence finished.")
            onComplete?.invoke()
            return
        }

        val targetCoord = pinCoordinates[index]
        val tapPath = Path().apply {
            moveTo(targetCoord.first, targetCoord.second)
        }

        val tapStroke = GestureDescription.StrokeDescription(tapPath, 0, 70)
        val tapGesture = GestureDescription.Builder().addStroke(tapStroke).build()

        dispatchGesture(tapGesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                mainHandler.postDelayed({
                    dispatchPinSequence(index + 1, onComplete)
                }, 160)
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
