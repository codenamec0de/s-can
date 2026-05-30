package com.uow.scan

import android.content.Intent
import android.content.res.ColorStateList
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
import com.uow.scan.ui.home.widget.RadarPulseView
import com.uow.scan.ui.home.widget.WifiScoreGaugeView
import com.uow.scan.util.DnsLeakAnalyzer
import com.uow.scan.util.DnsLeakAnalyzer.DemoMode
import com.uow.scan.util.DnsLeakAnalyzer.DnsResult
import com.uow.scan.util.DnsLeakAnalyzer.Grade
import com.uow.scan.util.DnsLeakAnalyzer.Severity
import com.uow.scan.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DNS Leak Detection — the Beta tool behind the Home card. A stateful screen that runs
 * an on-device check and answers one question: "Can my network see or redirect the sites
 * I visit?" Phases: empty (first-run) → scanning (4-step) → result (verdict gauge +
 * resolver summary + findings + remediation), plus an offline/error state.
 *
 * Built on the same V4 primitives as the Wi-Fi detail screen — [WifiScoreGaugeView] for
 * the gauge, [RadarPulseView] for the scanning pulse, and item_wifi_finding rows — so it
 * reads as native to the suite. Detection lives in [DnsLeakAnalyzer].
 *
 * Demo override: long-press the title to cycle live → forced-Exposed → forced-Protected
 * for deterministic presentations.
 */
class DnsLeakActivity : AppCompatActivity() {

    private enum class Phase { EMPTY, SCANNING, ERROR, RESULT }
    private enum class StepState { IDLE, ACTIVE, DONE }

    private lateinit var phaseEmpty: View
    private lateinit var phaseScanning: View
    private lateinit var phaseError: View
    private lateinit var phaseResult: View

    private lateinit var btnBack: View
    private lateinit var btnRefreshTop: View
    private lateinit var tvTopTitle: TextView

    private lateinit var btnCheck: MaterialButton
    private lateinit var btnTryAgain: MaterialButton

    private lateinit var radarPulse: RadarPulseView
    private lateinit var stepRows: List<View>

    private lateinit var dnsGauge: WifiScoreGaugeView
    private lateinit var tvScore: TextView
    private lateinit var gradeBadge: LinearLayout
    private lateinit var gradeDot: View
    private lateinit var tvGrade: TextView
    private lateinit var tvVerdictLine: TextView
    private lateinit var tvResolverProto: TextView

    private lateinit var tileResolver: View
    private lateinit var tileEncrypted: View
    private lateinit var tileNetwork: View
    private lateinit var tileVpn: View

    private lateinit var cleanBanner: View
    private lateinit var tvFindingsHeader: TextView
    private lateinit var findingsContainer: LinearLayout
    private lateinit var btnPrimary: MaterialButton
    private lateinit var btnSecondary: MaterialButton
    private lateinit var tvFooter: TextView

