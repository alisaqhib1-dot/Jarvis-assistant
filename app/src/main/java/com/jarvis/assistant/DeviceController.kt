package com.jarvis.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.AudioManager
import android.media.ImageReader
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class DeviceController(private val context: Context) {

    companion object {
        private const val TAG = "DeviceController"
        private const val CHANNEL_ID = "zuraiz_security_channel"
        private const val NOTIFICATION_ID = 9046
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    var isAwaitingAuthChallenge: Boolean = false

    init {
        createNotificationChannel()
    }

    /**
     * Entry point for lock-screen unlock requests.
     */
    fun handleUnlockRequest(speakCallback: (String) -> Unit) {
        isAwaitingAuthChallenge = true
        speakCallback("Identify.")
    }

    /**
     * Evaluates the vocal challenge response.
     */
    fun processAuthResponse(spokenText: String, speakCallback: (String) -> Unit) {
        val cleanInput = spokenText.trim().lowercase()

        if (cleanInput.contains("stand down it's me") || cleanInput.contains("stand down its me")) {
            isAwaitingAuthChallenge = false
            speakCallback("Access granted. Standing down, Sir YUNO.")

            mainHandler.postDelayed({
                JarvisAccessibilityService.instance?.performAutoUnlock()
            }, 600)
        } else {
            isAwaitingAuthChallenge = false
            speakCallback("Access denied. Intruder protocol engaged.")
            captureIntruderSilent { capturedPhotoPath ->
                notifyIntruderBreach(capturedPhotoPath)
            }
        }
    }

    /**
     * Offline Command Parser: Executes local commands without internet latency.
     */
    fun executeOfflineCommand(rawCommand: String, speakCallback: (String) -> Unit): Boolean {
        val cmd = rawCommand.lowercase().trim()

        return when {
            // Hardware: Wi-Fi
            cmd.contains("wifi on") || cmd.contains("turn on wifi") -> {
                setWifiEnabled(true)
                speakCallback("Wi-Fi activated, Boss.")
                true
            }
            cmd.contains("wifi off") || cmd.contains("turn off wifi") -> {
                setWifiEnabled(false)
                speakCallback("Wi-Fi disabled, Boss.")
                true
            }

            // Hardware: Bluetooth
            cmd.contains("bluetooth on") || cmd.contains("turn on bluetooth") -> {
                setBluetoothEnabled(true)
                speakCallback("Bluetooth enabled, Sir YUNO.")
                true
            }
            cmd.contains("bluetooth off") || cmd.contains("turn off bluetooth") -> {
                setBluetoothEnabled(false)
                speakCallback("Bluetooth turned off, Sir YUNO.")
                true
            }

            // Hardware: Volume Controls
            cmd.contains("volume max") || cmd.contains("max volume") -> {
                adjustVolume(AudioManager.ADJUST_SAME, setMax = true)
                speakCallback("Media volume maximized.")
                true
            }
            cmd.contains("volume up") -> {
                adjustVolume(AudioManager.ADJUST_RAISE)
                speakCallback("Volume raised.")
                true
            }
            cmd.contains("volume down") -> {
                adjustVolume(AudioManager.ADJUST_LOWER)
                speakCallback("Volume lowered.")
                true
            }
            cmd.contains("mute") -> {
                adjustVolume(AudioManager.ADJUST_MUTE)
                speakCallback("Audio muted.")
                true
            }

            // Media Playback Controls
            cmd.contains("play music") || cmd.contains("resume music") -> {
                dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
                speakCallback("Playing media.")
                true
            }
            cmd.contains("pause music") || cmd.contains("pause song") -> {
                dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
                speakCallback("Media paused.")
                true
            }
            cmd.contains("next song") || cmd.contains("skip song") -> {
                dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
                speakCallback("Skipping to next track.")
                true
            }

            // Automation: WhatsApp Direct Dispatch
            cmd.startsWith("send whatsapp to") -> {
                handleWhatsAppVoiceTrigger(cmd, speakCallback)
                true
            }

            // Automation: Instagram DM Dispatch
            cmd.startsWith("send instagram to") -> {
                handleInstagramVoiceTrigger(cmd, speakCallback)
                true
            }

            else -> false
        }
    }

    private fun setWifiEnabled(enable: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifiManager?.isWifiEnabled = enable
        } else {
            val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(panelIntent)
        }
    }

    private fun setBluetoothEnabled(enable: Boolean) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        try {
            if (enable && !adapter.isEnabled) {
                @Suppress("DEPRECATION")
                adapter.enable()
            } else if (!enable && adapter.isEnabled) {
                @Suppress("DEPRECATION")
                adapter.disable()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission missing: ${e.message}")
        }
    }

    private fun adjustVolume(direction: Int, setMax: Boolean = false) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (setMax) {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
        } else {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun dispatchMediaKeyEvent(keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(eventDown)
        audioManager.dispatchMediaKeyEvent(eventUp)
    }

    private fun handleWhatsAppVoiceTrigger(cmd: String, speakCallback: (String) -> Unit) {
        val delimiter = " that "
        if (cmd.contains(delimiter)) {
            val parts = cmd.split(delimiter)
            val message = parts[1].trim()

            JarvisAccessibilityService.instance?.pendingWhatsAppMessage = message
            speakCallback("Opening WhatsApp and sending message, Boss.")

            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }
        } else {
            speakCallback("Please state the message using: send whatsapp to [name] that [message].")
        }
    }

    private fun handleInstagramVoiceTrigger(cmd: String, speakCallback: (String) -> Unit) {
        val delimiter = " that "
        if (cmd.contains(delimiter)) {
            val parts = cmd.split(delimiter)
            val message = parts[1].trim()

            JarvisAccessibilityService.instance?.pendingInstagramMessage = message
            speakCallback("Routing to Instagram, Boss.")

            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.instagram.android")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }
        } else {
            speakCallback("Please state: send instagram to [name] that [message].")
        }
    }

    private fun captureIntruderSilent(onPhotoCaptured: (String) -> Unit) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return

        try {
            var frontCameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = id
                    break
                }
            }

            if (frontCameraId == null) {
                Log.e(TAG, "Front camera hardware not detected.")
                return
            }

            val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            val intruderFile = File(context.filesDir, "intruder_${System.currentTimeMillis()}.jpg")

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                try {
                    FileOutputStream(intruderFile).use { fos ->
                        fos.write(bytes)
                    }
                    mainHandler.post {
                        onPhotoCaptured(intruderFile.absolutePath)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing intruder photo bytes: ${e.message}")
                }
            }, mainHandler)

            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                        }

                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            result: TotalCaptureResult
                                        ) {
                                            super.onCaptureCompleted(session, request, result)
                                            camera.close()
                                        }
                                    }, mainHandler)
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    camera.close()
                                }
                            },
                            mainHandler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera capture request exception: ${e.message}")
                        camera.close()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                }
            }, mainHandler)

        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission missing or revoked: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during silent intruder capture: ${e.message}")
        }
    }

    /**
     * Builds and posts an alert notification displaying the intruder photo.
     * Attaches a PendingIntent with FileProvider URI to open the photo in full screen on tap.
     */
    private fun notifyIntruderBreach(photoPath: String) {
        val imageFile = File(photoPath)
        val bitmap = BitmapFactory.decodeFile(photoPath)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // Build a secure URI using Android's FileProvider
        val photoUri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider error: ${e.message}")
            Uri.fromFile(imageFile)
        }

        // Tap action: Launch the photo in the system viewer
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(photoUri, "image/jpeg")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("SECURITY ALERT: Breach Attempt")
            .setContentText("Unauthorized voice attempt detected. Tap to inspect intruder.")
            .setLargeIcon(bitmap)
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).bigLargeIcon(null as? android.graphics.Bitmap))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zuraiz Intruder Security",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Intruder security photo capture alerts"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
