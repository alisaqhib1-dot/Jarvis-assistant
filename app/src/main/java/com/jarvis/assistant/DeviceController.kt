package com.jarvis.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
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
     * Sets the challenge state and instructs ZURAIZ to demand identification.
     */
    fun handleUnlockRequest(speakCallback: (String) -> Unit) {
        isAwaitingAuthChallenge = true
        speakCallback("Identify.")
    }

    /**
     * Evaluates the vocal challenge response.
     * Grants access for 'Stand down, it's me', or initiates intruder capture protocol.
     */
    fun processAuthResponse(spokenText: String, speakCallback: (String) -> Unit) {
        val cleanInput = spokenText.trim().lowercase()

        if (cleanInput.contains("stand down it's me") || cleanInput.contains("stand down its me")) {
            isAwaitingAuthChallenge = false
            speakCallback("Access granted. Standing down, Sir YUNO.")

            // Execute Accessibility auto-swipe and PIN taps (9046)
            mainHandler.postDelayed({
                JarvisAccessibilityService.instance?.performAutoUnlock()
            }, 600)
        } else {
            // Breach detected: unauthorized voice input
            isAwaitingAuthChallenge = false
            speakCallback("Access denied. Intruder protocol engaged.")
            captureIntruderSilent { capturedPhotoPath ->
                notifyIntruderBreach(capturedPhotoPath)
            }
        }
    }

    /**
     * Captures a silent picture using the front camera via Camera2 without UI or shutter sound.
     */
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
                    Log.d(TAG, "Intruder snapshot saved at: ${intruderFile.absolutePath}")
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
     */
    private fun notifyIntruderBreach(photoPath: String) {
        val bitmap = BitmapFactory.decodeFile(photoPath)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("SECURITY ALERT: Breach Attempt")
            .setContentText("Unauthorized voice attempt detected. Intruder captured.")
            .setLargeIcon(bitmap)
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).bigLargeIcon(null as? android.graphics.Bitmap))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
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
