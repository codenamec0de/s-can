package com.uow.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.uow.scan.util.PreferencesManager

class SmsOnboardingActivity : AppCompatActivity() {

    private lateinit var btnClose: FrameLayout
    private lateinit var btnEnable: FrameLayout
    private lateinit var btnSkip: TextView
    private lateinit var bulletsContainer: LinearLayout

    private val smsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Even if the OS permission is denied, the user wanted SMS detection enabled.
            // We persist the toggle and acknowledge the disclosure either way; the
            // permissions screen will surface the missing permission separately.
            PreferencesManager.setSmsScamDetectionEnabled(this, true)
            PreferencesManager.setSmsDisclosureAccepted(this, true)
            startActivity(Intent(this, SmsScamActivity::class.java))
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms_onboarding)
        bindViews()
        renderBullets()
        setupListeners()
    }

    private fun bindViews() {
        btnClose = findViewById(R.id.btnClose)
        btnEnable = findViewById(R.id.btnEnable)
        btnSkip = findViewById(R.id.btnSkip)
        bulletsContainer = findViewById(R.id.bulletsContainer)
    }

    private fun renderBullets() {
        val texts = listOf(
            R.string.sms_onboarding_bullet_1,
            R.string.sms_onboarding_bullet_2,
            R.string.sms_onboarding_bullet_3,
            R.string.sms_onboarding_bullet_4,
        )
        bulletsContainer.removeAllViews()
        for ((i, res) in texts.withIndex()) {
            val v = LayoutInflater.from(this)
                .inflate(R.layout.item_v4_check_bullet, bulletsContainer, false)
            v.findViewById<TextView>(R.id.bulletText).setText(res)
            if (i > 0) {
                (v.layoutParams as LinearLayout.LayoutParams).topMargin =
                    (10 * resources.displayMetrics.density).toInt()
            }
            bulletsContainer.addView(v)
        }
    }

    private fun setupListeners() {
        btnClose.setOnClickListener { finish() }
        btnSkip.setOnClickListener { finish() }
        btnEnable.setOnClickListener { requestSmsPermission() }
    }

    private fun requestSmsPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            PreferencesManager.setSmsScamDetectionEnabled(this, true)
            PreferencesManager.setSmsDisclosureAccepted(this, true)
            startActivity(Intent(this, SmsScamActivity::class.java))
            finish()
        } else {
            smsLauncher.launch(Manifest.permission.RECEIVE_SMS)
        }
    }
}
