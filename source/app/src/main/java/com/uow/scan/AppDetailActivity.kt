package com.uow.scan

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.uow.scan.adapter.PermissionAdapter
import com.uow.scan.adapter.TrackerAdapter
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.PermissionAccessEntity
import com.uow.scan.model.RiskLevel
import com.uow.scan.util.AppIntegrityChecker
import com.uow.scan.util.AppScanner
import com.uow.scan.util.PermissionHelper
import com.uow.scan.util.ScanDialog
import com.uow.scan.util.SensorAccessFormat
import com.uow.scan.util.TrackerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }

    private lateinit var btnBack: FrameLayout
    private lateinit var ivAppIcon: ImageView
    private lateinit var tvAppInitial: TextView
    private lateinit var tvAppName: TextView
    private lateinit var tvPackageName: TextView
    private lateinit var tvRiskBadge: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvPermissionCount: TextView
    private lateinit var tvTrackerCount: TextView
    private lateinit var btnManagePermissions: FrameLayout

    // Findings
    private lateinit var sectionFindings: LinearLayout
    private lateinit var findingsContainer: LinearLayout

    // Permission sections
    private lateinit var sectionPrivacyCritical: LinearLayout
    private lateinit var sectionStandardAccess: LinearLayout
    private lateinit var headerStandardAccess: LinearLayout
    private lateinit var tvPrivacyCriticalCount: TextView
    private lateinit var tvStandardAccessCount: TextView
    private lateinit var ivStandardAccessArrow: ImageView
    private lateinit var rvPrivacyCritical: RecyclerView
    private lateinit var rvStandardAccess: RecyclerView
    private lateinit var btnSensitiveInfo: ImageButton

    // Integrity
    private lateinit var tvIntegrityStatus: TextView
    private lateinit var progressIntegrity: ProgressBar
    private lateinit var integrityChecklist: LinearLayout

    // Trackers
    private lateinit var tvTrackerStatus: TextView
    private lateinit var progressTrackers: ProgressBar
    private lateinit var rvTrackers: RecyclerView

    private var targetPackageName: String = ""
    private var isStandardAccessExpanded = false
    private var sensorAccesses: List<PermissionAccessEntity> = emptyList()
    private var trackerCount: Int = 0

    // Inputs to the effective risk badge (HIGH only when a real finding exists). Each is filled
    // by a different async loader; refreshRiskBadge() recomputes the badge as they arrive.
    private var exposureRisk: RiskLevel? = null
    private var hasBgSensorFinding: Boolean = false
    private var integrityCritical: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_detail)

        targetPackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: run {
            finish()
            return
        }

        initViews()
        setupListeners()
        loadAppDetails()
        loadIntegrity()
        loadTrackers()
        loadAlertStats()
    }

    override fun onResume() {
        super.onResume()
        loadAppDetails()
        loadAlertStats()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        ivAppIcon = findViewById(R.id.ivAppIcon)
        tvAppInitial = findViewById(R.id.tvAppInitial)
        tvAppName = findViewById(R.id.tvAppName)
        tvPackageName = findViewById(R.id.tvPackageName)
        tvRiskBadge = findViewById(R.id.tvRiskBadge)
        tvVersion = findViewById(R.id.tvVersion)
        tvPermissionCount = findViewById(R.id.tvPermissionCount)
        tvTrackerCount = findViewById(R.id.tvTrackerCount)
        btnManagePermissions = findViewById(R.id.btnManagePermissions)

        sectionFindings = findViewById(R.id.sectionFindings)
        findingsContainer = findViewById(R.id.findingsContainer)

        sectionPrivacyCritical = findViewById(R.id.sectionPrivacyCritical)
        sectionStandardAccess = findViewById(R.id.sectionStandardAccess)
        headerStandardAccess = findViewById(R.id.headerStandardAccess)
        tvPrivacyCriticalCount = findViewById(R.id.tvPrivacyCriticalCount)
        tvStandardAccessCount = findViewById(R.id.tvStandardAccessCount)
        ivStandardAccessArrow = findViewById(R.id.ivStandardAccessArrow)
        rvPrivacyCritical = findViewById(R.id.rvPrivacyCritical)
        rvStandardAccess = findViewById(R.id.rvStandardAccess)
        btnSensitiveInfo = findViewById(R.id.btnSensitiveInfo)

        tvIntegrityStatus = findViewById(R.id.tvIntegrityStatus)
        progressIntegrity = findViewById(R.id.progressIntegrity)
        integrityChecklist = findViewById(R.id.integrityChecklist)

        tvTrackerStatus = findViewById(R.id.tvTrackerStatus)
        progressTrackers = findViewById(R.id.progressTrackers)
        rvTrackers = findViewById(R.id.rvTrackers)

        rvPrivacyCritical.layoutManager = LinearLayoutManager(this)
        rvStandardAccess.layoutManager = LinearLayoutManager(this)
        rvTrackers.layoutManager = LinearLayoutManager(this)

        rvStandardAccess.visibility = View.GONE
        isStandardAccessExpanded = false
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        // Match the design helper text: "remove the app from Android's app settings".
        // Open the full app details page (where Permissions, Storage, and Uninstall live).
        btnManagePermissions.setOnClickListener {
            PermissionHelper.openAppSettings(this, targetPackageName)
        }

        headerStandardAccess.setOnClickListener {
            toggleStandardAccess()
        }

        btnSensitiveInfo.setOnClickListener {
            showSensitivePermissionsInfo()
        }
    }

    private fun showSensitivePermissionsInfo() {
        val message = """
            |Privacy Critical permissions allow apps to access sensitive data about you:
            |
            |• Camera
            |• Microphone
            |• Location
            |• Phone & Calls
            |• SMS
            |• Contacts
            |• Calendar
            |• Storage / Media
            |• Body sensors
            |
            |Apps holding many of these can track movements, access private conversations, or collect personal information. Review carefully and revoke anything you don't need.
        """.trimMargin()

        ScanDialog.notice(
            context = this,
            title = "What are sensitive permissions?",
            message = message,
            buttonText = "Got it",
        )
    }

    private fun toggleStandardAccess() {
        isStandardAccessExpanded = !isStandardAccessExpanded
        rvStandardAccess.visibility = if (isStandardAccessExpanded) View.VISIBLE else View.GONE
        ivStandardAccessArrow.rotation = if (isStandardAccessExpanded) 180f else 0f
    }

    private fun loadAppDetails() {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    targetPackageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(targetPackageName, PackageManager.GET_PERMISSIONS)
            }

            val applicationInfo = packageInfo.applicationInfo
            val label = applicationInfo?.let { packageManager.getApplicationLabel(it).toString() } ?: targetPackageName

            applicationInfo?.let {
                ivAppIcon.setImageDrawable(packageManager.getApplicationIcon(it))
                tvAppName.text = label
            }
            // Initial fallback (shown if icon load fails for any reason)
            tvAppInitial.text = label.firstOrNull()?.toString()?.uppercase() ?: "?"

            tvPackageName.text = targetPackageName
            tvVersion.text = packageInfo.versionName ?: "—"

            val grantedPermissions = getGrantedPermissions(packageInfo)
            tvPermissionCount.text = grantedPermissions.size.toString()

            val privacyCritical = grantedPermissions.filter { PermissionHelper.isSensitivePermission(it) }
            val standardAccess = grantedPermissions.filter { !PermissionHelper.isSensitivePermission(it) }

            if (privacyCritical.isNotEmpty()) {
                sectionPrivacyCritical.visibility = View.VISIBLE
                tvPrivacyCriticalCount.text = privacyCritical.size.toString()
                rvPrivacyCritical.adapter = PermissionAdapter(privacyCritical)
            } else {
                sectionPrivacyCritical.visibility = View.GONE
            }

            if (standardAccess.isNotEmpty()) {
                sectionStandardAccess.visibility = View.VISIBLE
                tvStandardAccessCount.text = standardAccess.size.toString()
                rvStandardAccess.adapter = PermissionAdapter(standardAccess)
                rvStandardAccess.visibility = View.GONE
                isStandardAccessExpanded = false
                ivStandardAccessArrow.rotation = 0f
            } else {
                sectionStandardAccess.visibility = View.GONE
            }

            // Risk badge — small uppercase pill
            lifecycleScope.launch {
                val apps = withContext(Dispatchers.IO) {
                    AppScanner.scanInstalledApps(this@AppDetailActivity)
                }
                val app = apps.find { it.packageName == targetPackageName }
                app?.let {
                    exposureRisk = it.riskLevel
                    refreshRiskBadge()
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Toast.makeText(this, "App not found", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * Returns only dangerous permissions the user has explicitly granted.
     * Normal permissions (INTERNET, WAKE_LOCK, etc.) are auto-granted and don't reflect a user decision.
     */
    private fun getGrantedPermissions(packageInfo: PackageInfo): List<String> {
        val permissions = packageInfo.requestedPermissions ?: return emptyList()
        val flags = packageInfo.requestedPermissionsFlags ?: return emptyList()
        return permissions.filterIndexed { index, permission ->
            val isGranted = (flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            if (!isGranted) return@filterIndexed false
            try {
                val permInfo = packageManager.getPermissionInfo(permission, 0)
                (permInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }.distinct()
    }

    /**
     * Recomputes the risk badge from the latest known signals. The badge is HIGH only when a
     * real finding exists (an observed background sensor access, or a critical integrity issue);
     * otherwise an app's permission capability is capped at MEDIUM. Called as each async signal
     * lands, so the badge settles to its final value once everything has loaded.
     */
    private fun refreshRiskBadge() {
        val exposure = exposureRisk ?: return
        val hasFinding = hasBgSensorFinding || integrityCritical
        setRiskBadge(AppScanner.effectiveRisk(exposure, hasFinding))
    }

    private fun setRiskBadge(riskLevel: RiskLevel) {
        tvRiskBadge.visibility = View.VISIBLE
        when (riskLevel) {
            RiskLevel.HIGH -> {
                tvRiskBadge.text = getString(R.string.app_detail_risk_high)
                tvRiskBadge.setBackgroundResource(R.drawable.bg_v4_perm_pill_bad)
                tvRiskBadge.setTextColor(getColor(R.color.v4_bad))
            }
            RiskLevel.MEDIUM -> {
                tvRiskBadge.text = getString(R.string.app_detail_risk_medium)
                tvRiskBadge.setBackgroundResource(R.drawable.bg_v4_perm_pill_warn)
                tvRiskBadge.setTextColor(getColor(R.color.v4_warn))
            }
            RiskLevel.LOW -> {
                tvRiskBadge.text = getString(R.string.app_detail_risk_low)
                tvRiskBadge.setBackgroundResource(R.drawable.bg_v4_perm_pill_ok)
                tvRiskBadge.setTextColor(getColor(R.color.v4_ok))
            }
        }
    }

    private fun loadIntegrity() {
        progressIntegrity.visibility = View.VISIBLE
        tvIntegrityStatus.visibility = View.GONE
        integrityChecklist.removeAllViews()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AppIntegrityChecker.check(this@AppDetailActivity, targetPackageName)
            }

            progressIntegrity.visibility = View.GONE
            tvIntegrityStatus.visibility = View.VISIBLE

            when (result.overallStatus) {
                AppIntegrityChecker.Status.CRITICAL -> {
                    tvIntegrityStatus.text = "Issues found"
                    tvIntegrityStatus.setTextColor(getColor(R.color.v4_bad))
                    tvIntegrityStatus.setBackgroundResource(R.drawable.bg_v4_perm_pill_bad)
                }
                AppIntegrityChecker.Status.WARNING -> {
                    tvIntegrityStatus.text = "Warnings"
                    tvIntegrityStatus.setTextColor(getColor(R.color.v4_warn))
                    tvIntegrityStatus.setBackgroundResource(R.drawable.bg_v4_perm_pill_warn)
                }
                AppIntegrityChecker.Status.INFO -> {
                    tvIntegrityStatus.text = "Info"
                    tvIntegrityStatus.setTextColor(getColor(R.color.v4_fg2))
                    tvIntegrityStatus.setBackgroundResource(R.drawable.bg_v4_perm_pill_idle)
                }
                AppIntegrityChecker.Status.CLEAN -> {
                    tvIntegrityStatus.text = "Clean"
                    tvIntegrityStatus.setTextColor(getColor(R.color.v4_ok))
                    tvIntegrityStatus.setBackgroundResource(R.drawable.bg_v4_perm_pill_ok)
                }
            }

            val checks = result.checks
            for ((index, check) in checks.withIndex()) {
                val itemView = LayoutInflater.from(this@AppDetailActivity)
                    .inflate(R.layout.item_integrity_check, integrityChecklist, false)
                bindIntegrityRow(itemView, check, isLast = index == checks.size - 1)
                integrityChecklist.addView(itemView)
            }

            // A critical integrity issue is a real finding that escalates the risk badge to HIGH.
            integrityCritical = result.overallStatus == AppIntegrityChecker.Status.CRITICAL
            refreshRiskBadge()
            refreshFindings()
        }
    }

    private fun bindIntegrityRow(
        itemView: View,
        check: AppIntegrityChecker.IntegrityCheck,
        isLast: Boolean
    ) {
        val ivIcon = itemView.findViewById<ImageView>(R.id.ivStatusIcon)
        val tvName = itemView.findViewById<TextView>(R.id.tvCheckName)
        val tvDetail = itemView.findViewById<TextView>(R.id.tvCheckDetail)
        val tvSeverity = itemView.findViewById<TextView>(R.id.tvSeverity)
        val divider = itemView.findViewById<View>(R.id.integrityRowDivider)

        tvName.text = check.name
        tvDetail.text = check.detail
        tvDetail.visibility = if (check.detail.isBlank()) View.GONE else View.VISIBLE

        if (check.passed) {
            ivIcon.setImageResource(R.drawable.ic_glyph_check)
            ivIcon.setColorFilter(getColor(R.color.v4_ok))
            tvSeverity.visibility = View.VISIBLE
            tvSeverity.text = getString(R.string.app_detail_integrity_pass)
            tvSeverity.setTextColor(getColor(R.color.v4_ok))
            tvSeverity.setBackgroundResource(0)
            tvSeverity.setPadding(0, 0, 0, 0)
        } else {
            ivIcon.setImageResource(R.drawable.ic_glyph_warn)
            val severityColor = when (check.severity) {
                AppIntegrityChecker.Severity.CRITICAL -> R.color.v4_bad
                AppIntegrityChecker.Severity.WARNING -> R.color.v4_warn
                AppIntegrityChecker.Severity.INFO -> R.color.v4_fg2
            }
            ivIcon.setColorFilter(getColor(severityColor))
            tvSeverity.visibility = View.VISIBLE
            tvSeverity.text = getString(R.string.app_detail_integrity_flagged)
            tvSeverity.setTextColor(getColor(severityColor))
            tvSeverity.setBackgroundResource(0)
            tvSeverity.setPadding(0, 0, 0, 0)
        }

        divider.visibility = if (isLast) View.GONE else View.VISIBLE
    }

    private fun loadTrackers() {
        tvTrackerStatus.text = getString(R.string.loading_trackers)
        tvTrackerStatus.setTextColor(getColor(R.color.v4_fg3))
        progressTrackers.visibility = View.VISIBLE
        rvTrackers.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                TrackerRepository.getTrackersForApp(applicationContext, targetPackageName)
            }

            withContext(Dispatchers.Main) {
                progressTrackers.visibility = View.GONE

                result.fold(
                    onSuccess = { trackers ->
                        trackerCount = trackers.size
                        tvTrackerCount.text = trackers.size.toString()
                        if (trackers.isEmpty()) {
                            tvTrackerCount.setTextColor(getColor(R.color.v4_ok))
                            tvTrackerStatus.text = getString(R.string.no_trackers)
                            tvTrackerStatus.setTextColor(getColor(R.color.v4_ok))
                            rvTrackers.visibility = View.GONE
                        } else {
                            tvTrackerCount.setTextColor(getColor(R.color.v4_warn))
                            tvTrackerStatus.text = "${trackers.size} detected"
                            tvTrackerStatus.setTextColor(getColor(R.color.v4_warn))
                            rvTrackers.visibility = View.VISIBLE
                            rvTrackers.adapter = TrackerAdapter(trackers)
                        }
                        refreshFindings()
                    },
                    onFailure = {
                        tvTrackerCount.text = "—"
                        tvTrackerCount.setTextColor(getColor(R.color.v4_fg3))
                        tvTrackerStatus.text = getString(R.string.tracker_error)
                        tvTrackerStatus.setTextColor(getColor(R.color.v4_fg3))
                        rvTrackers.visibility = View.GONE
                    }
                )
            }
        }
    }

    private fun loadAlertStats() {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val weekAgo = now - 7L * 24 * 60 * 60 * 1000
            val accesses = withContext(Dispatchers.IO) {
                // Real observed camera/mic/location access events (with true start/end times
                // and a foreground/background flag), captured live by OpAccessTracker and the
                // privacy NotificationListener.
                ScanDatabase.getInstance(this@AppDetailActivity)
                    .permissionAccessDao()
                    .accessesInWindow(targetPackageName, weekAgo, now)
            }
            sensorAccesses = accesses
            hasBgSensorFinding = accesses.any { !it.foregroundAtStart }
            refreshRiskBadge()
            refreshFindings()
        }
    }

    /**
     * Builds the Findings card from REAL observed evidence:
     *  • Camera/microphone/location access events captured live by OpAccessTracker and the
     *    privacy NotificationListener — each shows the real active duration, when it happened,
     *    and whether it was a background access (a concern, red) or while you had the app open
     *    (transparency, neutral).
     *  • Detected trackers.
     * We deliberately do NOT flag ordinary background network data — virtually every app uses
     * it (push, sync) so surfacing it as a "finding" is a false alarm, not a real concern.
     * Hidden entirely when nothing real has been observed (no fabricated rows).
     */
    private fun refreshFindings() {
        findingsContainer.removeAllViews()
        val rows = mutableListOf<FindingRow>()

        // Real sensor-access events, most recent first. Background accesses sort ahead of
        // foreground ones so concerns lead.
        val recentAccesses = sensorAccesses
            .sortedWith(compareBy<PermissionAccessEntity> { it.foregroundAtStart }
                .thenByDescending { it.startedAt })
            .take(3)
        for (acc in recentAccesses) {
            rows += FindingRow(
                severityColor = if (acc.foregroundAtStart) R.color.v4_fg3 else R.color.v4_bad,
                title = SensorAccessFormat.title(acc),
                detail = SensorAccessFormat.detail(acc)
            )
        }

        if (trackerCount > 0) {
            rows += FindingRow(
                severityColor = R.color.v4_warn,
                title = getString(R.string.app_detail_finding_trackers, trackerCount),
                detail = getString(R.string.app_detail_finding_trackers_detail)
            )
        }

        if (rows.isEmpty()) {
            sectionFindings.visibility = View.GONE
            return
        }
        sectionFindings.visibility = View.VISIBLE

        for ((index, row) in rows.withIndex()) {
            val view = LayoutInflater.from(this)
                .inflate(R.layout.item_v4_finding_row, findingsContainer, false)
            view.findViewById<View>(R.id.findingBar).setBackgroundColor(getColor(row.severityColor))
            view.findViewById<TextView>(R.id.findingText).text = row.title
            view.findViewById<TextView>(R.id.findingDetail).text = row.detail
            view.findViewById<View>(R.id.findingRowDivider).visibility =
                if (index == rows.size - 1) View.GONE else View.VISIBLE
            findingsContainer.addView(view)
        }
    }

    private data class FindingRow(val severityColor: Int, val title: String, val detail: String)

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
