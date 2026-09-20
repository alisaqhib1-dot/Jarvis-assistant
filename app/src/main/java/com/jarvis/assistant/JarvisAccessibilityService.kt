package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        // Auto-send WhatsApp message when window opens
        if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                clickWhatsAppSend(8)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun unlockDevice() {
        val handler = Handler(Looper.getMainLooper())

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
                handler.postDelayed({ tap(800f, 1650f) }, 600)
                handler.postDelayed({ tap(550f, 1350f) }, 900)
                handler.postDelayed({ tap(285f, 1350f) }, 1200)
                handler.postDelayed({ tap(842f, 1350f) }, 1500)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
            }
        }, null)
    }

    fun clickFirstVisibleResult() {
        val root = rootInActiveWindow ?: return
        val clickableNode = findFirstClickableItem(root)
        clickableNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findFirstClickableItem(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (node.isClickable && bounds.top > 320 && bounds.height() > 120) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstClickableItem(child)
            if (found != null) return found
        }
        return null
    }

    fun clickWhatsAppSend(retries: Int = 8) {
        val root = rootInActiveWindow ?: return

        // 1. Try finding by resource ID
        val sendById = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")?.firstOrNull()
            ?: root.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/send")?.firstOrNull()

        // 2. Try finding by text or description
        val sendByDesc = root.findAccessibilityNodeInfosByText("Send")?.firstOrNull()
            ?: root.findAccessibilityNodeInfosByText("भेजें")?.firstOrNull()

        val targetBtn = sendById ?: sendByDesc

        if (targetBtn != null && targetBtn.isEnabled) {
            targetBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else if (retries > 0) {
            // Wait 250ms and search again
            Handler(Looper.getMainLooper()).postDelayed({
                clickWhatsAppSend(retries - 1)
            }, 250)
        }
    }

    fun tap(x: Float, y: Float) {
        val tapPath = Path().apply {
            moveTo(x, y)
        }

        val tapGesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tapPath, 0, 80))
            .build()

        dispatchGesture(tapGesture, null, null)
    }
}
