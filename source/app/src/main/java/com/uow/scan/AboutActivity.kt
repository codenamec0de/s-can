package com.uow.scan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.uow.scan.util.ScanDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<TextView>(R.id.tvTopBarTitle).setText(R.string.about_v4_title)
        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        val buildTimestamp = runCatching {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrDefault(System.currentTimeMillis())
        val buildDate = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date(buildTimestamp))
        findViewById<TextView>(R.id.tvAboutBuildMeta).text =
            getString(R.string.about_v4_build_format, BuildConfig.VERSION_NAME, buildDate)

        findViewById<TextView>(R.id.tvAboutVersion).text = BuildConfig.VERSION_NAME
        findViewById<TextView>(R.id.tvAboutTrackerDb).text =
            "${getString(R.string.about_v4_row_tracker_db_value)} · $buildDate"

        findViewById<LinearLayout>(R.id.btnLicenses).setOnClickListener { showLicensesDialog() }
        findViewById<LinearLayout>(R.id.btnAcks).setOnClickListener { showAcknowledgementsDialog() }
        findViewById<FrameLayout>(R.id.btnFeedback).setOnClickListener { sendFeedback() }
        findViewById<TextView>(R.id.btnWebsite).setOnClickListener { openWebsite() }
    }

    private fun showLicensesDialog() {
        val licenses = listOf(
            "Firebase (Apache 2.0)",
            "Google Play Services Auth (Apache 2.0)",
            "AndroidX (Apache 2.0)",
            "Material Components (Apache 2.0)",
            "Retrofit + OkHttp (Apache 2.0)",
            "Kotlin Coroutines (Apache 2.0)",
            "Room (Apache 2.0)",
            "Exodus tracker DB (AGPL 3.0)",
        ).joinToString("\n• ", prefix = "• ")

        ScanDialog.notice(
            context = this,
            title = getString(R.string.about_v4_row_licenses),
            message = licenses,
        )
    }

    private fun showAcknowledgementsDialog() {
        ScanDialog.notice(
            context = this,
            title = getString(R.string.about_v4_row_acks),
            message = "Built as a graduation project at the University of Wollongong.\n\n" +
                "Special thanks to the Exodus Privacy team for the open tracker database, " +
                "the Have I Been Pwned project for the breach API, and the OWASP Mobile " +
                "Security Testing Guide for the audit baselines this app is benchmarked against.",
        )
    }

    private fun sendFeedback() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:feedback@scan.app")
            putExtra(Intent.EXTRA_SUBJECT, "S'CAN feedback (v${BuildConfig.VERSION_NAME})")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No email app available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWebsite() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://scan.app")))
        } catch (e: Exception) {
            Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
