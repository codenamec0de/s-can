package com.uow.sensorprobe

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test harness service. On START_CAMERA / START_MIC / START_LOCATION it grabs
 * the corresponding sensor and holds it open in the background. STOP_* (or
 * STOP_ALL) releases. Used to verify that S'CAN's OpAccessTracker and
 * PrivacyNotificationListener detect the access and attribute it to this
 * package (com.uow.sensorprobe).
 *
 * Implementation notes:
 *  • Foreground service is required for camera/mic access from background on
 *    Android 9+. The service runs with type camera|microphone|location.
 *  • Camera is held open via Camera2 + an ImageReader surface and a
 *    repeating preview request — that's what flips the system's
 *    "camera in use" indicator.
 *  • Mic is held by AudioRecord with a small reader thread draining the
 *    buffer (otherwise some OEMs short-circuit the active state).
 */
class BackgroundSensorService : Service() {

    companion object {
        private const val TAG = "SensorProbe"
        private const val CHANNEL_ID = "sensorprobe_channel"
        private const val NOTIFICATION_ID = 7001

        const val ACTION_START_CAMERA = "com.uow.sensorprobe.START_CAMERA"
        const val ACTION_STOP_CAMERA = "com.uow.sensorprobe.STOP_CAMERA"
        const val ACTION_START_MIC = "com.uow.sensorprobe.START_MIC"
        const val ACTION_STOP_MIC = "com.uow.sensorprobe.STOP_MIC"
        const val ACTION_START_LOCATION = "com.uow.sensorprobe.START_LOCATION"
        const val ACTION_STOP_LOCATION = "com.uow.sensorprobe.STOP_LOCATION"
        const val ACTION_STOP_ALL = "com.uow.sensorprobe.STOP_ALL"
    }

    // ─── Camera state ────────────────────────────────────────────────────────
    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private val cameraOpen = AtomicBoolean(false)

    // ─── Mic state ───────────────────────────────────────────────────────────
    private var audioRecord: AudioRecord? = null
    private var micReaderThread: Thread? = null
    private val micRunning = AtomicBoolean(false)

    // ─── Location state ──────────────────────────────────────────────────────
    private var locationListener: LocationListener? = null
    private val locationActive = AtomicBoolean(false)

