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
        Pair(799.5f, 1606.1f), // Key 9
        Pair(557.7f, 1836.5f), // Key 0
        Pair(264.7f, 1391.9f), // Key 4
        Pair(842.0f, 1325.9f)  // Key 6
    )

    // State trackers for auto-typing and auto-sending
    var pendingWhatsAppMessage: String? = null
    var pendingInstagramMessage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.source == null) return

        val packageName = event.packageName?.toString() ?: ""

        // WhatsApp Automation
        if (packageName == "com.whatsapp" && pendingWhatsAppMessage != null) {
            handleWhatsAppAutomatedSend(rootInActiveWindow)
        }

        // Instagram Automation
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

    private fun dispatchSwipeUp(onSwipeFinished: () -> Unit) {
        val swipePath = Path().apply {
            moveTo(540f, 1900f)
            lineTo(540f, 500f)
        }

        val swipeStroke = GestureDescription.StrokeDescription(swipePath, 0, 250)
        val gesture = GestureDescription.Builder().addStroke(swipeStroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onSwipeFinished()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
            }
        }, null)
    }

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

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
            }
        }, null)
    }

    /**
     * Inspects active window tree for WhatsApp input box, pastes message, and clicks Send.
     */
    private fun handleWhatsAppAutomatedSend(rootNode: AccessibilityNodeInfo?) {
        val textToSend = pendingWhatsAppMessage ?: return
        if (rootNode == null) return

        // Search for the message input field
        val inputNodes = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")
        if (inputNodes.isNotEmpty()) {
            val inputField = inputNodes[0]
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToSend)
            }
            inputField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            // Short pause to allow Send button to register text input
            mainHandler.postDelayed({
                val sendNodes = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
                if (sendNodes.isNotEmpty()) {
                    sendNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    pendingWhatsAppMessage = null // Action complete, clear payload
                }
            }, 250)
        }
    }

    /**
     * Injects text into active Instagram direct chat input and triggers the Send button.
     */
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
