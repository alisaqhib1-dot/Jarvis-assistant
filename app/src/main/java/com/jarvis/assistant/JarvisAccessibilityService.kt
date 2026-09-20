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

                handler.postDelayed({ tap(800f, 1606f) }, 600)  // 9
                handler.postDelayed({ tap(558f, 1836f) }, 900)  // 0
                handler.postDelayed({ tap(265f, 1390f) }, 1200) // 4
                handler.postDelayed({ tap(842f, 1390f) }, 1500) // 6
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
            }
        }, null)
    }

    /**
     * Clicks the first video result in YouTube
     */
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

    /**
     * Locates WhatsApp's native send button by content description and clicks it
     */
    fun clickWhatsAppSend() {
        val root = rootInActiveWindow ?: return
        val sendNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
        if (!sendNodes.isNullOrEmpty()) {
            sendNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        // Fallback: search by content description ("Send")
        val sendByDesc = root.findAccessibilityNodeInfosByText("Send")
        if (!sendByDesc.isNullOrEmpty()) {
            for (node in sendByDesc) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
            }
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