    // ─── Simulated network exfiltration ──────────────────────────────────────
    // While any sensor holder is active, hit a public endpoint periodically.
    // This makes S'CAN's NetworkStats lookup show non-zero bytes for our UID,
    // exercising the BackgroundUsageMonitor data-transfer alert path.
    private var leakThread: Thread? = null
    private val leakRunning = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Promote to foreground immediately with a non-zero type. We OR in
        // every type the manifest declares; concrete types are gated by
        // runtime permission checks per-action.
        val type = computeFgsType()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification("Idle"), type)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Idle"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAMERA -> startCamera()
            ACTION_STOP_CAMERA -> stopCamera()
            ACTION_START_MIC -> startMic()
            ACTION_STOP_MIC -> stopMic()
            ACTION_START_LOCATION -> startLocation()
            ACTION_STOP_LOCATION -> stopLocation()
            ACTION_STOP_ALL -> {
                stopCamera(); stopMic(); stopLocation()
                stopNetworkLeak()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Sync the network-leak loop with whether any sensor is held.
        if (cameraOpen.get() || micRunning.get() || locationActive.get()) {
            startNetworkLeak()
        } else {
            stopNetworkLeak()
        }
        updateNotification()
        // If nothing is held any more, terminate the service so it doesn't
        // sit around as a zombie foreground notification.
        if (!cameraOpen.get() && !micRunning.get() && !locationActive.get()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCamera(); stopMic(); stopLocation()
        stopNetworkLeak()
        super.onDestroy()
    }

    // ─── Simulated exfiltration ──────────────────────────────────────────────

    private fun startNetworkLeak() {
        if (leakRunning.getAndSet(true)) return
        leakThread = Thread {
            // Hit a public endpoint every 8s, downloading ~16KB each time.
            // 4 cycles ≈ 64KB, comfortably above S'CAN's 50KB threshold.
            // Endpoint chosen for stability + small response.
            val url = java.net.URL("https://httpbin.org/bytes/16384")
            while (leakRunning.get()) {
                try {
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.requestMethod = "GET"
                    conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                    Log.i(TAG, "leak GET ok")
                } catch (e: Exception) {
                    Log.w(TAG, "leak GET failed: ${e.message}")
                }
                // Sleep responsively so stop() doesn't have to wait the full
                // interval before exiting.
                var slept = 0
                while (leakRunning.get() && slept < 8_000) {
                    Thread.sleep(200); slept += 200
                }
            }
        }.also { it.isDaemon = true; it.name = "probe-leak"; it.start() }
        Log.i(TAG, "Network leak thread started")
    }

    private fun stopNetworkLeak() {
        if (!leakRunning.getAndSet(false)) return
        leakThread = null
        Log.i(TAG, "Network leak thread stopping")
    }

    // ─── Camera ──────────────────────────────────────────────────────────────

    private fun startCamera() {
        if (cameraOpen.getAndSet(true)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Camera permission not granted; aborting camera start")
            cameraOpen.set(false)
            return
        }
        cameraThread = HandlerThread("probe-cam").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cm.cameraIdList.firstOrNull() ?: run {
            Log.w(TAG, "No camera ids"); return
        }
        // 320×240 YUV — minimum we need to keep the camera active.
        imageReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.close()
            }, cameraHandler)
        }
        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    val target = imageReader!!.surface
                    val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(target)
                    }
                    device.createCaptureSession(
                        listOf(target),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                cameraSession = session
                                session.setRepeatingRequest(builder.build(), null, cameraHandler)
                                Log.i(TAG, "Camera repeating preview started")
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Camera session configuration failed")
                            }
                        },
                        cameraHandler
                    )
                }

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    device.close()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    device.close()
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "openCamera SecurityException", e)
            cameraOpen.set(false)
        }
    }

    private fun stopCamera() {
        if (!cameraOpen.getAndSet(false)) return
        runCatching { cameraSession?.close() }
        cameraSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { imageReader?.close() }
        imageReader = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        Log.i(TAG, "Camera released")
    }

    // ─── Mic ─────────────────────────────────────────────────────────────────

    private fun startMic() {
        if (micRunning.getAndSet(true)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted; aborting mic start")
            micRunning.set(false)
            return
        }
        val sampleRate = 44_100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            .coerceAtLeast(4096)
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate, channelConfig, encoding, bufferSize
            )
            audioRecord?.startRecording()
            micReaderThread = Thread {
                val buf = ByteArray(bufferSize)
                while (micRunning.get()) {
                    val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                    if (n < 0) break
                }
            }.also { it.isDaemon = true; it.start() }
            Log.i(TAG, "Mic recording started (bufferSize=$bufferSize)")
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed", e)
            micRunning.set(false)
        }
    }

    private fun stopMic() {
        if (!micRunning.getAndSet(false)) return
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        micReaderThread = null
        Log.i(TAG, "Mic released")
    }

    // ─── Location ────────────────────────────────────────────────────────────

    private fun startLocation() {
        if (locationActive.getAndSet(true)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted; aborting location start")
            locationActive.set(false)
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { /* drain */ }
        locationListener = listener
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper()
            )
            // Also request from network provider for indoor tests.
            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper()
                )
            }
            Log.i(TAG, "Location updates requested")
        } catch (e: SecurityException) {
            Log.e(TAG, "requestLocationUpdates SecurityException", e)
            locationActive.set(false)
        }
    }

    private fun stopLocation() {
        if (!locationActive.getAndSet(false)) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationListener?.let { runCatching { lm.removeUpdates(it) } }
        locationListener = null
        Log.i(TAG, "Location released")
    }

    // ─── Notification / FGS plumbing ─────────────────────────────────────────

    private fun computeFgsType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var t = 0
        if (cameraOpen.get()) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (micRunning.get()) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (locationActive.get()) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        // Default to a benign type so the very first startForeground call
        // with no holders running doesn't fail. We re-promote with the right
        // type the moment a sensor is actually grabbed.
        if (t == 0) t = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return t
    }

    private fun statusLine(): String {
        val parts = mutableListOf<String>()
        if (cameraOpen.get()) parts += "camera"
        if (micRunning.get()) parts += "mic"
        if (locationActive.get()) parts += "location"
        return if (parts.isEmpty()) "Idle" else parts.joinToString(" + ")
    }

    private fun updateNotification() {
        // Re-call startForeground so the FGS type reflects the holders that are
        // actually active right now. The system enforces that the runtime type
        // is a subset of the manifest declaration AND matches the resource
        // we're currently holding (e.g. CAMERA must be set while we hold the
        // camera, otherwise it kills the access).
        val notif = buildNotification(statusLine())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, computeFgsType())
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun buildNotification(status: String): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SensorProbe")
            .setContentText("Holding: $status")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
        return builder.build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "SensorProbe", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
