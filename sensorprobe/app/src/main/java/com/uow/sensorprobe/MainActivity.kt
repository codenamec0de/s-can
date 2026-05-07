package com.uow.sensorprobe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Three toggles: Camera, Microphone, Location. Each toggle requests its
 * runtime permission if needed, then sends the matching START_/STOP_ action
 * to [BackgroundSensorService]. Press Home after toggling on — the service
 * keeps the sensor active in the background, which is the test condition
 * S'CAN must detect.
 */
class MainActivity : AppCompatActivity() {

    private var cameraOn = false
    private var micOn = false
    private var locationOn = false

    private lateinit var btnCamera: Button
    private lateinit var btnMic: Button
    private lateinit var btnLocation: Button
    private lateinit var btnStopAll: Button
    private lateinit var tvStatus: TextView

    private val requestPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // After the user answers, the toggle that triggered the request
            // re-runs with the current permission state.
            val grantedAll = results.values.all { it }
            if (!grantedAll) {
                Toast.makeText(
                    this,
                    "Permission denied; can't run that holder.",
                    Toast.LENGTH_SHORT
                ).show()
                return@registerForActivityResult
            }
            // Replay whichever toggle wanted the perms — cheapest is to re-evaluate
            // current state and start any holder whose perms are now satisfied.
            replayDesiredState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnCamera = findViewById(R.id.btnCamera)
        btnMic = findViewById(R.id.btnMic)
        btnLocation = findViewById(R.id.btnLocation)
        btnStopAll = findViewById(R.id.btnStopAll)
        tvStatus = findViewById(R.id.tvStatus)

        btnCamera.setOnClickListener {
            cameraOn = !cameraOn
            apply()
        }
        btnMic.setOnClickListener {
            micOn = !micOn
            apply()
        }
        btnLocation.setOnClickListener {
            locationOn = !locationOn
            apply()
        }
        btnStopAll.setOnClickListener {
            cameraOn = false; micOn = false; locationOn = false
            sendAction(BackgroundSensorService.ACTION_STOP_ALL)
            renderStatus()
        }

        renderStatus()
    }

    private fun apply() {
        // Collect the runtime perms we need for the desired state.
        val needed = mutableListOf<String>()
        if (cameraOn && !has(Manifest.permission.CAMERA))
            needed += Manifest.permission.CAMERA
        if (micOn && !has(Manifest.permission.RECORD_AUDIO))
            needed += Manifest.permission.RECORD_AUDIO
        if (locationOn && !has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !has(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            requestPerms.launch(needed.toTypedArray())
            return
        }
        replayDesiredState()
    }

    private fun replayDesiredState() {
        sendAction(
            if (cameraOn) BackgroundSensorService.ACTION_START_CAMERA
            else BackgroundSensorService.ACTION_STOP_CAMERA
        )
        sendAction(
            if (micOn) BackgroundSensorService.ACTION_START_MIC
            else BackgroundSensorService.ACTION_STOP_MIC
        )
        sendAction(
            if (locationOn) BackgroundSensorService.ACTION_START_LOCATION
            else BackgroundSensorService.ACTION_STOP_LOCATION
        )
        renderStatus()
    }

    private fun sendAction(action: String) {
        val intent = Intent(this, BackgroundSensorService::class.java).setAction(action)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun has(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun renderStatus() {
        btnCamera.text = if (cameraOn) "Camera: ON" else "Camera: OFF"
        btnMic.text = if (micOn) "Microphone: ON" else "Microphone: OFF"
        btnLocation.text = if (locationOn) "Location: ON" else "Location: OFF"
        val parts = mutableListOf<String>()
        if (cameraOn) parts += "camera"
        if (micOn) parts += "microphone"
        if (locationOn) parts += "location"
        tvStatus.text = if (parts.isEmpty())
            "Idle. Toggle a sensor on, then press Home — the service keeps it running in the background."
        else
            "Active: ${parts.joinToString(" + ")}. Press Home to background — the sensor stays active."
    }
}
