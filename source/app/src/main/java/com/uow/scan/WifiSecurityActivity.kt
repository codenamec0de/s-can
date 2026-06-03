package com.uow.scan

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import com.uow.scan.util.WifiActiveTests
import com.uow.scan.util.WifiNetwork
import com.uow.scan.util.WifiSecurityAnalyzer
import com.uow.scan.util.WifiSecurityAnalyzer.Grade
import com.uow.scan.vpn.ScanDnsVpnService
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

    // Active verification (live safety tests) state
    private lateinit var wifiTestsCard: View
    private lateinit var wifiTestsSub: TextView
    private lateinit var wifiTestsRerun: TextView
    private lateinit var wifiShieldCard: View
    private lateinit var wifiShieldCta: View
    private lateinit var wifiShieldArmed: View
    private lateinit var wifiShieldBtn: MaterialButton
    private lateinit var wifiShieldOff: View
    private var connectedNet: WifiNetwork? = null
    private var activeReport: WifiActiveTests.Report? = null
    private var testsRunning = false
    private var testedBssid: String? = null

    /** OS VPN-consent result → bring the Shield (DoH + monitor) tunnel up. */
    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r -> if (r.resultCode == RESULT_OK) armShield() }

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

        wifiTestsCard = findViewById(R.id.wifiTestsCard)
        wifiTestsSub = findViewById(R.id.wifiTestsSub)
        wifiTestsRerun = findViewById(R.id.wifiTestsRerun)
        wifiShieldCard = findViewById(R.id.wifiShieldCard)
        wifiShieldCta = findViewById(R.id.wifiShieldCta)
        wifiShieldArmed = findViewById(R.id.wifiShieldArmed)
        wifiShieldBtn = findViewById(R.id.wifiShieldBtn)
        wifiShieldOff = findViewById(R.id.wifiShieldOff)

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
        wifiTestsRerun.setOnClickListener { connectedNet?.let { runActiveTests(it, force = true) } }
        wifiShieldBtn.setOnClickListener { requestShield() }
        wifiShieldOff.setOnClickListener {
            ScanDnsVpnService.stop(this)
            connectedNet?.let { refreshShieldUi(it); runActiveTests(it, force = true) }   // #1: re-test the now-exposed network
        }
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
        manageActiveLayer(res.connected)
        renderThreatBanner(res)

        tvNearbyHeader.text = getString(R.string.wifi_v4_nearby_header, res.nearby.size)
        tvTopSubtitle.text = getString(R.string.wifi_v4_networks_in_range, res.nearby.size)

        adapter.submitList(sortNetworks(res.nearby))
        val empty = res.nearby.isEmpty()
        rvNearby.visibility = if (empty) View.GONE else View.VISIBLE
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE

        tvLastScan.setText(R.string.wifi_v4_overview_footer)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Active verification — live safety tests, reactive score, Shield
    // ─────────────────────────────────────────────────────────────────────

    /** Show/refresh the active layer for the connected network; auto-run tests on a new network. */
    private fun manageActiveLayer(connected: WifiNetwork?) {
        connectedNet = connected
        if (connected == null) {
            wifiTestsCard.visibility = View.GONE
            wifiShieldCard.visibility = View.GONE
            testedBssid = null
            return
        }
        when {
            testsRunning -> wifiTestsCard.visibility = View.VISIBLE
            connected.bssid != testedBssid || activeReport == null -> runActiveTests(connected, force = false)
            else -> {
                wifiTestsCard.visibility = View.VISIBLE
                applyReport(activeReport!!)
                applyDockedScore(connected, activeReport!!)
            }
        }
        refreshShieldUi(connected)
    }

    private fun runActiveTests(net: WifiNetwork, force: Boolean) {
        if (testsRunning) return
        if (!force && net.bssid == testedBssid && activeReport != null) { wifiTestsCard.visibility = View.VISIBLE; return }
        testsRunning = true
        testedBssid = net.bssid
        wifiTestsCard.visibility = View.VISIBLE
        wifiTestsRerun.visibility = View.GONE
        wifiTestsSub.text = "Testing on-device…"
        setTestRow("dns", running = true, result = null)
        setTestRow("tls", running = true, result = null)
        setTestRow("cap", running = true, result = null)
        val wifiNet = underlyingWifiNetwork()   // #3: resolve DNS over the network, not our tunnel
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { WifiActiveTests.run(wifiNet) }
            testsRunning = false
            activeReport = report
            if (connectedNet?.bssid == net.bssid) {
                applyReport(report)
                applyDockedScore(net, report)
                refreshShieldUi(net)
            }
        }
    }

    private fun applyReport(report: WifiActiveTests.Report) {
        // #2: while shielded, lookups are encrypted by us — show the DNS row as Protected rather
        // than reporting on the network we're no longer exposed to.
        if (isShielded()) setDnsProtectedRow() else setTestRow("dns", running = false, result = report.dns)
        setTestRow("tls", running = false, result = report.tls)
        setTestRow("cap", running = false, result = report.captive)
        wifiTestsSub.text = "on-device · tested just now"
        wifiTestsRerun.visibility = View.VISIBLE
    }

    /** DNS row in the "Protected by Shield" state (#2). */
    private fun setDnsProtectedRow() {
        val ids = rowIds("dns")
        findViewById<ProgressBar>(ids[2]).visibility = View.GONE
        val icon = findViewById<ImageView>(ids[1])
        icon.visibility = View.VISIBLE
        icon.setImageResource(R.drawable.ic_glyph_shield); icon.imageTintList = csl(R.color.v4_ok)
        findViewById<FrameLayout>(ids[0]).background = tileBg(c(R.color.v4_ok_bg), withAlpha(c(R.color.v4_ok), 0x44), 9f)
        val status = findViewById<TextView>(ids[3])
        status.text = "Protected — DNS encrypted by Shield"; status.setTextColor(c(R.color.v4_ok))
        val chip = findViewById<TextView>(ids[4])
        chip.visibility = View.VISIBLE; chip.text = "Shielded"; chip.setTextColor(c(R.color.v4_ok))
        chip.background = tileBg(c(R.color.v4_ok_bg), withAlpha(c(R.color.v4_ok), 0x33), 5f)
    }

    private fun isShielded(): Boolean = ScanDnsVpnService.tunnelUp

    /** The underlying Wi-Fi transport (never the VPN), so the DNS probe tests the real network (#3). */
    private fun underlyingWifiNetwork(): android.net.Network? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps != null &&
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun setTestRow(which: String, running: Boolean, result: WifiActiveTests.Result?) {
        val ids = rowIds(which)
        val tile = findViewById<FrameLayout>(ids[0])
        val icon = findViewById<ImageView>(ids[1])
        val spin = findViewById<ProgressBar>(ids[2])
        val status = findViewById<TextView>(ids[3])
        val chip = findViewById<TextView>(ids[4])
        if (running) {
            spin.visibility = View.VISIBLE; icon.visibility = View.GONE
            tile.background = tileBg(c(R.color.v4_surf3), c(R.color.v4_hairline), 9f)
            status.text = "Testing…"; status.setTextColor(c(R.color.v4_fg3))
            chip.visibility = View.GONE
            return
        }
        if (result == null) return
        spin.visibility = View.GONE; icon.visibility = View.VISIBLE
        val colorRes = when (result) {
            WifiActiveTests.Result.PASS -> R.color.v4_ok
            WifiActiveTests.Result.FAIL -> R.color.v4_bad
            WifiActiveTests.Result.INCONCLUSIVE -> R.color.v4_warn
        }
        val bgRes = when (result) {
            WifiActiveTests.Result.PASS -> R.color.v4_ok_bg
            WifiActiveTests.Result.FAIL -> R.color.v4_bad_bg
            WifiActiveTests.Result.INCONCLUSIVE -> R.color.v4_warn_bg
        }
        val resIcon = when (result) {
            WifiActiveTests.Result.PASS -> R.drawable.ic_glyph_check
            WifiActiveTests.Result.FAIL -> R.drawable.ic_glyph_block
            WifiActiveTests.Result.INCONCLUSIVE -> R.drawable.ic_glyph_warn
        }
        val word = when (result) {
            WifiActiveTests.Result.PASS -> "Pass"
            WifiActiveTests.Result.FAIL -> "Fail"
            WifiActiveTests.Result.INCONCLUSIVE -> "Inconclusive"
        }
        icon.setImageResource(resIcon); icon.imageTintList = csl(colorRes)
        tile.background = tileBg(c(bgRes), withAlpha(c(colorRes), 0x44), 9f)
        status.text = copyFor(which, result); status.setTextColor(c(colorRes))
        chip.visibility = View.VISIBLE; chip.text = word; chip.setTextColor(c(colorRes))
        chip.background = tileBg(c(bgRes), withAlpha(c(colorRes), 0x33), 5f)
    }

    private fun rowIds(which: String): IntArray = when (which) {
        "dns" -> intArrayOf(R.id.wifiTestDnsTile, R.id.wifiTestDnsIcon, R.id.wifiTestDnsSpin, R.id.wifiTestDnsStatus, R.id.wifiTestDnsChip)
        "tls" -> intArrayOf(R.id.wifiTestTlsTile, R.id.wifiTestTlsIcon, R.id.wifiTestTlsSpin, R.id.wifiTestTlsStatus, R.id.wifiTestTlsChip)
        else -> intArrayOf(R.id.wifiTestCapTile, R.id.wifiTestCapIcon, R.id.wifiTestCapSpin, R.id.wifiTestCapStatus, R.id.wifiTestCapChip)
    }

    private fun copyFor(which: String, r: WifiActiveTests.Result): String = when (which) {
        "dns" -> when (r) {
            WifiActiveTests.Result.PASS -> "DNS answers are honest"
            WifiActiveTests.Result.FAIL -> "This network is redirecting DNS"
            WifiActiveTests.Result.INCONCLUSIVE -> "Finish the Wi-Fi login to test"
        }
        "tls" -> when (r) {
            WifiActiveTests.Result.PASS -> "No interception detected"
            WifiActiveTests.Result.FAIL -> "A proxy is intercepting HTTPS"
            WifiActiveTests.Result.INCONCLUSIVE -> "Couldn't reach test host"
        }
        else -> when (r) {
            WifiActiveTests.Result.PASS -> "No redirection or injection"
            WifiActiveTests.Result.FAIL -> "Content is being injected"
            WifiActiveTests.Result.INCONCLUSIVE -> "Captive portal active"
        }
    }

    /** The gauge reacts to live verification: passing probes + the Shield (DoH) *raise* the passive
     *  score (real-time protection the crypto scan can't see), while tested tampering docks it. */
    private fun applyDockedScore(net: WifiNetwork, report: WifiActiveTests.Report) {
        val shielded = isShielded()
        val adjusted = net.score + report.activeCredit(shielded) - report.scoreDock(shielded)
        val docked = adjusted.coerceIn(if (report.actionableFail(shielded)) 5 else 0, 100)
        val grade = gradeForScore(docked)
        val colorRes = gradeColorRes(grade)
        tvHeroScore.text = docked.toString()
        heroGauge.setScore(docked, colorRes)
        val (label, bgRes) = gradeBadge(grade)
        tvHeroGrade.text = label
        tvHeroGrade.setBackgroundResource(bgRes)
        tvHeroGrade.setTextColor(c(colorRes))
    }

    private fun gradeForScore(s: Int): Grade = when {
        s >= 90 -> Grade.EXCELLENT
        s >= 75 -> Grade.GOOD
        s >= 55 -> Grade.FAIR
        s >= 30 -> Grade.POOR
        else -> Grade.CRITICAL
    }

    private fun refreshShieldUi(net: WifiNetwork) {
        val shielded = ScanDnsVpnService.tunnelUp
        val recommend = shielded || activeReport?.anyFail == true || net.score < 55
        wifiShieldCard.visibility = if (recommend) View.VISIBLE else View.GONE
        wifiShieldCta.visibility = if (shielded) View.GONE else View.VISIBLE
        wifiShieldArmed.visibility = if (shielded) View.VISIBLE else View.GONE
    }

    private fun requestShield() {
        val prep = VpnService.prepare(this)
        if (prep != null) vpnConsent.launch(prep) else armShield()
    }

    private fun armShield() {
        ScanDnsVpnService.startMonitor(this, block = true, encrypt = true, capture = false)
        wifiShieldCard.visibility = View.VISIBLE
        wifiShieldCta.visibility = View.GONE
        wifiShieldArmed.visibility = View.VISIBLE
        // #1: re-test so the DNS row flips to "Protected" and the score un-docks the (now-mitigated)
        // DNS penalty. The DNS probe runs over the underlying network, so it completes after the
        // tunnel is up and reads the shielded state.
        connectedNet?.let { runActiveTests(it, force = true) }
    }

    private fun tileBg(fill: Int, stroke: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusDp * resources.displayMetrics.density
        setColor(fill); setStroke((resources.displayMetrics.density).toInt(), stroke)
    }

    private fun c(res: Int): Int = ContextCompat.getColor(this, res)
    private fun csl(res: Int): ColorStateList = ColorStateList.valueOf(c(res))
    private fun withAlpha(color: Int, a: Int): Int = (a shl 24) or (color and 0xFFFFFF)

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
