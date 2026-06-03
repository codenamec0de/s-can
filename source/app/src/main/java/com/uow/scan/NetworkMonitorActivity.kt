package com.uow.scan

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.net.VpnService
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.uow.scan.ui.home.widget.RadarPulseView
import com.uow.scan.ui.home.widget.SparklineView
import com.uow.scan.ui.home.widget.WifiScoreGaugeView
import com.uow.scan.util.NtmDemoData
import com.uow.scan.util.NtmDemoData.Finding
import com.uow.scan.util.NtmDemoData.FindingIcon
import com.uow.scan.util.NtmDemoData.NtmApp
import com.uow.scan.util.NtmDemoData.Tone
import com.uow.scan.util.NtmBlocklist
import com.uow.scan.util.NtmDataSource
import com.uow.scan.util.NtmDemoData.Dest
import com.uow.scan.util.NtmLiveRepository
import com.uow.scan.util.PreferencesManager
import com.uow.scan.util.ScanDialog
import com.uow.scan.util.TrackerDomainMatcher
import com.uow.scan.vpn.ScanDnsVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Network Traffic Monitor — Overview (S'CAN V4 · Screen A). A stateful screen that shows, per
 * app, where the phone's apps connect, which of those are trackers, and lets the user block
 * them. Phases: off (pre-consent) → starting (consent pending) → live (populated) / empty.
 *
 * Built on the same V4 primitives as the DNS + Wi-Fi screens — [WifiScoreGaugeView] for the
 * posture gauge, [RadarPulseView] for the scan pulse, [SparklineView] for live throughput, and
 * item_wifi_finding rows — so it reads as native to the suite.
 *
 * This is the design-first build: it renders the [NtmDemoData] dataset with full animation. The
 * on-device tunnel + per-app attribution land in a later stage and swap the data source; the
 * Monitor switch / disclosure / consent flow is stubbed to the design's transitions for now.
 *
 * Demo override: long-press the title to cycle off → live → empty for deterministic stage runs.
 */
class NetworkMonitorActivity : AppCompatActivity() {

    private enum class Phase { OFF, STARTING, EMPTY, LIVE }

    private lateinit var phaseOff: View
    private lateinit var phaseStarting: View
    private lateinit var phaseEmpty: View
    private lateinit var phaseLive: View

    private lateinit var btnBack: View
    private lateinit var tvTopTitle: TextView
    private lateinit var tvTopSubtitle: TextView
    private lateinit var livePill: View
    private lateinit var livePillDot: View
    private lateinit var btnTurnOn: View

    private lateinit var startingRadar: RadarPulseView
    private lateinit var emptyRadar: RadarPulseView
    private lateinit var heroRadar: RadarPulseView

    private lateinit var postureGauge: WifiScoreGaugeView
    private lateinit var tvPostureScore: TextView
    private lateinit var gradePill: View
    private lateinit var gradeDot: View
    private lateinit var tvGrade: TextView
    private lateinit var tvPostureLine: TextView
    private lateinit var tvRate: TextView
    private lateinit var sparkline: SparklineView

    private lateinit var tvTileBlocked: TextView
    private lateinit var tvTilePhoning: TextView
    private lateinit var tvTileConns: TextView
    private lateinit var tvTileData: TextView
    private lateinit var tvTileDataSub: TextView

    private lateinit var swMonitor: View
    private lateinit var swBlock: View
    private lateinit var swEncrypt: View
    private lateinit var swAdvanced: View

    private lateinit var findingsContainer: LinearLayout
    private lateinit var appsContainer: LinearLayout
    private lateinit var tvPerAppHeader: TextView
    private lateinit var sortData: TextView
    private lateinit var sortTrackers: TextView
    private lateinit var sortRecent: TextView
    private lateinit var filterAll: TextView
    private lateinit var filterTrackers: TextView
    private lateinit var filterBackground: TextView

    private var phase = Phase.OFF
    private var monitor = false
    private var blocking = true
    private var encDns = true
    private var advanced = false
    private var sort = "data"
    private var filter = "all"

    private val spark = mutableListOf(3f, 5f, 4f, 7f, 6f, 9f, 7f, 11f, 8f, 6f, 9f, 12f, 10f, 8f)
    private var tickJob: Job? = null
    private var pulse: ValueAnimator? = null

    /** OS VPN-consent result → bring the real tunnel up; denial returns the screen to OFF. */
    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) beginTunnel()
        else { monitor = false; showPhase(Phase.OFF) }
    }

    private var liveRepo: NtmLiveRepository? = null
    private var dataJob: Job? = null

    /** Real data while the tunnel is up (unless the demo override is on), else the demo dataset.
     *  Cached per-instance; [refreshLiveData] drops it so the next read recomputes. */
    private fun source(): NtmDataSource =
        if (PreferencesManager.isNetMonActive(this) && !PreferencesManager.isNetMonDemoMode(this))
            (liveRepo ?: NtmLiveRepository(this).also { liveRepo = it })
        else NtmDemoData

    private fun refreshLiveData() { liveRepo = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_monitor)
        bindViews()
        setupSwitchLabels()
        setupListeners()
        syncFromTunnel(initial = true)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    override fun onResume() {
        super.onResume()
        syncFromTunnel(initial = false)
    }

    /** Restore the screen to the real tunnel state on entry/resume — re-opening NTM while it is
     *  already running now shows LIVE, not the "Turn on" screen (the bug: onCreate always OFF).
     *  Toggle states are read back from prefs so they reflect the running config. */
    private fun syncFromTunnel(initial: Boolean) {
        if (phase == Phase.STARTING) { applyPhaseAnimations(); return }  // a consent/start is in flight
        // Liveness truth = the service is actually running in THIS process. A persisted
        // isNetMonActive with no live service (after a kill/reinstall) is stale → clear it.
        val running = ScanDnsVpnService.tunnelUp
        if (!running && PreferencesManager.isNetMonActive(this)) PreferencesManager.setNetMonActive(this, false)
        val active = running && PreferencesManager.isNetMonActive(this)
        val demo = PreferencesManager.isNetMonDemoMode(this)
        when {
            active -> {
                monitor = true
                blocking = PreferencesManager.isNetMonBlockingEnabled(this)
                encDns = PreferencesManager.isNetMonEncryptEnabled(this)
                advanced = PreferencesManager.isNetMonCaptureEnabled(this)
                if (phase != Phase.LIVE) {
                    showPhase(Phase.LIVE)                 // renderLive() refreshes + renders
                } else {
                    refreshLiveData()                      // resuming an already-LIVE screen → recompute now
                    renderDynamic(animateGauge = false)
                    applyPhaseAnimations()
                }
            }
            demo -> {
                monitor = true
                if (phase != Phase.LIVE && phase != Phase.EMPTY) showPhase(Phase.LIVE) else applyPhaseAnimations()
            }
            else -> {
                monitor = false
                if (phase != Phase.OFF) showPhase(Phase.OFF) else applyPhaseAnimations()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopAnimations()
    }

    private fun bindViews() {
        phaseOff = findViewById(R.id.phaseOff)
        phaseStarting = findViewById(R.id.phaseStarting)
        phaseEmpty = findViewById(R.id.phaseEmpty)
        phaseLive = findViewById(R.id.phaseLive)

        btnBack = findViewById(R.id.btnBack)
        tvTopTitle = findViewById(R.id.tvTopTitle)
        tvTopSubtitle = findViewById(R.id.tvTopSubtitle)
        livePill = findViewById(R.id.livePill)
        livePillDot = findViewById(R.id.livePillDot)
        btnTurnOn = findViewById(R.id.btnTurnOn)

        startingRadar = findViewById(R.id.startingRadar)
        emptyRadar = findViewById(R.id.emptyRadar)
        heroRadar = findViewById(R.id.heroRadar)

        postureGauge = findViewById(R.id.postureGauge)
        tvPostureScore = findViewById(R.id.tvPostureScore)
        gradePill = findViewById(R.id.gradePill)
        gradeDot = findViewById(R.id.gradeDot)
        tvGrade = findViewById(R.id.tvGrade)
        tvPostureLine = findViewById(R.id.tvPostureLine)
        tvRate = findViewById(R.id.tvRate)
        sparkline = findViewById(R.id.sparkline)

        tvTileBlocked = findViewById(R.id.tvTileBlocked)
        tvTilePhoning = findViewById(R.id.tvTilePhoning)
        tvTileConns = findViewById(R.id.tvTileConns)
        tvTileData = findViewById(R.id.tvTileData)
        tvTileDataSub = findViewById(R.id.tvTileDataSub)

        swMonitor = findViewById(R.id.swMonitor)
        swBlock = findViewById(R.id.swBlock)
        swEncrypt = findViewById(R.id.swEncrypt)
        swAdvanced = findViewById(R.id.swAdvanced)

        findingsContainer = findViewById(R.id.findingsContainer)
        appsContainer = findViewById(R.id.appsContainer)
        tvPerAppHeader = findViewById(R.id.tvPerAppHeader)
        sortData = findViewById(R.id.sortData)
        sortTrackers = findViewById(R.id.sortTrackers)
        sortRecent = findViewById(R.id.sortRecent)
        filterAll = findViewById(R.id.filterAll)
        filterTrackers = findViewById(R.id.filterTrackers)
        filterBackground = findViewById(R.id.filterBackground)
    }

    private fun setupSwitchLabels() {
        bindSwitchLabel(swMonitor, R.drawable.ic_glyph_activity, R.string.ntm_sw_monitor, R.string.ntm_sw_monitor_sub)
        bindSwitchLabel(swBlock, R.drawable.ic_glyph_block, R.string.ntm_sw_block, R.string.ntm_sw_block_sub)
        bindSwitchLabel(swEncrypt, R.drawable.ic_glyph_lock, R.string.ntm_sw_encrypt, R.string.ntm_sw_encrypt_sub)
        bindSwitchLabel(swAdvanced, R.drawable.ic_glyph_eye, R.string.ntm_sw_advanced, R.string.ntm_sw_advanced_sub)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish(); slideBack() }
        btnTurnOn.setOnClickListener { turnOn() }
        tvTopTitle.setOnLongClickListener { cycleDemo(); true }

        swMonitor.setOnClickListener { if (monitor) turnOff() else turnOn() }
        swBlock.setOnClickListener {
            if (!monitor) return@setOnClickListener
            blocking = !blocking
            PreferencesManager.setNetMonBlockingEnabled(this, blocking)
            renderSwitch(swBlock, blocking, monitor, animate = true)
            renderDynamic(animateGauge = true)
            reconfigureTunnel()
        }
        swEncrypt.setOnClickListener {
            if (!monitor) return@setOnClickListener
            encDns = !encDns
            PreferencesManager.setNetMonEncryptEnabled(this, encDns)
            renderSwitch(swEncrypt, encDns, monitor, animate = true)
            reconfigureTunnel()
        }
        swAdvanced.setOnClickListener {
            // "Show full hostnames" is a detail-view preference (reveal exact domains vs owners).
            // It does NOT enable packet capture — that's the hidden long-press affordance below.
            if (!monitor) return@setOnClickListener
            advanced = !advanced
            PreferencesManager.setNetMonCaptureEnabled(this, advanced)
            renderSwitch(swAdvanced, advanced, monitor, animate = true)
        }
        // Hidden experimental affordance: long-press toggles the Stage-4b full-capture forwarder
        // (routes ALL IPv4 through a userspace relay → real cleartext/SNI/ports/bytes). Off by
        // default + kept off the main UI; applies on the next monitor start.
        swAdvanced.setOnLongClickListener {
            val on = !PreferencesManager.isNetMonForwarderEnabled(this)
            PreferencesManager.setNetMonForwarderEnabled(this, on)
            Toast.makeText(
                this,
                "Experimental full capture ${if (on) "ON" else "OFF"} — turn monitoring off and on to apply",
                Toast.LENGTH_LONG,
            ).show()
            true
        }

        sortData.setOnClickListener { setSort("data") }
        sortTrackers.setOnClickListener { setSort("trackers") }
        sortRecent.setOnClickListener { setSort("recent") }
        filterAll.setOnClickListener { setFilter("all") }
        filterTrackers.setOnClickListener { setFilter("trackers") }
        filterBackground.setOnClickListener { setFilter("background") }

        // Each summary tile opens a breakdown of the number it shows (the tile card is the parent
        // of the value TextView). Only reachable on the LIVE phase, where the tiles are visible.
        (tvTileBlocked.parent as View).setOnClickListener { showBlockedTrackersDialog() }
        (tvTilePhoning.parent as View).setOnClickListener { showPhoningDialog() }
        (tvTileConns.parent as View).setOnClickListener { showConnectionsDialog() }
        (tvTileData.parent as View).setOnClickListener { showDataDialog() }
    }

    // ───────────────────────── phase machine ─────────────────────────

    private fun showPhase(p: Phase) {
        phase = p
        phaseOff.visibility = if (p == Phase.OFF) View.VISIBLE else View.GONE
        phaseStarting.visibility = if (p == Phase.STARTING) View.VISIBLE else View.GONE
        phaseEmpty.visibility = if (p == Phase.EMPTY) View.VISIBLE else View.GONE
        phaseLive.visibility = if (p == Phase.LIVE) View.VISIBLE else View.GONE

        tvTopSubtitle.setText(
            when (p) {
                Phase.OFF -> R.string.ntm_subtitle_off
                Phase.STARTING -> R.string.ntm_subtitle_starting
                else -> R.string.ntm_subtitle_live
            }
        )
        livePill.visibility = if (p == Phase.LIVE) View.VISIBLE else View.GONE

        if (p == Phase.LIVE) renderLive()
        applyPhaseAnimations()
    }

    private fun turnOn() {
        if (monitor) return
        if (PreferencesManager.hasAcceptedNetMonDisclosure(this)) { startMonitoring(); return }
        ScanDialog.confirm(
            this,
            getString(R.string.ntm_disclosure_title),
            getString(R.string.ntm_disclosure_body),
            getString(R.string.ntm_disclosure_accept),
            getString(R.string.ntm_disclosure_cancel),
        ) {
            PreferencesManager.setNetMonDisclosureAccepted(this, true)
            startMonitoring()
        }
    }

    /** Real tunnel start: request OS VPN consent if needed, else bring the tunnel straight up. */
    private fun startMonitoring() {
        val prep = VpnService.prepare(this)
        if (prep != null) {
            monitor = true
            showPhase(Phase.STARTING)
            vpnConsent.launch(prep)
        } else {
            beginTunnel()
        }
    }

    private fun beginTunnel() {
        monitor = true
        PreferencesManager.setNetMonDemoMode(this, false)
        showPhase(Phase.STARTING)
        Toast.makeText(this, R.string.ntm_active_toast, Toast.LENGTH_SHORT).show()
        PreferencesManager.setNetMonBlockingEnabled(this, blocking)
        PreferencesManager.setNetMonEncryptEnabled(this, encDns)
        PreferencesManager.setNetMonCaptureEnabled(this, advanced)
        ScanDnsVpnService.startMonitor(this, block = blocking, encrypt = encDns, capture = PreferencesManager.isNetMonForwarderEnabled(this))
        lifecycleScope.launch(Dispatchers.IO) { TrackerDomainMatcher.warmUp(applicationContext) }
        lifecycleScope.launch {
            val up = awaitNetMonActive(target = true, timeoutMs = 8000)
            if (isFinishing || !monitor) return@launch
            if (up) {
                showPhase(Phase.LIVE)
            } else {
                monitor = false
                showPhase(Phase.OFF)
                Toast.makeText(this@NetworkMonitorActivity, R.string.ntm_stopped_toast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun turnOff() {
        monitor = false
        PreferencesManager.setNetMonDemoMode(this, false)
        ScanDnsVpnService.stop(this)
        Toast.makeText(this, R.string.ntm_stopped_toast, Toast.LENGTH_SHORT).show()
        showPhase(Phase.OFF)
    }

    /** Re-send the current toggle config to the running tunnel (reconfigures in place). */
    private fun reconfigureTunnel() {
        if (monitor) ScanDnsVpnService.startMonitor(this, block = blocking, encrypt = encDns, capture = advanced)
    }

    /** Poll the active flag the VpnService flips on establish/teardown, bounded by [timeoutMs]. */
    private suspend fun awaitNetMonActive(target: Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (PreferencesManager.isNetMonActive(this) == target) return true
            delay(120)
        }
        return PreferencesManager.isNetMonActive(this) == target
    }

    private fun cycleDemo() {
        val target = when (phase) {
            Phase.OFF, Phase.STARTING -> Phase.LIVE
            Phase.LIVE -> Phase.EMPTY
            Phase.EMPTY -> Phase.OFF
        }
        monitor = target != Phase.OFF
        PreferencesManager.setNetMonDemoMode(this, target != Phase.OFF)
        showPhase(target)
        Toast.makeText(this, R.string.ntm_demo_loaded, Toast.LENGTH_SHORT).show()
    }

    // ───────────────────────── live rendering ─────────────────────────

    private fun renderLive() {
        refreshLiveData()
        renderSwitch(swMonitor, monitor, enabled = true, animate = false)
        renderSwitch(swBlock, blocking, enabled = monitor, animate = false)
        renderSwitch(swEncrypt, encDns, enabled = monitor, animate = false)
        renderSwitch(swAdvanced, advanced, enabled = monitor, animate = false)
        styleSortChips()
        styleFilterChips()
        renderDynamic(animateGauge = true)
    }

    /** The parts that depend on the blocking toggle: posture gauge, tiles, findings, app list. */
    private fun renderDynamic(animateGauge: Boolean) {
        val src = source()
        val posture = src.posture(blocking)
        val toneColor = color(toneColorRes(posture.tone))
        if (animateGauge) animateGauge(posture.score, toneColorRes(posture.tone))
        else {
            postureGauge.setScore(posture.score, toneColorRes(posture.tone))
            tvPostureScore.text = posture.score.toString()
        }
        gradePill.background = pillBg(toneBgColor(posture.tone), withAlpha(toneColor, 0x33), 6)
        gradeDot.backgroundTintList = ColorStateList.valueOf(toneColor)
        tvGrade.text = posture.grade
        tvGrade.setTextColor(toneColor)
        tvPostureLine.text = posture.line

        val agg = src.agg(blocking)
        tvTileBlocked.text = agg.trackersBlocked.toString()
        tvTilePhoning.text = agg.phoningHome.toString()
        tvTileConns.text = agg.connections.toString()
        tvTileData.text = NtmDemoData.fmtBytes(agg.dataKb)
        tvTileDataSub.text = getString(
            R.string.ntm_tile_data_sub_fmt,
            NtmDemoData.fmtBytes(agg.wifiKb), NtmDemoData.fmtBytes(agg.mobileKb),
        )

        sparkline.setColor(toneColor)
        sparkline.setData(spark.toFloatArray())

        renderFindings(src)
        renderApps(src)
    }

    private fun renderFindings(src: NtmDataSource) {
        findingsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (f in src.findings(blocking)) {
            val row = inflater.inflate(R.layout.item_wifi_finding, findingsContainer, false)
            bindFinding(row, f)
            findingsContainer.addView(row)
        }
    }

    private fun bindFinding(row: View, f: Finding) {
        val colorRes = toneColorRes(f.tone)
        val tile = row.findViewById<FrameLayout>(R.id.sevTile)
        val icon = row.findViewById<ImageView>(R.id.sevIcon)
        val pill = row.findViewById<TextView>(R.id.tvSeverityPill)
        tile.setBackgroundResource(toneTileBg(f.tone))
        icon.setImageResource(findingIconRes(f.icon))
        icon.setColorFilter(color(colorRes))
        pill.text = getString(tonePillLabel(f.tone))
        pill.setTextColor(color(colorRes))
        pill.setBackgroundResource(tonePillBg(f.tone))

        row.findViewById<TextView>(R.id.tvFindingTitle).text = f.title
        row.findViewById<TextView>(R.id.tvFindingBody).text = f.desc

        val fixBox = row.findViewById<LinearLayout>(R.id.fixBox)
        val tvFix = row.findViewById<TextView>(R.id.tvFindingFix)
        if (f.cta != null) {
            fixBox.visibility = View.VISIBLE
            tvFix.text = f.cta
            if (f.appId != null) {
                fixBox.isClickable = true
                fixBox.setOnClickListener {
                    if (f.appId == NtmDemoData.FINDING_BLOCKED) showBlockedTrackersDialog()
                    else openApp(f.appId)
                }
            } else {
                fixBox.isClickable = false
                fixBox.setOnClickListener(null)
            }
        } else {
            fixBox.visibility = View.GONE
        }
    }

    private fun renderApps(src: NtmDataSource) {
        appsContainer.removeAllViews()
        val all = src.apps()
        val maxData = (all.maxOfOrNull { NtmDemoData.appStats(it).total } ?: 1L).coerceAtLeast(1L)
        var apps = all
        when (filter) {
            "trackers" -> apps = apps.filter { NtmDemoData.appStats(it).trackerDests.isNotEmpty() }
            "background" -> apps = apps.filter { it.bg >= 20480 }
        }
        apps = when (sort) {
            "trackers" -> apps.sortedByDescending { NtmDemoData.appStats(it).trackerDests.size }
            "recent" -> apps
            else -> apps.sortedByDescending { NtmDemoData.appStats(it).total }
        }
        tvPerAppHeader.text = getString(R.string.ntm_per_app_header_fmt, apps.size)

        val inflater = LayoutInflater.from(this)
        for (app in apps) {
            val row = inflater.inflate(R.layout.item_ntm_app, appsContainer, false)
            bindApp(row, app, maxData)
            appsContainer.addView(row)
        }
    }

    private fun bindApp(row: View, app: NtmApp, maxData: Long) {
        val s = NtmDemoData.appStats(app)
        val brand = parseColor(app.brand, color(R.color.v4_accent))

        val tile = row.findViewById<FrameLayout>(R.id.appTile)
        val mono = row.findViewById<TextView>(R.id.appMono)
        val iconView = row.findViewById<ImageView>(R.id.appIcon)
        val icon = appIconFor(app.pkg)
        if (icon != null) {
            iconView.setImageDrawable(icon)
            iconView.visibility = View.VISIBLE
            mono.visibility = View.GONE
            tile.background = pillBg(color(R.color.v4_surf2), color(R.color.v4_hairline2), 11)
        } else {
            iconView.visibility = View.GONE
            mono.visibility = View.VISIBLE
            mono.text = app.mono
            mono.setTextColor(brand)
            tile.background = pillBg(withAlpha(brand, 0x22), withAlpha(brand, 0x44), 11)
        }

        row.findViewById<TextView>(R.id.appName).text = app.name
        row.findViewById<View>(R.id.appCleartext).visibility =
            if (app.cleartext) View.VISIBLE else View.GONE
        row.findViewById<TextView>(R.id.appBytes).text = NtmDemoData.fmtBytes(s.total)
        row.findViewById<TextView>(R.id.appConns).text = getString(R.string.ntm_conns_fmt, app.conns)

        val bgHeavy = app.bg >= app.fg
        val state = row.findViewById<TextView>(R.id.appState)
        state.text = getString(if (bgHeavy) R.string.ntm_bg else R.string.ntm_fg)
        state.setTextColor(color(if (bgHeavy) R.color.v4_warn else R.color.v4_fg3))
        state.background = if (bgHeavy)
            pillBg(color(R.color.v4_warn_bg), withAlpha(color(R.color.v4_warn), 0x33), 4)
        else
            pillBg(color(R.color.v4_surf_tint), color(R.color.v4_hairline2), 4)

        // usage bar
        val pct = maxOf(4f, (s.total.toFloat() / maxData.toFloat()) * 100f).coerceAtMost(100f)
        val fill = row.findViewById<View>(R.id.appBarFill)
        val rest = row.findViewById<View>(R.id.appBarRest)
        (fill.layoutParams as LinearLayout.LayoutParams).weight = pct
        (rest.layoutParams as LinearLayout.LayoutParams).weight = 100f - pct
        fill.setBackgroundColor(if (app.cleartext) color(R.color.v4_bad) else brand)
        fill.requestLayout()

        val badge = row.findViewById<View>(R.id.appTrackerBadge)
        val check = row.findViewById<View>(R.id.appCheck)
        if (s.trackerDests.isNotEmpty()) {
            badge.visibility = View.VISIBLE
            check.visibility = View.GONE
            row.findViewById<TextView>(R.id.appTrackerCount).text = s.trackerDests.size.toString()
        } else {
            badge.visibility = View.GONE
            check.visibility = View.VISIBLE
        }

        row.setOnClickListener { openApp(app.id) }
    }

    private fun openApp(appId: String) {
        NetworkAppDetailActivity.start(this, appId, advanced)
    }

    // ───────────────────────── tile / finding detail dialogs ─────────────────────────

    private fun isLive(): Boolean =
        PreferencesManager.isNetMonActive(this) && !PreferencesManager.isNetMonDemoMode(this)

    /** Same rule the tiles count by — live: allow → user-block → curated while the toggle is on;
     *  demo: the dest's pre-block flag gated by the global toggle. So a dialog never disagrees with
     *  the number on the tile that opened it. */
    private fun isDestBlocked(d: Dest): Boolean = if (isLive()) when {
        PreferencesManager.isNetMonAllowed(this, d.host) -> false
        PreferencesManager.isNetMonUserBlocked(this, d.host) -> true
        else -> blocking && NtmBlocklist.isBlocked(this, d.host)
    } else blocking && d.preBlocked

    /** "Trackers blocked" tile + the blocked-connections finding → which trackers, by company. */
    private fun showBlockedTrackersDialog() {
        val rows = LinkedHashMap<String, Pair<String, Int>>()   // company → (category, count)
        for (app in source().apps()) for (d in app.dests) {
            val key = d.trk ?: continue
            // Live: real block state. Demo: the tile reads "blocked" while blocking is on, so list
            // every tracker company contacted (the demo dataset carries no per-dest block flag).
            val isBlocked = if (isLive()) isDestBlocked(d) else blocking
            if (!isBlocked) continue
            val t = NtmDemoData.tracker(key) ?: continue
            rows[t.name] = t.cat to ((rows[t.name]?.second ?: 0) + 1)
        }
        val sb = SpannableStringBuilder()
        if (rows.isEmpty()) sb.append(getString(R.string.ntm_dlg_blocked_none))
        else {
            appendDim(sb, getString(R.string.ntm_dlg_blocked_intro))
            val sorted = rows.entries.sortedByDescending { it.value.second }
            sorted.take(6).forEach { appendItem(sb, it.key, getString(R.string.ntm_dlg_blocked_line_fmt, it.value.first, it.value.second)) }
            appendMore(sb, sorted.size - 6)
        }
        ScanDialog.notice(this, getString(R.string.ntm_tile_blocked), sb, getString(R.string.ntm_tracker_close))
    }

    /** "Phoning home" tile → which apps are still reaching trackers we're NOT blocking. */
    private fun showPhoningDialog() {
        val lines = source().apps().mapNotNull { app ->
            // Live: only trackers we're NOT blocking. Demo: the tile counts every tracker-contacting
            // app, so list each app's tracker companies regardless of the (flagless) demo block state.
            val open = app.dests.filter { if (isLive()) !isDestBlocked(it) else true }
                .mapNotNull { it.trk?.let { k -> NtmDemoData.tracker(k)?.name } }.distinct()
            if (open.isEmpty()) null else app.name to open.joinToString(", ")
        }
        val sb = SpannableStringBuilder()
        if (lines.isEmpty()) sb.append(getString(R.string.ntm_dlg_phoning_none))
        else {
            appendDim(sb, getString(R.string.ntm_dlg_phoning_intro))
            lines.take(6).forEach { appendItem(sb, it.first, "→ ${it.second}") }
            appendMore(sb, lines.size - 6)
            sb.append("\n\n"); sb.append(getString(R.string.ntm_dlg_phoning_hint))
        }
        ScanDialog.notice(this, getString(R.string.ntm_tile_phoning), sb, getString(R.string.ntm_tracker_close))
    }

    /** "Connections" tile → busiest apps + what the number means. */
    private fun showConnectionsDialog() {
        val apps = source().apps().filter { it.conns > 0 }.sortedByDescending { it.conns }
        val sb = SpannableStringBuilder()
        appendDim(sb, getString(R.string.ntm_dlg_conns_intro_fmt, source().agg(blocking).connections))
        apps.take(6).forEach { appendItem(sb, it.name, "· ${it.conns}") }
        appendMore(sb, apps.size - 6)
        sb.append("\n\n"); sb.append(getString(R.string.ntm_dlg_conns_note))
        ScanDialog.notice(this, getString(R.string.ntm_tile_connections), sb, getString(R.string.ntm_tracker_close))
    }

    /** "Data today" tile → Wi-Fi / cellular split + the apps using the most. */
    private fun showDataDialog() {
        val agg = source().agg(blocking)
        val apps = source().apps().filter { it.fg + it.bg > 0 }.sortedByDescending { it.fg + it.bg }
        val sb = SpannableStringBuilder()
        appendDim(sb, getString(R.string.ntm_dlg_data_intro_fmt,
            NtmDemoData.fmtBytes(agg.wifiKb), NtmDemoData.fmtBytes(agg.mobileKb)))
        apps.take(6).forEach { appendItem(sb, it.name, "· ${NtmDemoData.fmtBytes(it.fg + it.bg)}") }
        appendMore(sb, apps.size - 6)
        sb.append("\n\n"); sb.append(getString(R.string.ntm_dlg_data_note))
        ScanDialog.notice(this, getString(R.string.ntm_tile_data), sb, getString(R.string.ntm_tracker_close))
    }

    private fun appendDim(sb: SpannableStringBuilder, text: String) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(ForegroundColorSpan(color(R.color.v4_fg3)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** A bold name followed by a dim detail, on its own line. */
    private fun appendItem(sb: SpannableStringBuilder, name: String, detail: String) {
        sb.append("\n\n")
        val start = sb.length
        sb.append(name)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        appendDim(sb, "  $detail")
    }

    private fun appendMore(sb: SpannableStringBuilder, extra: Int) {
        if (extra > 0) { sb.append("\n\n"); appendDim(sb, getString(R.string.ntm_dlg_and_more_fmt, extra)) }
    }

    // ───────────────────────── switches ─────────────────────────

    private fun bindSwitchLabel(root: View, iconRes: Int, labelRes: Int, subRes: Int) {
        root.findViewById<ImageView>(R.id.swIcon).setImageResource(iconRes)
        root.findViewById<TextView>(R.id.swLabel).setText(labelRes)
        root.findViewById<TextView>(R.id.swSub).setText(subRes)
    }

    private fun renderSwitch(root: View, on: Boolean, enabled: Boolean, animate: Boolean) {
        val track = root.findViewById<FrameLayout>(R.id.swTrack)
        val thumb = root.findViewById<View>(R.id.swThumb)
        val iconTile = root.findViewById<FrameLayout>(R.id.swIconTile)
        val icon = root.findViewById<ImageView>(R.id.swIcon)

        track.setBackgroundResource(if (on) R.drawable.bg_ntm_switch_track_on else R.drawable.bg_ntm_switch_track_off)
        thumb.setBackgroundResource(if (on) R.drawable.bg_ntm_switch_thumb_on else R.drawable.bg_ntm_switch_thumb_off)
        val target = if (on) dp(18).toFloat() else 0f
        if (animate) thumb.animate().translationX(target).setDuration(180).start()
        else thumb.translationX = target

        icon.imageTintList = colorState(if (on) R.color.v4_accent else R.color.v4_fg3)
        iconTile.setBackgroundResource(
            if (on) R.drawable.bg_v4_perm_icon_tile_active else R.drawable.bg_v4_perm_icon_tile
        )
        root.alpha = if (enabled) 1f else 0.4f
        root.isClickable = enabled
    }

    // ───────────────────────── sort / filter ─────────────────────────

    private fun setSort(value: String) {
        sort = value
        styleSortChips()
        renderApps(source())
    }

    private fun setFilter(value: String) {
        filter = value
        styleFilterChips()
        renderApps(source())
    }

    private fun styleSortChips() {
        for ((chip, id) in listOf(sortData to "data", sortTrackers to "trackers", sortRecent to "recent")) {
            val on = sort == id
            chip.setBackgroundResource(if (on) R.drawable.bg_ntm_sort_on else 0)
            chip.setTextColor(color(if (on) R.color.v4_accent else R.color.v4_fg3))
        }
    }

    private fun styleFilterChips() {
        for ((chip, id) in listOf(filterAll to "all", filterTrackers to "trackers", filterBackground to "background")) {
            val on = filter == id
            chip.setBackgroundResource(if (on) R.drawable.bg_ntm_chip_on else R.drawable.bg_ntm_chip_off)
            chip.setTextColor(color(if (on) R.color.v4_bg else R.color.v4_fg2))
        }
    }

    // ───────────────────────── animations ─────────────────────────

    private fun applyPhaseAnimations() {
        if (phase == Phase.STARTING) startingRadar.start() else startingRadar.stop()
        if (phase == Phase.EMPTY) emptyRadar.start() else emptyRadar.stop()
        if (phase == Phase.LIVE) {
            heroRadar.start()
            startLiveTick()
            startLivePulse()
            startDataRefresh()
        } else {
            heroRadar.stop()
            stopLiveTick()
            stopLivePulse()
            stopDataRefresh()
        }
    }

    private fun stopAnimations() {
        startingRadar.stop(); emptyRadar.stop(); heroRadar.stop()
        stopLiveTick(); stopLivePulse(); stopDataRefresh()
    }

    /** Animate the gauge sweep + score count-up to the posture value (the design's .7s ease). */
    private fun animateGauge(score: Int, colorRes: Int) {
        ValueAnimator.ofInt(0, score).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Int
                postureGauge.setScore(v, colorRes)
                tvPostureScore.text = v.toString()
            }
            start()
        }
    }

    private var lastTotalBytes = 0L
    private fun startLiveTick() {
        if (tickJob?.isActive == true) return
        lastTotalBytes = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
        tickJob = lifecycleScope.launch {
            while (isActive) {
                delay(850)
                // Real device throughput from TrafficStats deltas — no fabricated numbers.
                val now = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
                val delta = (now - lastTotalBytes).coerceAtLeast(0L)
                lastTotalBytes = now
                spark.removeAt(0); spark.add((delta / 1024f).coerceAtLeast(0f))   // KB moved this tick
                sparkline.setData(spark.toFloatArray())
                tvRate.text = String.format("%.1f", delta / 0.85 / 1_048_576.0)   // MB/s
            }
        }
    }

    private fun stopLiveTick() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun startLivePulse() {
        if (pulse?.isRunning == true) return
        pulse = ValueAnimator.ofFloat(1f, 0.3f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { livePillDot.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopLivePulse() {
        pulse?.cancel()
        pulse = null
        livePillDot.alpha = 1f
    }

    /** While LIVE on real data, periodically recompute the live source so newly observed domains
     *  and usage surface without re-entering the screen. Demo data is static (no refresh). */
    private fun startDataRefresh() {
        if (dataJob?.isActive == true) return
        if (PreferencesManager.isNetMonDemoMode(this) || !PreferencesManager.isNetMonActive(this)) return
        dataJob = lifecycleScope.launch {
            while (isActive) {
                delay(5000)
                if (phase == Phase.LIVE && PreferencesManager.isNetMonActive(this@NetworkMonitorActivity)) {
                    refreshLiveData()
                    renderDynamic(animateGauge = false)
                }
            }
        }
    }

    private fun stopDataRefresh() {
        dataJob?.cancel()
        dataJob = null
    }

    // ───────────────────────── tone / style helpers ─────────────────────────

    private fun toneColorRes(tone: Tone): Int = when (tone) {
        Tone.OK -> R.color.v4_ok
        Tone.ACCENT -> R.color.v4_accent
        Tone.WARN -> R.color.v4_warn
        Tone.BAD -> R.color.v4_bad
    }

    private fun toneBgColor(tone: Tone): Int = color(
        when (tone) {
            Tone.OK -> R.color.v4_ok_bg
            Tone.ACCENT -> R.color.v4_accent_bg
            Tone.WARN -> R.color.v4_warn_bg
            Tone.BAD -> R.color.v4_bad_bg
        }
    )

    private fun toneTileBg(tone: Tone): Int = when (tone) {
        Tone.BAD -> R.drawable.bg_v4_breach_tile_bad
        Tone.WARN -> R.drawable.bg_v4_breach_tile_warn
        else -> R.drawable.bg_v4_perm_pill_ok
    }

    private fun tonePillBg(tone: Tone): Int = when (tone) {
        Tone.BAD -> R.drawable.bg_v4_perm_pill_bad
        Tone.WARN -> R.drawable.bg_v4_perm_pill_warn
        else -> R.drawable.bg_v4_perm_pill_ok
    }

    private fun tonePillLabel(tone: Tone): Int = when (tone) {
        Tone.BAD -> R.string.ntm_pill_high
        Tone.WARN -> R.string.ntm_pill_med
        else -> R.string.ntm_pill_ok
    }

    private fun findingIconRes(icon: FindingIcon): Int = when (icon) {
        FindingIcon.WARN -> R.drawable.ic_glyph_warn
        FindingIcon.TRACKERS -> R.drawable.ic_glyph_trackers
        FindingIcon.BACKGROUND -> R.drawable.ic_glyph_background
        FindingIcon.BLOCK -> R.drawable.ic_glyph_block
    }

    private fun pillBg(fillColor: Int, strokeColor: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }

    private fun withAlpha(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0xFFFFFF)

    private fun parseColor(hex: String, fallback: Int): Int =
        runCatching { Color.parseColor(hex) }.getOrDefault(fallback)

    private val iconCache = HashMap<String, Drawable?>()
    /** Real installed-app icon for [pkg], cached; null if not resolvable (→ monogram fallback). */
    private fun appIconFor(pkg: String): Drawable? =
        iconCache.getOrPut(pkg) { runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)
    private fun colorState(res: Int): ColorStateList = ColorStateList.valueOf(color(res))
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun slideBack() = overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, NetworkMonitorActivity::class.java))
            if (context is android.app.Activity) {
                @Suppress("DEPRECATION")
                context.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }
    }
}
