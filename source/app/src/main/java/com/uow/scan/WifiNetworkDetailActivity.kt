package com.uow.scan

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.uow.scan.ui.home.widget.WifiScoreGaugeView
import com.uow.scan.util.PreferencesManager
import com.uow.scan.util.WifiNetwork
import com.uow.scan.util.WifiSecurityAnalyzer
import com.uow.scan.util.WifiSecurityAnalyzer.Grade
import com.uow.scan.util.WifiSecurityAnalyzer.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-network Wi-Fi security report. Renders ANY network — the connected one or a
 * nearby AP tapped from [WifiSecurityActivity] — passed in as a [WifiNetwork] extra.
 * Score gauge + metric strip + findings + network details + context actions.
 */
class WifiNetworkDetailActivity : AppCompatActivity() {

    private lateinit var btnBack: View
    private lateinit var btnRescanTop: View
    private lateinit var tvTopTitle: TextView

    private lateinit var scoreGauge: WifiScoreGaugeView
    private lateinit var tvScore: TextView
    private lateinit var ivWifiIcon: ImageView
    private lateinit var tvHeroEyebrow: TextView
    private lateinit var tvDuplicateBadge: TextView
    private lateinit var tvSsid: TextView
    private lateinit var tvNetworkSummary: TextView
    private lateinit var tvGrade: TextView

    private lateinit var tvCipher: TextView
    private lateinit var tvCipherSub: TextView
    private lateinit var tvPmf: TextView
    private lateinit var tvSignal: TextView

    private lateinit var evilTwinCard: View
    private lateinit var tvFindingsHeader: TextView
    private lateinit var findingsContainer: LinearLayout
    private lateinit var detailsContainer: LinearLayout

    private lateinit var btnPrimary: MaterialButton
    private lateinit var btnRisky: MaterialButton
    private lateinit var btnTrust: MaterialButton
    private lateinit var btnExport: MaterialButton

