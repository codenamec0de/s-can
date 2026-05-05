package com.uow.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.uow.scan.ui.home.widget.WifiScoreGaugeView
import com.uow.scan.util.WifiSecurityAnalyzer
import com.uow.scan.util.WifiSecurityAnalyzer.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WifiSecurityActivity : AppCompatActivity() {

    private lateinit var btnBack: View
    private lateinit var btnRescanTop: View

    private lateinit var cardPermission: LinearLayout
    private lateinit var btnGrantPermission: MaterialButton

    // Hero
    private lateinit var heroCard: LinearLayout
    private lateinit var scoreGauge: WifiScoreGaugeView
    private lateinit var tvScore: TextView
    private lateinit var ivWifiIcon: ImageView
    private lateinit var tvHeroEyebrow: TextView
    private lateinit var tvSsid: TextView
    private lateinit var tvNetworkSummary: TextView
    private lateinit var tvGrade: TextView

    // Metric strip
    private lateinit var tvCipher: TextView
    private lateinit var tvCipherSub: TextView
    private lateinit var tvPmf: TextView
    private lateinit var tvSignal: TextView

    // Findings + details
    private lateinit var tvFindingsHeader: TextView
    private lateinit var findingsContainer: LinearLayout
    private lateinit var detailsContainer: LinearLayout

    // Action area
    private lateinit var btnRescan: MaterialButton
    private lateinit var btnExport: MaterialButton
    private lateinit var tvLastScan: TextView

    private val requestPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { analyze() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_security)
        bindViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        analyze()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        btnRescanTop = findViewById(R.id.btnRescanTop)

        cardPermission = findViewById(R.id.cardPermission)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)

        heroCard = findViewById(R.id.heroCard)
        scoreGauge = findViewById(R.id.scoreGauge)
        tvScore = findViewById(R.id.tvScore)
        ivWifiIcon = findViewById(R.id.ivWifiIcon)
        tvHeroEyebrow = findViewById(R.id.tvHeroEyebrow)
        tvSsid = findViewById(R.id.tvSsid)
        tvNetworkSummary = findViewById(R.id.tvNetworkSummary)
        tvGrade = findViewById(R.id.tvGrade)

        tvCipher = findViewById(R.id.tvCipher)
        tvCipherSub = findViewById(R.id.tvCipherSub)
        tvPmf = findViewById(R.id.tvPmf)
        tvSignal = findViewById(R.id.tvSignal)

        tvFindingsHeader = findViewById(R.id.tvFindingsHeader)
        findingsContainer = findViewById(R.id.findingsContainer)
        detailsContainer = findViewById(R.id.detailsContainer)

        btnRescan = findViewById(R.id.btnRescan)
        btnExport = findViewById(R.id.btnExport)
        tvLastScan = findViewById(R.id.tvLastScan)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
        btnRescanTop.setOnClickListener { analyze() }
        btnRescan.setOnClickListener { analyze() }
        btnExport.setOnClickListener {
            startActivity(Intent(this, ExportReportActivity::class.java))
        }
        btnGrantPermission.setOnClickListener { requestScanPermission() }
    }

    private fun analyze() {
        val missing = missingPermissions()
        cardPermission.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                WifiSecurityAnalyzer.analyze(this@WifiSecurityActivity)
            }
            render(result)
        }
    }

    private fun missingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNearby = ContextCompat.checkSelfPermission(
                this, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNearby) missing += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        val hasLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) missing += Manifest.permission.ACCESS_FINE_LOCATION
        return missing
    }

    private fun requestScanPermission() {
        val toRequest = missingPermissions().toTypedArray()
        if (toRequest.isEmpty()) {
            analyze()
            return
        }
        val alreadyDeniedForever = toRequest.any { perm ->
            !shouldShowRequestPermissionRationale(perm) &&
                ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (alreadyDeniedForever) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        } else {
            requestPermLauncher.launch(toRequest)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Rendering
    // ─────────────────────────────────────────────────────────────────────

    private fun render(r: WifiSecurityAnalyzer.WifiSecurityResult) {
        renderHero(r)
        renderMetricStrip(r)
        renderFindings(r)
        renderDetails(r)
        tvLastScan.text = getString(R.string.wifi_v4_last_scan_just_now)
    }

    private fun renderHero(r: WifiSecurityAnalyzer.WifiSecurityResult) {
        val gradeColorRes = gradeColorRes(r)
        tvScore.text = if (r.notConnected) "—" else r.score.toString()
        tvScore.setTextColor(ContextCompat.getColor(this, R.color.v4_fg0))
        scoreGauge.setScore(if (r.notConnected) 0 else r.score, gradeColorRes)

        if (r.notConnected) {
            ivWifiIcon.setImageResource(R.drawable.ic_glyph_wifi)
            ivWifiIcon.setColorFilter(ContextCompat.getColor(this, R.color.v4_fg3))
            tvHeroEyebrow.setText(R.string.wifi_v4_eyebrow_not_connected)
            tvHeroEyebrow.setTextColor(ContextCompat.getColor(this, R.color.v4_fg3))
            tvSsid.setText(R.string.wifi_v4_no_network)
            tvNetworkSummary.setText(R.string.wifi_v4_connect_prompt)
        } else {
            ivWifiIcon.setImageResource(R.drawable.ic_glyph_wifi)
            ivWifiIcon.setColorFilter(ContextCompat.getColor(this, R.color.v4_accent))
            tvHeroEyebrow.setText(R.string.wifi_v4_eyebrow_connected)
            tvHeroEyebrow.setTextColor(ContextCompat.getColor(this, R.color.v4_accent))
            tvSsid.text = r.ssid ?: getString(R.string.wifi_v4_unknown_network)
            tvNetworkSummary.text = buildHeroSummary(r)
        }

        // Grade pill
        val (gradeLabel, gradeBgRes) = gradeBadge(r)
        tvGrade.text = gradeLabel
        tvGrade.setTextColor(ContextCompat.getColor(this, gradeColorRes))
        tvGrade.setBackgroundResource(gradeBgRes)
    }

    private fun buildHeroSummary(r: WifiSecurityAnalyzer.WifiSecurityResult): String {
        val auth = r.authType.name.replace('_', ' ')
            .lowercase()
            .replaceFirstChar { it.titlecase() }
        val band = r.bandMhz?.let { "${it / 1000} GHz" }
        val rssi = r.rssiDbm?.let { "$it dBm" }
        return listOfNotNull(auth, band, rssi).joinToString(" · ")
    }

    private fun renderMetricStrip(r: WifiSecurityAnalyzer.WifiSecurityResult) {
        // Cipher
        val cipher = r.cipher
        tvCipher.text = cipher ?: if (r.notConnected) "—" else "none"
        tvCipherSub.setText(R.string.wifi_v4_metric_cipher_sub)
        tvCipher.setTextColor(
            ContextCompat.getColor(
                this,
                when (cipher) {
                    "CCMP" -> R.color.v4_ok
                    "TKIP", "MIXED" -> R.color.v4_warn
                    null -> R.color.v4_fg2
                    else -> R.color.v4_fg0
                }
            )
        )

        // PMF
        val pmfText = when {
            r.notConnected -> "—"
            r.pmfRequired -> getString(R.string.wifi_v4_metric_pmf_required)
            r.pmfCapable -> getString(R.string.wifi_v4_metric_pmf_capable)
            else -> getString(R.string.wifi_v4_metric_pmf_off)
        }
        tvPmf.text = pmfText
        tvPmf.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    r.notConnected -> R.color.v4_fg2
                    r.pmfRequired -> R.color.v4_ok
                    r.pmfCapable -> R.color.v4_accent
                    else -> R.color.v4_warn
                }
            )
        )

        // Signal
        val rssi = r.rssiDbm
        tvSignal.text = if (rssi == null) "—" else rssi.toString()
        tvSignal.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    rssi == null -> R.color.v4_fg2
                    r.signalQuality >= 70 -> R.color.v4_ok
                    r.signalQuality >= 40 -> R.color.v4_warn
                    else -> R.color.v4_bad
                }
            )
        )
    }

    private fun renderFindings(r: WifiSecurityAnalyzer.WifiSecurityResult) {
        findingsContainer.removeAllViews()
        val flagged = r.findings.count { it.severity != Severity.OK && it.severity != Severity.INFO }
        tvFindingsHeader.text = if (r.findings.isEmpty()) {
            getString(R.string.wifi_v4_section_findings)
        } else {
            getString(R.string.wifi_v4_section_findings_count, flagged, r.findings.size)
        }

        val inflater = LayoutInflater.from(this)
        for (finding in r.findings) {
            val row = inflater.inflate(R.layout.item_wifi_finding, findingsContainer, false)
            bindFindingRow(row, finding)
            findingsContainer.addView(row)
        }
    }

    private fun bindFindingRow(row: View, finding: WifiSecurityAnalyzer.Finding) {
        val sevTile = row.findViewById<FrameLayout>(R.id.sevTile)
        val sevIcon = row.findViewById<ImageView>(R.id.sevIcon)
        val pill = row.findViewById<TextView>(R.id.tvSeverityPill)
        val (tileBgRes, pillBgRes, colorRes, iconRes, label) = severityStyle(finding.severity)
        sevTile.setBackgroundResource(tileBgRes)
        sevIcon.setImageResource(iconRes)
        sevIcon.setColorFilter(ContextCompat.getColor(this, colorRes))
        pill.text = label
        pill.setTextColor(ContextCompat.getColor(this, colorRes))
        pill.setBackgroundResource(pillBgRes)

        row.findViewById<TextView>(R.id.tvFindingTitle).text = finding.title
        row.findViewById<TextView>(R.id.tvFindingBody).text = finding.description
        val fixBox = row.findViewById<LinearLayout>(R.id.fixBox)
        val tvFix = row.findViewById<TextView>(R.id.tvFindingFix)
        if (finding.recommendation != null) {
            fixBox.visibility = View.VISIBLE
            tvFix.text = finding.recommendation
        } else {
            fixBox.visibility = View.GONE
        }
    }

    private fun renderDetails(r: WifiSecurityAnalyzer.WifiSecurityResult) {
        detailsContainer.removeAllViews()
        if (r.notConnected) {
            addDetail(detailsContainer, getString(R.string.wifi_v4_detail_status), "—", isLast = true)
            return
        }
        val rows = mutableListOf<Pair<String, String>>()
        rows += getString(R.string.wifi_v4_detail_bssid) to (r.bssid ?: "—")
        rows += getString(R.string.wifi_v4_detail_standard) to (r.wifiStandard ?: "—")
        rows += getString(R.string.wifi_v4_detail_band) to formatBand(r)
        rows += getString(R.string.wifi_v4_detail_auth) to r.authType.name.replace('_', ' ')
        rows += getString(R.string.wifi_v4_detail_cipher) to (r.cipher ?: "—")
        rows += getString(R.string.wifi_v4_detail_pmf) to when {
            r.pmfRequired -> "required"
            r.pmfCapable -> "capable"
            else -> "off"
        }
        rows += getString(R.string.wifi_v4_detail_rssi) to (r.rssiDbm?.let { "$it dBm (${r.signalQuality}%)" } ?: "—")
        rows += getString(R.string.wifi_v4_detail_wps) to if (r.wpsEnabled) "on" else "off"
        rows += getString(R.string.wifi_v4_detail_hidden) to if (r.hiddenSsid) "yes" else "no"
        rows += getString(R.string.wifi_v4_detail_mac_rand) to when (r.macRandomized) {
            true -> "on"
            false -> "off"
            null -> "unknown"
        }
        rows += getString(R.string.wifi_v4_detail_captive) to if (r.captivePortal) "yes" else "no"
        rows += getString(R.string.wifi_v4_detail_internet) to if (r.internetValidated) "validated" else "—"
        rows += getString(R.string.wifi_v4_detail_dns) to if (r.dnsServers.isEmpty()) "—" else r.dnsServers.joinToString(", ")
        val peers = r.nearbySameSsidCount.toString() +
            if (r.apparentEvilTwin) " ⚠ divergent" else ""
        rows += getString(R.string.wifi_v4_detail_peers) to peers

        rows.forEachIndexed { i, (k, v) ->
            addDetail(detailsContainer, k, v, isLast = i == rows.size - 1)
        }
    }

    private fun formatBand(r: WifiSecurityAnalyzer.WifiSecurityResult): String {
        val mhz = r.bandMhz ?: return "—"
        return "%.1f GHz".format(mhz / 1000.0)
    }

    private fun addDetail(container: LinearLayout, key: String, value: String, isLast: Boolean) {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_wifi_detail_row, container, false)
        row.findViewById<TextView>(R.id.tvDetailKey).text = key
        row.findViewById<TextView>(R.id.tvDetailValue).text = value
        container.addView(row)
        if (!isLast) container.addView(detailDivider())
    }

    private fun detailDivider(): View {
        val v = View(this)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (1 * resources.displayMetrics.density).toInt()
        )
        lp.marginStart = (16 * resources.displayMetrics.density).toInt()
        lp.marginEnd = (16 * resources.displayMetrics.density).toInt()
        v.layoutParams = lp
        v.setBackgroundColor(ContextCompat.getColor(this, R.color.v4_hairline))
        return v
    }

    // ─────────────────────────────────────────────────────────────────────
    // Severity / grade styling
    // ─────────────────────────────────────────────────────────────────────

    private data class SevStyle(
        val tileBgRes: Int,
        val pillBgRes: Int,
        val colorRes: Int,
        val iconRes: Int,
        val label: String
    )

    private fun severityStyle(s: Severity): SevStyle = when (s) {
        Severity.CRITICAL -> SevStyle(
            R.drawable.bg_v4_breach_tile_bad, R.drawable.bg_v4_perm_pill_bad,
            R.color.v4_bad, R.drawable.ic_glyph_warn,
            getString(R.string.wifi_v4_pill_critical)
        )
        Severity.HIGH -> SevStyle(
            R.drawable.bg_v4_breach_tile_bad, R.drawable.bg_v4_perm_pill_bad,
            R.color.v4_bad, R.drawable.ic_glyph_warn,
            getString(R.string.wifi_v4_pill_high)
        )
        Severity.MEDIUM -> SevStyle(
            R.drawable.bg_v4_breach_tile_warn, R.drawable.bg_v4_perm_pill_warn,
            R.color.v4_warn, R.drawable.ic_glyph_warn,
            getString(R.string.wifi_v4_pill_medium)
        )
        Severity.LOW -> SevStyle(
            R.drawable.bg_v4_breach_tile_warn, R.drawable.bg_v4_perm_pill_warn,
            R.color.v4_warn, R.drawable.ic_glyph_warn,
            getString(R.string.wifi_v4_pill_low)
        )
        Severity.INFO -> SevStyle(
            R.drawable.bg_v4_perm_pill_idle, R.drawable.bg_v4_perm_pill_idle,
            R.color.v4_fg2, R.drawable.ic_glyph_check,
            getString(R.string.wifi_v4_pill_info)
        )
        Severity.OK -> SevStyle(
            R.drawable.bg_v4_perm_pill_ok, R.drawable.bg_v4_perm_pill_ok,
            R.color.v4_ok, R.drawable.ic_glyph_check,
            getString(R.string.wifi_v4_pill_ok)
        )
    }

    private fun gradeColorRes(r: WifiSecurityAnalyzer.WifiSecurityResult): Int = when {
        r.notConnected -> R.color.v4_fg3
        r.grade == WifiSecurityAnalyzer.Grade.EXCELLENT ||
            r.grade == WifiSecurityAnalyzer.Grade.GOOD -> R.color.v4_ok
        r.grade == WifiSecurityAnalyzer.Grade.FAIR -> R.color.v4_accent
        r.grade == WifiSecurityAnalyzer.Grade.POOR -> R.color.v4_warn
        else -> R.color.v4_bad
    }

    private fun gradeBadge(r: WifiSecurityAnalyzer.WifiSecurityResult): Pair<String, Int> {
        if (r.notConnected) return getString(R.string.wifi_v4_grade_not_connected) to R.drawable.bg_v4_perm_pill_idle
        return when (r.grade) {
            WifiSecurityAnalyzer.Grade.EXCELLENT,
            WifiSecurityAnalyzer.Grade.GOOD ->
                getString(R.string.wifi_v4_grade_good) to R.drawable.bg_v4_perm_pill_ok
            WifiSecurityAnalyzer.Grade.FAIR ->
                getString(R.string.wifi_v4_grade_fair) to R.drawable.bg_v4_perm_pill_accent
            WifiSecurityAnalyzer.Grade.POOR ->
                getString(R.string.wifi_v4_grade_poor) to R.drawable.bg_v4_perm_pill_warn
            WifiSecurityAnalyzer.Grade.CRITICAL ->
                getString(R.string.wifi_v4_grade_critical) to R.drawable.bg_v4_perm_pill_bad
        }
    }
}
