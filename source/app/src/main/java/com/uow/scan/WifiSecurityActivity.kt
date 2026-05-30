package com.uow.scan

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.uow.scan.adapter.WifiNetworkAdapter
import com.uow.scan.ui.home.widget.RadarPulseView
import com.uow.scan.ui.home.widget.WifiScoreGaugeView
import com.uow.scan.util.WifiNetwork
import com.uow.scan.util.WifiSecurityAnalyzer
import com.uow.scan.util.WifiSecurityAnalyzer.Grade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wi-Fi Security OVERVIEW (S'CAN V4). The connected network is a tappable hero; below
 * it a threat banner (only when a nearby access point is impersonating a network — an
 * evil twin) and the nearby-networks list, sorted by signal or risk. Tapping any network
 * opens
 * [WifiNetworkDetailActivity]. Refresh runs a short scanning animation.
 *
 * Nearby data is a live cached scan with a representative sample fallback (see
 * [WifiSecurityAnalyzer.scanNearby]).
 */
class WifiSecurityActivity : AppCompatActivity() {

    private enum class SortMode { SIGNAL, RISK }

    private lateinit var btnBack: View
    private lateinit var btnRescanTop: View
    private lateinit var ivRescanIcon: ImageView
    private lateinit var tvTopSubtitle: TextView

    private lateinit var cardPermission: LinearLayout
    private lateinit var btnGrantPermission: MaterialButton

    private lateinit var heroCard: LinearLayout
    private lateinit var heroDisconnected: View
    private lateinit var heroGauge: WifiScoreGaugeView
    private lateinit var tvHeroScore: TextView
    private lateinit var tvHeroSsid: TextView
    private lateinit var tvHeroGrade: TextView
    private lateinit var tvHeroSummary: TextView

    private lateinit var threatBanner: View
    private lateinit var tvThreatTitle: TextView
    private lateinit var tvThreatBody: TextView

    private lateinit var tvNearbyHeader: TextView
    private lateinit var sortControls: View
    private lateinit var btnSortSignal: TextView
    private lateinit var btnSortRisk: TextView

    private lateinit var rvNearby: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var scanningState: View
    private lateinit var radar: RadarPulseView
    private lateinit var tvLastScan: TextView

    private lateinit var adapter: WifiNetworkAdapter

    private var sort = SortMode.SIGNAL
    private var scanning = false
    private var lastResult: WifiSecurityAnalyzer.NearbyResult? = null

    private val requestPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadNetworks() }

    /**
     * Passive live scanning: Android delivers [WifiManager.SCAN_RESULTS_AVAILABLE_ACTION]
     * whenever the system completes one of its periodic scans (the app stays read-only —
     * it never calls startScan()). When fresh results land while we're not already mid
     * scan-animation, re-read and re-render so the nearby list updates on its own.
     */
    private val scanResultsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!scanning) loadNetworks()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_security)
        bindViews()
        setupListeners()
        rvNearby.layoutManager = LinearLayoutManager(this)
        adapter = WifiNetworkAdapter { net -> WifiNetworkDetailActivity.start(this, net) }
        rvNearby.adapter = adapter
        rvNearby.isNestedScrollingEnabled = false
        updateSortChips()
    }

    override fun onResume() {
        super.onResume()
        if (!scanning) loadNetworks()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            scanResultsReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(scanResultsReceiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered — ignore
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        btnRescanTop = findViewById(R.id.btnRescanTop)
        ivRescanIcon = findViewById(R.id.ivRescanIcon)
        tvTopSubtitle = findViewById(R.id.tvTopSubtitle)

        cardPermission = findViewById(R.id.cardPermission)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)

        heroCard = findViewById(R.id.heroCard)
        heroDisconnected = findViewById(R.id.heroDisconnected)
        heroGauge = findViewById(R.id.heroGauge)
        tvHeroScore = findViewById(R.id.tvHeroScore)
        tvHeroSsid = findViewById(R.id.tvHeroSsid)
        tvHeroGrade = findViewById(R.id.tvHeroGrade)
        tvHeroSummary = findViewById(R.id.tvHeroSummary)

        threatBanner = findViewById(R.id.threatBanner)
        tvThreatTitle = findViewById(R.id.tvThreatTitle)
        tvThreatBody = findViewById(R.id.tvThreatBody)

        tvNearbyHeader = findViewById(R.id.tvNearbyHeader)
        sortControls = findViewById(R.id.sortControls)
        btnSortSignal = findViewById(R.id.btnSortSignal)
        btnSortRisk = findViewById(R.id.btnSortRisk)

        rvNearby = findViewById(R.id.rvNearby)
        tvEmpty = findViewById(R.id.tvEmpty)
        scanningState = findViewById(R.id.scanningState)
        radar = findViewById(R.id.radar)
        tvLastScan = findViewById(R.id.tvLastScan)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
        btnRescanTop.setOnClickListener { startScanAnimation() }
        btnGrantPermission.setOnClickListener { requestScanPermission() }
        btnSortSignal.setOnClickListener { setSort(SortMode.SIGNAL) }
        btnSortRisk.setOnClickListener { setSort(SortMode.RISK) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Loading
    // ─────────────────────────────────────────────────────────────────────

    private fun loadNetworks() {
        updatePermissionCard()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                WifiSecurityAnalyzer.scanNearby(this@WifiSecurityActivity)
            }
            if (!scanning) render(res)
        }
    }

    private fun startScanAnimation() {
        if (scanning) return
        scanning = true
        ivRescanIcon.setColorFilter(ContextCompat.getColor(this, R.color.v4_accent))
        showScanning(true)
        radar.start()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                WifiSecurityAnalyzer.scanNearby(this@WifiSecurityActivity)
            }
            delay(1900)
            scanning = false
            radar.stop()
            ivRescanIcon.setColorFilter(ContextCompat.getColor(this@WifiSecurityActivity, R.color.v4_fg2))
            showScanning(false)
            updatePermissionCard()
            render(res)
        }
    }

    private fun showScanning(active: Boolean) {
        scanningState.visibility = if (active) View.VISIBLE else View.GONE
        sortControls.visibility = if (active) View.INVISIBLE else View.VISIBLE
        tvLastScan.visibility = if (active) View.GONE else View.VISIBLE
        if (active) {
            rvNearby.visibility = View.GONE
            tvEmpty.visibility = View.GONE
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Rendering
    // ─────────────────────────────────────────────────────────────────────

    private fun render(res: WifiSecurityAnalyzer.NearbyResult) {
        lastResult = res
        renderHero(res.connected)
        renderThreatBanner(res)

        tvNearbyHeader.text = getString(R.string.wifi_v4_nearby_header, res.nearby.size)
        tvTopSubtitle.text = getString(R.string.wifi_v4_networks_in_range, res.nearby.size)

        adapter.submitList(sortNetworks(res.nearby))
        val empty = res.nearby.isEmpty()
        rvNearby.visibility = if (empty) View.GONE else View.VISIBLE
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE

        tvLastScan.setText(R.string.wifi_v4_overview_footer)
    }

    private fun renderHero(connected: WifiNetwork?) {
        if (connected == null) {
            heroCard.visibility = View.GONE
            heroDisconnected.visibility = View.VISIBLE
            return
        }
        heroCard.visibility = View.VISIBLE
        heroDisconnected.visibility = View.GONE

        val gradeColorRes = gradeColorRes(connected.grade)
        tvHeroScore.text = connected.score.toString()
        heroGauge.setScore(connected.score, gradeColorRes)
        tvHeroSsid.text = connected.ssid

        val (gradeLabel, gradeBgRes) = gradeBadge(connected.grade)
        tvHeroGrade.text = gradeLabel
        tvHeroGrade.setTextColor(ContextCompat.getColor(this, gradeColorRes))
        tvHeroGrade.setBackgroundResource(gradeBgRes)

        tvHeroSummary.text =
            "${WifiSecurityAnalyzer.authShortLabel(connected.authType)} · ${connected.rssiDbm} dBm"

        heroCard.setOnClickListener { WifiNetworkDetailActivity.start(this, connected) }
    }

    /**
     * The top-of-screen alert is reserved for the one nearby situation that actually
     * concerns the user: an *evil twin* — a rogue access point copying a network name to
     * lure devices into connecting. A nearby open or weakly-encrypted AP is just that
     * network's own posture (the user isn't connected to it), so it never raises an alert
     * here; flagging every café / guest / printer network would only cause needless worry.
     */
    private fun renderThreatBanner(res: WifiSecurityAnalyzer.NearbyResult) {
        val evilTwins = res.nearby.filter { it.evilTwin }
        if (evilTwins.isEmpty()) {
            threatBanner.visibility = View.GONE
            return
        }
        threatBanner.visibility = View.VISIBLE
        tvThreatTitle.text =
            resources.getQuantityString(R.plurals.wifi_v4_threat_title, evilTwins.size, evilTwins.size)
        tvThreatBody.setText(R.string.wifi_v4_threat_evil)
        threatBanner.setOnClickListener { WifiNetworkDetailActivity.start(this, evilTwins.first()) }
    }

    private fun sortNetworks(list: List<WifiNetwork>): List<WifiNetwork> = when (sort) {
        SortMode.SIGNAL -> list.sortedByDescending { it.rssiDbm }
        SortMode.RISK -> list.sortedBy { it.score }
    }

    private fun setSort(mode: SortMode) {
        if (sort == mode) return
        sort = mode
        updateSortChips()
        lastResult?.let { adapter.submitList(sortNetworks(it.nearby)) }
    }

    private fun updateSortChips() {
        styleSortChip(btnSortSignal, sort == SortMode.SIGNAL)
        styleSortChip(btnSortRisk, sort == SortMode.RISK)
    }

    private fun styleSortChip(chip: TextView, active: Boolean) {
        if (active) {
            chip.setBackgroundResource(R.drawable.bg_v4_perm_pill_accent)
            chip.setTextColor(ContextCompat.getColor(this, R.color.v4_accent))
        } else {
            chip.background = null
            chip.setTextColor(ContextCompat.getColor(this, R.color.v4_fg3))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Permissions (unchanged entry point for nearby scanning)
    // ─────────────────────────────────────────────────────────────────────

    private fun updatePermissionCard() {
        cardPermission.visibility = if (missingPermissions().isEmpty()) View.GONE else View.VISIBLE
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
            loadNetworks()
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
    // Grade styling
    // ─────────────────────────────────────────────────────────────────────

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
}