    private var network: WifiNetwork? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_network_detail)

        network = readNetworkExtra()
        if (network == null) {
            finish()
            return
        }

        bindViews()
        setupListeners()
        render(network!!)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun readNetworkExtra(): WifiNetwork? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_NETWORK, WifiNetwork::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_NETWORK)
        }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        btnRescanTop = findViewById(R.id.btnRescanTop)
        tvTopTitle = findViewById(R.id.tvTopTitle)

        scoreGauge = findViewById(R.id.scoreGauge)
        tvScore = findViewById(R.id.tvScore)
        ivWifiIcon = findViewById(R.id.ivWifiIcon)
        tvHeroEyebrow = findViewById(R.id.tvHeroEyebrow)
        tvDuplicateBadge = findViewById(R.id.tvDuplicateBadge)
        tvSsid = findViewById(R.id.tvSsid)
        tvNetworkSummary = findViewById(R.id.tvNetworkSummary)
        tvGrade = findViewById(R.id.tvGrade)

        tvCipher = findViewById(R.id.tvCipher)
        tvCipherSub = findViewById(R.id.tvCipherSub)
        tvPmf = findViewById(R.id.tvPmf)
        tvSignal = findViewById(R.id.tvSignal)

        evilTwinCard = findViewById(R.id.evilTwinCard)
        tvFindingsHeader = findViewById(R.id.tvFindingsHeader)
        findingsContainer = findViewById(R.id.findingsContainer)
        detailsContainer = findViewById(R.id.detailsContainer)

        btnPrimary = findViewById(R.id.btnPrimary)
        btnRisky = findViewById(R.id.btnRisky)
        btnTrust = findViewById(R.id.btnTrust)
        btnExport = findViewById(R.id.btnExport)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
        btnRescanTop.setOnClickListener { reScan() }
        btnExport.setOnClickListener {
            startActivity(Intent(this, ExportReportActivity::class.java))
        }
    }

    /** Re-reads live state for the connected network; nearby networks just re-render. */
    private fun reScan() {
        val current = network ?: return
        if (!current.connected) {
            render(current)
            return
        }
        lifecycleScope.launch {
            val refreshed = withContext(Dispatchers.IO) {
                WifiSecurityAnalyzer.scanNearby(this@WifiNetworkDetailActivity).connected
            }
            if (refreshed != null) {
                network = refreshed
                render(refreshed)
            }
        }
    }

    private fun openWifiSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Rendering
    // ─────────────────────────────────────────────────────────────────────

    private fun render(net: WifiNetwork) {
        tvTopTitle.setText(
            if (net.connected) R.string.wifi_v4_detail_title_connected
            else R.string.wifi_v4_detail_title_nearby
        )
        renderHero(net)
        renderMetricStrip(net)
        evilTwinCard.visibility = if (net.evilTwin) View.VISIBLE else View.GONE
        renderFindings(net)
        renderDetails(net)
        renderActions(net)
        renderTrust(net)
    }

    private fun renderHero(net: WifiNetwork) {
        val gradeColorRes = gradeColorRes(net.grade)
        tvScore.text = net.score.toString()
        scoreGauge.setScore(net.score, gradeColorRes)

        val eyebrowColor = if (net.connected) R.color.v4_accent else R.color.v4_fg2
        ivWifiIcon.setColorFilter(ContextCompat.getColor(this, eyebrowColor))
        tvHeroEyebrow.setText(
            if (net.connected) R.string.wifi_v4_eyebrow_connected else R.string.wifi_v4_eyebrow_nearby
        )
        tvHeroEyebrow.setTextColor(ContextCompat.getColor(this, eyebrowColor))
        tvDuplicateBadge.visibility = if (net.evilTwin) View.VISIBLE else View.GONE

        tvSsid.text = net.ssid
        tvNetworkSummary.text = buildHeroSummary(net)

        val (gradeLabel, gradeBgRes) = gradeBadge(net.grade)
        tvGrade.text = gradeLabel
        tvGrade.setTextColor(ContextCompat.getColor(this, gradeColorRes))
        tvGrade.setBackgroundResource(gradeBgRes)
    }

    private fun buildHeroSummary(net: WifiNetwork): String = listOfNotNull(
        WifiSecurityAnalyzer.authLabel(net.authType),
        net.bandMhz?.let { WifiSecurityAnalyzer.bandLabel(it) },
        "${net.rssiDbm} dBm"
    ).joinToString(" · ")

    private fun renderMetricStrip(net: WifiNetwork) {
        val cipher = net.cipher
        val cipherShort = when {
            cipher == null -> "None"
            cipher.contains("CCMP") -> "CCMP"
            cipher.contains("GCMP") -> "GCMP"
            else -> cipher.substringBefore(" ")
        }
        tvCipher.text = cipherShort
        tvCipher.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    cipher == null -> R.color.v4_bad
                    cipher.contains("CCMP") || cipher.contains("GCMP") -> R.color.v4_ok
                    cipher.contains("WEP") -> R.color.v4_bad
                    else -> R.color.v4_warn
                }
            )
        )
        tvCipherSub.text = if (cipher == null) "none" else "AES"

        val pmfText = when {
            net.pmfRequired -> getString(R.string.wifi_v4_metric_pmf_required)
            net.pmfCapable -> getString(R.string.wifi_v4_metric_pmf_capable)
            else -> getString(R.string.wifi_v4_metric_pmf_off)
        }
        tvPmf.text = pmfText
        tvPmf.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    net.pmfRequired -> R.color.v4_ok
                    net.pmfCapable -> R.color.v4_accent
                    else -> R.color.v4_warn
                }
            )
        )

        tvSignal.text = net.rssiDbm.toString()
        tvSignal.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    net.signalQuality >= 70 -> R.color.v4_ok
                    net.signalQuality >= 40 -> R.color.v4_warn
                    else -> R.color.v4_bad
                }
            )
        )
    }

    private fun renderFindings(net: WifiNetwork) {
        findingsContainer.removeAllViews()
        val flagged = net.findings.count { it.severity != Severity.OK && it.severity != Severity.INFO }
        tvFindingsHeader.text = if (net.findings.isEmpty()) {
            getString(R.string.wifi_v4_section_findings)
        } else {
            getString(R.string.wifi_v4_section_findings_count, flagged, net.findings.size)
        }

        val inflater = LayoutInflater.from(this)
        for (finding in net.findings) {
            val row = inflater.inflate(R.layout.item_wifi_finding, findingsContainer, false)
            bindFindingRow(row, finding)
            findingsContainer.addView(row)
        }
    }

    private fun bindFindingRow(row: View, finding: WifiSecurityAnalyzer.Finding) {
        val sevTile = row.findViewById<FrameLayout>(R.id.sevTile)
        val sevIcon = row.findViewById<ImageView>(R.id.sevIcon)
        val pill = row.findViewById<TextView>(R.id.tvSeverityPill)
        val style = severityStyle(finding.severity)
        sevTile.setBackgroundResource(style.tileBgRes)
        sevIcon.setImageResource(style.iconRes)
        sevIcon.setColorFilter(ContextCompat.getColor(this, style.colorRes))
        pill.text = style.label
        pill.setTextColor(ContextCompat.getColor(this, style.colorRes))
        pill.setBackgroundResource(style.pillBgRes)

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

    private fun renderDetails(net: WifiNetwork) {
        detailsContainer.removeAllViews()
        val rows = mutableListOf<Pair<String, String>>()
        rows += getString(R.string.wifi_v4_detail_bssid) to net.bssid
        net.vendor?.let { rows += getString(R.string.wifi_v4_detail_vendor) to it }
        rows += getString(R.string.wifi_v4_detail_standard) to (net.wifiStandard ?: "—")
        rows += getString(R.string.wifi_v4_detail_band) to formatBand(net)
        rows += getString(R.string.wifi_v4_detail_security) to WifiSecurityAnalyzer.authLabel(net.authType)
        rows += getString(R.string.wifi_v4_detail_cipher) to (net.cipher ?: "—")
        rows += getString(R.string.wifi_v4_detail_pmf) to when {
            net.pmfRequired -> "required"
            net.pmfCapable -> "capable"
            else -> "off"
        }
        rows += getString(R.string.wifi_v4_detail_rssi) to "${net.rssiDbm} dBm (${net.signalQuality}%)"
        rows += getString(R.string.wifi_v4_detail_wps) to if (net.wpsEnabled) "on" else "off"
        rows += getString(R.string.wifi_v4_detail_hidden) to if (net.hiddenSsid) "yes" else "no"
        rows += getString(R.string.wifi_v4_detail_mac_rand) to when (net.macRandomized) {
            true -> "on"
            false -> "off"
            null -> "unknown"
        }
        if (net.connected) {
            rows += getString(R.string.wifi_v4_detail_dns) to
                if (net.dnsServers.isEmpty()) "—" else net.dnsServers.joinToString(", ")
            rows += getString(R.string.wifi_v4_detail_internet) to
                if (net.internetValidated) "validated" else "—"
        }

        rows.forEachIndexed { i, (k, v) ->
            addDetail(detailsContainer, k, v, isLast = i == rows.size - 1)
        }
    }

    private fun formatBand(net: WifiNetwork): String {
        val band = net.bandMhz ?: return "—"
        val chan = net.channel?.let { " · ch $it" } ?: ""
        return WifiSecurityAnalyzer.bandLabel(band) + chan
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

    private fun renderActions(net: WifiNetwork) {
        if (net.connected) {
            btnPrimary.visibility = View.VISIBLE
            btnRisky.visibility = View.GONE
            btnPrimary.setText(R.string.wifi_v4_btn_rescan_full)
            btnPrimary.setIconResource(R.drawable.ic_glyph_refresh)
            btnPrimary.setOnClickListener { reScan() }
        } else if (net.isThreat) {
            btnPrimary.visibility = View.GONE
            btnRisky.visibility = View.VISIBLE
            btnRisky.setOnClickListener { openWifiSettings() }
        } else {
            btnPrimary.visibility = View.VISIBLE
            btnRisky.visibility = View.GONE
            btnPrimary.setText(R.string.wifi_v4_btn_connect)
            btnPrimary.setIconResource(R.drawable.ic_glyph_wifi)
            btnPrimary.setOnClickListener { openWifiSettings() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Trusted-network toggle
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Offer "Trust this network" for encrypted APs (and any already-trusted one). We
     * deliberately never offer to trust an Open or WEP network — those are unsafe
     * regardless of evil-twin status, so trusting them would be a footgun.
     */
    private fun renderTrust(net: WifiNetwork) {
        val eligible = net.trusted ||
            (net.authType != WifiSecurityAnalyzer.AuthType.OPEN &&
                net.authType != WifiSecurityAnalyzer.AuthType.WEP)
        if (!eligible) {
            btnTrust.visibility = View.GONE
            return
        }
        btnTrust.visibility = View.VISIBLE
        btnTrust.setText(if (net.trusted) R.string.wifi_v4_btn_untrust else R.string.wifi_v4_btn_trust)
        val tint = ContextCompat.getColor(this, if (net.trusted) R.color.v4_ok else R.color.v4_fg1)
        btnTrust.setIconTint(android.content.res.ColorStateList.valueOf(tint))
        btnTrust.setOnClickListener { toggleTrust(net) }
    }

    private fun toggleTrust(net: WifiNetwork) {
        val newTrusted = !net.trusted
        PreferencesManager.setWifiBssidTrusted(this, net.bssid, newTrusted)
        Toast.makeText(
            this,
            if (newTrusted) R.string.wifi_v4_toast_trusted else R.string.wifi_v4_toast_untrusted,
            Toast.LENGTH_SHORT
        ).show()
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                WifiSecurityAnalyzer.scanNearby(this@WifiNetworkDetailActivity)
            }
            val updated = if (net.connected) {
                fresh.connected
            } else {
                fresh.nearby.firstOrNull { it.bssid.equals(net.bssid, ignoreCase = true) }
            }
            val result = updated ?: WifiSecurityAnalyzer.recompute(net, newTrusted)
            network = result
            render(result)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Severity / grade styling (ported from WifiSecurityActivity)
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

    private fun gradeColorRes(grade: Grade): Int = when (grade) {
        Grade.EXCELLENT, Grade.GOOD -> R.color.v4_ok
        Grade.FAIR -> R.color.v4_accent
        Grade.POOR -> R.color.v4_warn
        Grade.CRITICAL -> R.color.v4_bad
    }

    private fun gradeBadge(grade: Grade): Pair<String, Int> = when (grade) {
        Grade.EXCELLENT, Grade.GOOD ->
            getString(R.string.wifi_v4_grade_good) to R.drawable.bg_v4_perm_pill_ok
        Grade.FAIR ->
            getString(R.string.wifi_v4_grade_fair) to R.drawable.bg_v4_perm_pill_accent
        Grade.POOR ->
            getString(R.string.wifi_v4_grade_poor) to R.drawable.bg_v4_perm_pill_warn
        Grade.CRITICAL ->
            getString(R.string.wifi_v4_grade_critical) to R.drawable.bg_v4_perm_pill_bad
    }

    companion object {
        const val EXTRA_NETWORK = "extra_wifi_network"

        fun start(context: Context, net: WifiNetwork) {
            context.startActivity(
                Intent(context, WifiNetworkDetailActivity::class.java)
                    .putExtra(EXTRA_NETWORK, net)
            )
            if (context is android.app.Activity) {
                @Suppress("DEPRECATION")
                context.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }
    }
}
