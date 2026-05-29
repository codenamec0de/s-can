package com.uow.scan

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.MonitoredAppEntity
import com.uow.scan.data.entity.ScanResultEntity
import com.uow.scan.model.AppInfo
import com.uow.scan.model.RiskLevel
import com.uow.scan.util.AppScanner
import com.uow.scan.util.PreferencesManager
import com.uow.scan.util.ScanSnapshotManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var progressCircle: ProgressBar
    private lateinit var tvPercentage: TextView
    private lateinit var tvScanStatus: TextView
    private lateinit var ivStep1Icon: ImageView
    private lateinit var ivStep2Icon: ImageView
    private lateinit var ivStep3Icon: ImageView
    private lateinit var tvStep1: TextView
    private lateinit var tvStep2: TextView
    private lateinit var tvStep3: TextView
    private lateinit var btnViewResults: Button

    private var scannedApps: List<AppInfo> = emptyList()
    private var progressAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        initViews()
        setupListeners()
        startRealScan()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        progressCircle = findViewById(R.id.progressCircle)
        tvPercentage = findViewById(R.id.tvPercentage)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        ivStep1Icon = findViewById(R.id.ivStep1Icon)
        ivStep2Icon = findViewById(R.id.ivStep2Icon)
        ivStep3Icon = findViewById(R.id.ivStep3Icon)
        tvStep1 = findViewById(R.id.tvStep1)
        tvStep2 = findViewById(R.id.tvStep2)
        tvStep3 = findViewById(R.id.tvStep3)
        btnViewResults = findViewById(R.id.btnViewResults)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        btnViewResults.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("SHOW_AUDIT", true)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            finish()
        }
    }

    /**
     * Real scan flow:
     * Step 1 (0-40%): Discover and scan all installed apps in parallel batches
     * Step 2 (40-75%): Analyze permissions and classify risk levels
     * Step 3 (75-100%): Cache results to database
     */
    private fun startRealScan() {
        lifecycleScope.launch {
            try {
            // ---- Step 1: Discover apps ----
            tvScanStatus.text = "Discovering installed apps..."
            animateProgressTo(5)

            val packages = withContext(Dispatchers.IO) {
                AppScanner.getInstalledPackages(this@ScanActivity)
            }

            val totalApps = packages.size
            if (totalApps == 0) {
                animateProgressTo(100)
                scanComplete("No apps found")
                return@launch
            }

            tvScanStatus.text = "Scanning $totalApps apps..."
            animateProgressTo(10)

            // Scan in parallel batches of 8
            val batchSize = 8
            val results = mutableListOf<AppInfo>()
            var scannedCount = 0

            withContext(Dispatchers.IO) {
                packages.chunked(batchSize).forEach { batch ->
                    val batchResults = batch.map { pkg ->
                        async {
                            AppScanner.scanSinglePackage(this@ScanActivity, pkg)
                        }
                    }.awaitAll().filterNotNull()

                    results.addAll(batchResults)
                    scannedCount += batch.size

                    // Update UI with real progress
                    val scanProgress = 10 + (scannedCount * 30 / totalApps) // 10-40%
                    withContext(Dispatchers.Main) {
                        val currentApp = batchResults.lastOrNull()?.appName ?: ""
                        tvScanStatus.text = "Scanning: $currentApp ($scannedCount/$totalApps)"
                        animateProgressTo(scanProgress)
                    }
                }
            }

            // Complete step 1
            completeStep(1)
            tvStep1.text = "Scanned $totalApps apps"

            // ---- Step 2: Analyze permissions & classify ----
            tvScanStatus.text = "Analyzing permissions..."
            animateProgressTo(45)

            // Sort by risk (this is the "analysis" step)
            scannedApps = results.sortedWith(
                compareByDescending<AppInfo> {
                    it.permissions.count { perm -> perm in AppInfo.SENSITIVE_PERMISSIONS }
                }.thenByDescending { it.riskLevel.ordinal }
            )

            // "Real finding" signal — apps observed accessing a sensor in the background.
            // Only these escalate to HIGH; permission capability alone caps at MEDIUM.
            val flagged = withContext(Dispatchers.IO) {
                ScanDatabase.getInstance(this@ScanActivity).permissionAccessDao()
                    .packagesWithBackgroundAccess(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
                    .toSet()
            }
            fun effRisk(app: AppInfo) = AppScanner.effectiveRisk(app.riskLevel, app.packageName in flagged)

            val highCount = scannedApps.count { effRisk(it) == RiskLevel.HIGH }
            val medCount = scannedApps.count { effRisk(it) == RiskLevel.MEDIUM }

            // Show app names during analysis for realism
            for (i in 0 until minOf(scannedApps.size, 12)) {
                val app = scannedApps[i]
                val analysisProgress = 45 + (i * 30 / minOf(scannedApps.size, 12)) // 45-75%
                tvScanStatus.text = "Analyzing: ${app.appName}"
                animateProgressTo(analysisProgress)
                delay(if (app.riskLevel == RiskLevel.HIGH) 200L else 80L) // pause longer on risky apps
            }

            completeStep(2)
            tvStep2.text = "Found $highCount high, $medCount medium risk"
            animateProgressTo(75)

            // ---- Step 3: Cache results to database ----
            tvScanStatus.text = "Saving results..."
            animateProgressTo(80)

            withContext(Dispatchers.IO) {
                val db = ScanDatabase.getInstance(this@ScanActivity)
                val now = System.currentTimeMillis()

                // Snapshot previous scan before overwriting
                ScanSnapshotManager.snapshotCurrentScan(this@ScanActivity)

                // Save scan results
                val entities = scannedApps.map { app ->
                    ScanResultEntity(
                        packageName = app.packageName,
                        appName = app.appName,
                        versionName = app.versionName,
                        versionCode = app.versionCode,
                        permissions = app.permissions.joinToString(","),
                        riskLevel = effRisk(app).name,
                        isSystemApp = app.isSystemApp,
                        installedDate = app.installedDate,
                        lastUpdated = app.lastUpdated,
                        scannedAt = now
                    )
                }
                db.scanResultDao().clearAll()
                db.scanResultDao().insertAll(entities)

                // Auto-populate monitored apps (high/medium risk enabled by default)
                val monitorDao = db.monitoredAppDao()
                for (app in scannedApps) {
                    val existing = monitorDao.getByPackage(app.packageName)
                    if (existing == null) {
                        monitorDao.insert(
                            MonitoredAppEntity(
                                packageName = app.packageName,
                                appName = app.appName,
                                riskLevel = effRisk(app).name,
                                isMonitored = app.riskLevel != RiskLevel.LOW
                            )
                        )
                    }
                }
            }

            animateProgressTo(95)
            delay(200)

            completeStep(3)
            tvStep3.text = "Results cached"

            PreferencesManager.setLastScanTime(this@ScanActivity, System.currentTimeMillis())
            animateProgressTo(100)

            delay(300)
            scanComplete("Scan complete — ${scannedApps.size} apps audited")
            } catch (e: Exception) {
                // Never leave the user stranded mid-animation: log, finish the ring, and still
                // reveal "View Results" so they can proceed to whatever was cached.
                android.util.Log.e("ScanActivity", "Scan failed", e)
                animateProgressTo(100)
                scanComplete("Scan interrupted — tap to view results")
            }
        }
    }

    private fun animateProgressTo(target: Int) {
        progressAnimator?.cancel()
        val current = progressCircle.progress
        progressAnimator = ValueAnimator.ofInt(current, target).apply {
            duration = 300L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                progressCircle.progress = value
                tvPercentage.text = "$value%"
            }
            start()
        }
    }

    private fun completeStep(step: Int) {
        val greenColor = ContextCompat.getColor(this, R.color.risk_low)
        val whiteColor = ContextCompat.getColor(this, R.color.white)

        when (step) {
            1 -> {
                ivStep1Icon.setImageResource(R.drawable.ic_circle_complete)
                ivStep1Icon.setColorFilter(greenColor)
                tvStep1.setTextColor(whiteColor)
            }
            2 -> {
                ivStep2Icon.setImageResource(R.drawable.ic_circle_complete)
                ivStep2Icon.setColorFilter(greenColor)
                tvStep2.setTextColor(whiteColor)
            }
            3 -> {
                ivStep3Icon.setImageResource(R.drawable.ic_circle_complete)
                ivStep3Icon.setColorFilter(greenColor)
                tvStep3.setTextColor(whiteColor)
            }
        }
    }

    private fun scanComplete(message: String) {
        tvScanStatus.text = message
        tvScanStatus.setTextColor(ContextCompat.getColor(this, R.color.risk_low))
        // Celebratory beat: settle the big percentage to green + a confirmation haptic.
        tvPercentage.setTextColor(ContextCompat.getColor(this, R.color.risk_low))
        val haptic = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            android.view.HapticFeedbackConstants.CONFIRM
        else
            android.view.HapticFeedbackConstants.LONG_PRESS
        runCatching { btnViewResults.performHapticFeedback(haptic) }

        btnViewResults.visibility = View.VISIBLE
        btnViewResults.alpha = 0f
        btnViewResults.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    override fun onDestroy() {
        progressAnimator?.cancel()
        super.onDestroy()
    }
}
