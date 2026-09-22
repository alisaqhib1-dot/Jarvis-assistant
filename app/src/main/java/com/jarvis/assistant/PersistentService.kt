package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class PersistentService : Service() {

    private lateinit var deviceController: DeviceController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        deviceController = DeviceController(this)
        startForegroundServiceNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.getStringExtra("VOICE_COMMAND") ?: ""
        
        if (command.isNotEmpty()) {
            processVoiceCommand(command)
        }
        
        return START_STICKY
    }

    private fun processVoiceCommand(command: String) {
        val cleanCommand = command.trim().lowercase()

        when {
            cleanCommand.contains("stand down it's me") || cleanCommand.contains("unlock") -> {
                deviceController.showHud("AUTHENTICATED: SIR YUNO")
                deviceController.wakeAndUnlock()
            }
            cleanCommand.contains("show hud") -> {
                deviceController.showHud("ZURAIZ ONLINE")
            }
            cleanCommand.contains("hide hud") || cleanCommand.contains("dismiss") -> {
                deviceController.hideHud()
            }
            else -> {
                deviceController.showHud("COMMAND: $command")
            }
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "zuraiz_service_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Zuraiz Core Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ZURAIZ Core")
            .setContentText("Monitoring voice commands")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }
}