    private var currentPhase = Phase.EMPTY
    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_leak)
        bindViews()
        setupSteps()
        setupListeners()
        showPhase(Phase.EMPTY)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun bindViews() {
        phaseEmpty = findViewById(R.id.phaseEmpty)
        phaseScanning = findViewById(R.id.phaseScanning)
        phaseError = findViewById(R.id.phaseError)
        phaseResult = findViewById(R.id.phaseResult)

        btnBack = findViewById(R.id.btnBack)
        btnRefreshTop = findViewById(R.id.btnRefreshTop)
        tvTopTitle = findViewById(R.id.tvTopTitle)

        btnCheck = findViewById(R.id.btnCheck)
        btnTryAgain = findViewById(R.id.btnTryAgain)

        radarPulse = findViewById(R.id.radarPulse)
        stepRows = listOf(
            findViewById(R.id.step1), findViewById(R.id.step2),
            findViewById(R.id.step3), findViewById(R.id.step4),
        )

        dnsGauge = findViewById(R.id.dnsGauge)
        tvScore = findViewById(R.id.tvScore)
        gradeBadge = findViewById(R.id.gradeBadge)
        gradeDot = findViewById(R.id.gradeDot)
        tvGrade = findViewById(R.id.tvGrade)
        tvVerdictLine = findViewById(R.id.tvVerdictLine)
        tvResolverProto = findViewById(R.id.tvResolverProto)

        tileResolver = findViewById(R.id.tileResolver)
        tileEncrypted = findViewById(R.id.tileEncrypted)
        tileNetwork = findViewById(R.id.tileNetwork)
        tileVpn = findViewById(R.id.tileVpn)

        cleanBanner = findViewById(R.id.cleanBanner)
        tvFindingsHeader = findViewById(R.id.tvFindingsHeader)
        findingsContainer = findViewById(R.id.findingsContainer)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnSecondary = findViewById(R.id.btnSecondary)
        tvFooter = findViewById(R.id.tvFooter)
    }

    private fun setupSteps() {
        val labels = listOf(
            R.string.dns_v4_step_resolver, R.string.dns_v4_step_encryption,
            R.string.dns_v4_step_vpn, R.string.dns_v4_step_tamper,
        )
        stepRows.forEachIndexed { i, row ->
            row.findViewById<TextView>(R.id.stepLabel).setText(labels[i])
            setStepState(row, StepState.IDLE)
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
        btnRefreshTop.setOnClickListener { startScan() }
        btnCheck.setOnClickListener { startScan() }
        btnTryAgain.setOnClickListener { startScan() }
        tvTopTitle.setOnLongClickListener { cycleDemoMode(); true }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase machine
    // ─────────────────────────────────────────────────────────────────────────

    private fun showPhase(phase: Phase) {
        currentPhase = phase
        phaseEmpty.visibility = if (phase == Phase.EMPTY) View.VISIBLE else View.GONE
        phaseScanning.visibility = if (phase == Phase.SCANNING) View.VISIBLE else View.GONE
        phaseError.visibility = if (phase == Phase.ERROR) View.VISIBLE else View.GONE
        phaseResult.visibility = if (phase == Phase.RESULT) View.VISIBLE else View.GONE
        btnRefreshTop.visibility = if (phase == Phase.RESULT) View.VISIBLE else View.GONE
    }

    /** Runs the analyzer off the UI thread, then plays the 4-step checklist before revealing. */
    private fun startScan() {
        scanJob?.cancel()
        stepRows.forEach { setStepState(it, StepState.IDLE) }
        showPhase(Phase.SCANNING)
        radarPulse.start()
        scanJob = lifecycleScope.launch {
            val mode = PreferencesManager.getDnsDemoMode(this@DnsLeakActivity)
            val outcome = withContext(Dispatchers.IO) {
                DnsLeakAnalyzer.analyze(this@DnsLeakActivity, mode)
            }
            for (row in stepRows) {
                setStepState(row, StepState.ACTIVE)
                delay(430)
                setStepState(row, StepState.DONE)
            }
            delay(240)
            radarPulse.stop()
            when (outcome) {
                is DnsLeakAnalyzer.Outcome.Offline -> showPhase(Phase.ERROR)
                is DnsLeakAnalyzer.Outcome.Ok -> {
                    renderResult(outcome.result)
                    showPhase(Phase.RESULT)
                }
            }
        }
    }

    private fun setStepState(row: View, state: StepState) {
        val tile = row.findViewById<FrameLayout>(R.id.stepTile)
        val dot = row.findViewById<View>(R.id.stepDot)
        val icon = row.findViewById<ImageView>(R.id.stepIcon)
        val label = row.findViewById<TextView>(R.id.stepLabel)
        val status = row.findViewById<TextView>(R.id.stepStatus)

        when (state) {
            StepState.IDLE -> {
                tile.setBackgroundResource(R.drawable.bg_v4_dns_step_idle)
                icon.visibility = View.GONE
                dot.visibility = View.VISIBLE
                setDotSize(dot, 5)
                dot.backgroundTintList = colorState(R.color.v4_fg4)
                status.visibility = View.GONE
                label.setTextColor(color(R.color.v4_fg3))
                row.alpha = 0.45f
            }
            StepState.ACTIVE -> {
                tile.setBackgroundResource(R.drawable.bg_v4_dns_step_active)
                icon.visibility = View.GONE
                dot.visibility = View.VISIBLE
                setDotSize(dot, 8)
                dot.backgroundTintList = colorState(R.color.v4_accent)
                status.visibility = View.VISIBLE
                status.setText(R.string.dns_v4_step_running)
                status.setTextColor(color(R.color.v4_accent))
                label.setTextColor(color(R.color.v4_fg0))
                row.alpha = 1f
            }
            StepState.DONE -> {
                tile.setBackgroundResource(R.drawable.bg_v4_dns_step_done)
                dot.visibility = View.GONE
                icon.visibility = View.VISIBLE
                status.visibility = View.VISIBLE
                status.setText(R.string.dns_v4_step_done)
                status.setTextColor(color(R.color.v4_fg3))
                label.setTextColor(color(R.color.v4_fg2))
                row.alpha = 1f
            }
        }
    }

    private fun setDotSize(dot: View, sizeDp: Int) {
        val lp = dot.layoutParams
        lp.width = dp(sizeDp)
        lp.height = dp(sizeDp)
        dot.layoutParams = lp
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result rendering
    // ─────────────────────────────────────────────────────────────────────────

    private fun renderResult(r: DnsResult) {
        val gradeColor = gradeColorRes(r.grade)
        tvScore.text = r.score.toString()
        dnsGauge.setScore(r.score, gradeColor)

        gradeBadge.setBackgroundResource(gradeBadgeBg(r.grade))
        gradeDot.backgroundTintList = colorState(gradeColor)
        tvGrade.setText(gradeLabel(r.grade))
        tvGrade.setTextColor(color(gradeColor))

        tvVerdictLine.text = r.verdictLine
        val proto = if (r.resolver.encrypted) r.resolver.protocol
        else getString(R.string.dns_v4_proto_unencrypted)
        tvResolverProto.text = getString(R.string.dns_v4_resolver_proto_format, proto, r.resolver.provider)

        renderTiles(r)

        cleanBanner.visibility = if (r.isClean) View.VISIBLE else View.GONE

        tvFindingsHeader.text = if (r.isClean) {
            getString(R.string.dns_v4_section_findings_clean, r.findings.size)
        } else {
            getString(R.string.dns_v4_section_findings_count, r.flaggedCount, r.findings.size)
        }
        renderFindings(r)
        renderActions(r)

        tvFooter.setText(if (r.isDemo) R.string.dns_v4_footer_demo else R.string.dns_v4_footer)
    }

    private fun renderTiles(r: DnsResult) {
        val res = r.resolver
        bindTile(
            tileResolver, R.drawable.ic_glyph_data, R.string.dns_v4_tile_resolver,
            res.provider, res.address, if (res.isRouter) R.color.v4_warn else R.color.v4_ok,
        )
        bindTile(
            tileEncrypted, R.drawable.ic_glyph_lock, R.string.dns_v4_tile_encrypted,
            getString(if (res.encrypted) R.string.dns_v4_yes else R.string.dns_v4_no),
            res.protocol, if (res.encrypted) R.color.v4_ok else R.color.v4_bad,
        )
        bindTile(
            tileNetwork, R.drawable.ic_glyph_wifi, R.string.dns_v4_tile_network,
            res.network, res.networkName, R.color.v4_fg0,
        )
        bindTile(
            tileVpn, R.drawable.ic_glyph_shield, R.string.dns_v4_tile_vpn,
            getString(if (res.vpn) R.string.dns_v4_on else R.string.dns_v4_off),
            getString(if (res.vpn) R.string.dns_v4_vpn_sub_on else R.string.dns_v4_vpn_sub_off),
            if (res.vpn) R.color.v4_ok else R.color.v4_fg2,
        )
    }

    private fun bindTile(tile: View, icon: Int, label: Int, value: String, sub: String, valueColor: Int) {
        tile.findViewById<ImageView>(R.id.tileIcon).setImageResource(icon)
        tile.findViewById<TextView>(R.id.tileLabel).setText(label)
        val tv = tile.findViewById<TextView>(R.id.tileValue)
        tv.text = value
        tv.setTextColor(color(valueColor))
        tile.findViewById<TextView>(R.id.tileSub).text = sub
    }

    private fun renderFindings(r: DnsResult) {
        findingsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (finding in r.findings) {
            val row = inflater.inflate(R.layout.item_wifi_finding, findingsContainer, false)
            bindFindingRow(row, finding)
            findingsContainer.addView(row)
        }
    }

    private fun bindFindingRow(row: View, finding: DnsLeakAnalyzer.Finding) {
        val style = severityStyle(finding.severity)
        val tile = row.findViewById<FrameLayout>(R.id.sevTile)
        val icon = row.findViewById<ImageView>(R.id.sevIcon)
        val pill = row.findViewById<TextView>(R.id.tvSeverityPill)
        tile.setBackgroundResource(style.tileBgRes)
        icon.setImageResource(style.iconRes)
        icon.setColorFilter(color(style.colorRes))
        pill.text = getString(style.labelRes)
        pill.setTextColor(color(style.colorRes))
        pill.setBackgroundResource(style.pillBgRes)

        row.findViewById<TextView>(R.id.tvFindingTitle).text = finding.title
        row.findViewById<TextView>(R.id.tvFindingBody).text = finding.description

        val fixBox = row.findViewById<LinearLayout>(R.id.fixBox)
        val tvFix = row.findViewById<TextView>(R.id.tvFindingFix)
        if (finding.fix != null) {
            fixBox.visibility = View.VISIBLE
            tvFix.text = finding.fix
            if (finding.cta != null) {
                fixBox.isClickable = true
                fixBox.setOnClickListener { onCta(finding.cta) }
            } else {
                fixBox.isClickable = false
                fixBox.setOnClickListener(null)
            }
        } else {
            fixBox.visibility = View.GONE
        }
    }

    private fun renderActions(r: DnsResult) {
        if (r.isClean) {
            btnPrimary.visibility = View.GONE
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.setText(R.string.dns_v4_btn_recheck)
            btnSecondary.setIconResource(R.drawable.ic_glyph_refresh)
            btnSecondary.setOnClickListener { startScan() }
        } else {
            btnPrimary.visibility = View.VISIBLE
            btnPrimary.setText(R.string.dns_v4_btn_private_dns)
            btnPrimary.setIconResource(R.drawable.ic_glyph_lock)
            btnPrimary.setOnClickListener { openPrivateDnsSettings() }
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.setText(R.string.dns_v4_btn_run_again)
            btnSecondary.setIconResource(R.drawable.ic_glyph_refresh)
            btnSecondary.setOnClickListener { startScan() }
        }
    }

    private fun onCta(cta: String) {
        when (cta) {
            "private-dns" -> openPrivateDnsSettings()
            "deep-test" -> Toast.makeText(this, R.string.dns_v4_deeptest_toast, Toast.LENGTH_SHORT).show()
        }
    }

    /** No public Private-DNS settings action exists; open the closest network screen + hint. */
    private fun openPrivateDnsSettings() {
        val opened = listOf(
            Settings.ACTION_WIRELESS_SETTINGS,
            Settings.ACTION_SETTINGS,
        ).any { action ->
            try {
                startActivity(Intent(action))
                true
            } catch (_: Exception) {
                false
            }
        }
        if (opened) {
            Toast.makeText(this, R.string.dns_v4_private_dns_hint, Toast.LENGTH_LONG).show()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Demo override
    // ─────────────────────────────────────────────────────────────────────────

    private fun cycleDemoMode() {
        val next = when (PreferencesManager.getDnsDemoMode(this)) {
            DemoMode.AUTO -> DemoMode.EXPOSED
            DemoMode.EXPOSED -> DemoMode.PROTECTED
            DemoMode.PROTECTED -> DemoMode.AUTO
        }
        PreferencesManager.setDnsDemoMode(this, next)
        val msg = when (next) {
            DemoMode.AUTO -> R.string.dns_v4_demo_auto
            DemoMode.EXPOSED -> R.string.dns_v4_demo_exposed
            DemoMode.PROTECTED -> R.string.dns_v4_demo_protected
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        // Reflect the new mode immediately unless we're on the first-run screen.
        if (currentPhase != Phase.EMPTY) startScan()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Styling helpers
    // ─────────────────────────────────────────────────────────────────────────

    private data class SevStyle(
        val tileBgRes: Int,
        val pillBgRes: Int,
        val colorRes: Int,
        val iconRes: Int,
        val labelRes: Int,
    )

    private fun severityStyle(s: Severity): SevStyle = when (s) {
        Severity.BAD -> SevStyle(
            R.drawable.bg_v4_breach_tile_bad, R.drawable.bg_v4_perm_pill_bad,
            R.color.v4_bad, R.drawable.ic_glyph_warn, R.string.dns_v4_pill_high,
        )
        Severity.WARN -> SevStyle(
            R.drawable.bg_v4_breach_tile_warn, R.drawable.bg_v4_perm_pill_warn,
            R.color.v4_warn, R.drawable.ic_glyph_warn, R.string.dns_v4_pill_medium,
        )
        Severity.OK -> SevStyle(
            R.drawable.bg_v4_perm_pill_ok, R.drawable.bg_v4_perm_pill_ok,
            R.color.v4_ok, R.drawable.ic_glyph_check, R.string.dns_v4_pill_ok,
        )
    }

    private fun gradeColorRes(grade: Grade): Int = when (grade) {
        Grade.PRIVATE -> R.color.v4_ok
        Grade.PARTIAL -> R.color.v4_warn
        Grade.EXPOSED, Grade.INTERCEPTED -> R.color.v4_bad
    }

    private fun gradeBadgeBg(grade: Grade): Int = when (grade) {
        Grade.PRIVATE -> R.drawable.bg_v4_perm_pill_ok
        Grade.PARTIAL -> R.drawable.bg_v4_perm_pill_warn
        Grade.EXPOSED, Grade.INTERCEPTED -> R.drawable.bg_v4_perm_pill_bad
    }

    private fun gradeLabel(grade: Grade): Int = when (grade) {
        Grade.PRIVATE -> R.string.dns_v4_grade_private
        Grade.PARTIAL -> R.string.dns_v4_grade_partial
        Grade.EXPOSED -> R.string.dns_v4_grade_exposed
        Grade.INTERCEPTED -> R.string.dns_v4_grade_intercepted
    }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)
    private fun colorState(res: Int): ColorStateList = ColorStateList.valueOf(color(res))
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun start(context: android.content.Context) {
            context.startActivity(Intent(context, DnsLeakActivity::class.java))
            if (context is android.app.Activity) {
                @Suppress("DEPRECATION")
                context.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }
    }
}
