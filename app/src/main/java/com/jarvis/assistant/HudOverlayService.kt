package com.jarvis.assistant

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class HudOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayLayout: LinearLayout? = null
    private var subtitleText: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        // 1. Root Container
        overlayLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        // 2. Glowing Core Visualizer (Pure Code - No XML needed)
        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(90), dp(90))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF"))
            }
        }
        overlayLayout?.addView(progressBar)

        // 3. Subtitle Pill Background
        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#CC050B14"))
            setStroke(dp(1), Color.parseColor("#00E5FF"))
        }

        // 4. Subtitle Text
        subtitleText = TextView(this).apply {
            text = "ZURAIZ ONLINE"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = pillBg
            setPadding(dp(14), dp(6), dp(14), dp(6))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
            layoutParams = params
        }
        overlayLayout?.addView(subtitleText)

        // 5. System Window Overlay Parameters
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(40)
        }

        windowManager?.addView(overlayLayout, windowParams)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newText = intent?.getStringExtra("SUBTITLE_TEXT")
        if (!newText.isNullOrEmpty()) {
            subtitleText?.text = newText
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (overlayLayout != null) {
            windowManager?.removeView(overlayLayout)
        }
    }
}
