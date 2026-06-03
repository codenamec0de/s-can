package com.uow.scan.ui.home

import android.animation.ObjectAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.uow.scan.AppDetailActivity
import com.uow.scan.BreachCheckerActivity
import com.uow.scan.DnsLeakActivity
import com.uow.scan.MainActivity
import com.uow.scan.NetworkMonitorActivity
import com.uow.scan.TerminatorActivity
import com.uow.scan.R
import com.uow.scan.SmsOnboardingActivity
import com.uow.scan.SmsScamActivity
import com.uow.scan.WifiSecurityActivity
import com.uow.scan.data.ScanDatabase
import com.uow.scan.ui.home.widget.RadarPulseView
import com.uow.scan.util.AlertStorage
import com.uow.scan.util.DemoDataSeeder
import com.uow.scan.util.DeviceSecurityChecker
import com.uow.scan.util.PreferencesManager
import com.uow.scan.util.ScanRunner
import com.uow.scan.util.SensorAccessFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {

    private lateinit var btnNotifications: View
    private lateinit var tvGreetingEyebrow: TextView
    private lateinit var tvGreetingHead: TextView
    private lateinit var tvGreetingSub: TextView
    private lateinit var tvStatusHeadline: TextView
    private lateinit var tvStatusMeta: TextView
    private lateinit var btnScan: MaterialButton
    private lateinit var btnViewApps: MaterialButton
    private lateinit var attentionContainer: LinearLayout
    private lateinit var tvAttentionEmpty: TextView
    private lateinit var tvSeeAllAttention: TextView

    private lateinit var toolWifi: View
    private lateinit var toolTerminator: View
    private lateinit var toolSms: View
    private lateinit var toolBreach: View
    private lateinit var toolNetworkMonitor: View
    private lateinit var toolDnsLeak: View

    private var scanning = false
    private var sweepAnim: ObjectAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupListeners()
        renderGreetingAndStatus()
        renderTools()
        renderAttention()
        recordDeviceSecurity()
    }

    override fun onResume() {
        super.onResume()
        renderGreetingAndStatus()
        renderTools()
        renderAttention()
        startLiveBadge()
    }

    override fun onPause() {
        super.onPause()
        stopLiveBadge()
    }

    /** Always-on radar badge (top-right corner): pulsing rings + a continuously rotating sweep. */
    private fun startLiveBadge() {
        val v = view ?: return
        v.findViewById<RadarPulseView>(R.id.liveRadar)?.start()
        val sweep = v.findViewById<View>(R.id.liveSweep) ?: return
        if (sweepAnim?.isRunning != true) {
            sweepAnim = ObjectAnimator.ofFloat(sweep, View.ROTATION, 0f, 360f).apply {
                duration = 3400L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }
    }

    private fun stopLiveBadge() {
        view?.findViewById<RadarPulseView>(R.id.liveRadar)?.stop()
        sweepAnim?.cancel()
        sweepAnim = null
    }

    private fun bindViews(v: View) {
        btnNotifications = v.findViewById(R.id.btnNotifications)
        tvGreetingEyebrow = v.findViewById(R.id.tvGreetingEyebrow)
        tvGreetingHead = v.findViewById(R.id.tvGreetingHead)
        tvGreetingSub = v.findViewById(R.id.tvGreetingSub)
        tvStatusHeadline = v.findViewById(R.id.tvStatusHeadline)
        tvStatusMeta = v.findViewById(R.id.tvStatusMeta)
        btnScan = v.findViewById(R.id.btnScan)
        btnViewApps = v.findViewById(R.id.btnViewApps)
        attentionContainer = v.findViewById(R.id.attentionContainer)
        tvAttentionEmpty = v.findViewById(R.id.tvAttentionEmpty)
        tvSeeAllAttention = v.findViewById(R.id.tvSeeAllAttention)

        toolWifi = v.findViewById(R.id.toolWifi)
        toolTerminator = v.findViewById(R.id.toolTerminator)
        toolSms = v.findViewById(R.id.toolSms)
        toolBreach = v.findViewById(R.id.toolBreach)
        toolNetworkMonitor = v.findViewById(R.id.toolNetworkMonitor)
        toolDnsLeak = v.findViewById(R.id.toolDnsLeak)
    }

    private fun setupListeners() {
        btnNotifications.setOnClickListener {
            (activity as? MainActivity)?.navigateToActivity()
        }
        btnScan.setOnClickListener { runScan() }
        btnViewApps.setOnClickListener {
            (activity as? MainActivity)?.navigateToApps()
        }
        tvSeeAllAttention.setOnClickListener {
            (activity as? MainActivity)?.navigateToActivity()
        }
        // Hidden presenter aid: long-press the greeting to load realistic demo data so no
        // headline screen is empty on a clean device. Invisible to an audience.
        tvGreetingHead.setOnLongClickListener {
            seedDemoData()
            true
        }
    }

    private fun seedDemoData() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { DemoDataSeeder.seed(ctx) }
            if (!isAdded || view == null) return@launch
            android.widget.Toast.makeText(
                ctx, getString(R.string.demo_data_loaded), android.widget.Toast.LENGTH_SHORT
            ).show()
            renderGreetingAndStatus()
            renderAttention()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Greeting + status card
    // ──────────────────────────────────────────────────────────────────────

    private fun renderGreetingAndStatus() {
        val ctx = context ?: return

        // Eyebrow: "TUESDAY · 10:13"
        val now = Date()
        val dayFmt = SimpleDateFormat("EEEE", Locale.getDefault())
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        tvGreetingEyebrow.text = getString(
            R.string.home_eyebrow_format, dayFmt.format(now), timeFmt.format(now)
        )

        // Greeting head depends on time of day
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        tvGreetingHead.setText(
            when (hour) {
                in 5..11 -> R.string.home_greeting_morning
                in 12..16 -> R.string.home_greeting_afternoon
                in 17..21 -> R.string.home_greeting_evening
                else -> R.string.home_greeting_night
            }
        )

        // Status numbers
        viewLifecycleOwner.lifecycleScope.launch {
            val findingCount = withContext(Dispatchers.IO) {
                AlertStorage.getAlerts(ctx).size
            }
            val appsCount = withContext(Dispatchers.IO) {
                ScanDatabase.getInstance(ctx).scanResultDao().getAll().size
            }
            val lastScan = PreferencesManager.getLastScanTime(ctx)

            if (!isAdded || view == null) return@launch

            // Greeting sub
            tvGreetingSub.text = when (findingCount) {
                0 -> getString(R.string.home_greeting_findings_zero)
                1 -> getString(R.string.home_greeting_findings_one)
                else -> getString(R.string.home_greeting_findings_n, findingCount)
            }

            // Status card headline
            tvStatusHeadline.text = when (findingCount) {
                0 -> getString(R.string.home_status_zero)
                1 -> getString(R.string.home_status_one)
                else -> getString(R.string.home_status_n, findingCount)
            }

            // Status card meta line
            tvStatusMeta.text = if (lastScan == 0L) {
                getString(R.string.home_status_meta_never)
            } else {
                getString(
                    R.string.home_status_meta,
                    relativeTime(lastScan),
                    appsCount
                )
            }
        }
    }

    private fun relativeTime(then: Long): String {
        val deltaMs = System.currentTimeMillis() - then
        val mins = deltaMs / 60_000
        val hours = mins / 60
        val days = hours / 24
        return when {
            mins < 1 -> getString(R.string.home_time_just_now)
            mins < 60 -> getString(R.string.home_time_minutes_ago, mins.toInt())
            hours < 24 -> getString(R.string.home_time_hours_ago, hours.toInt())
            else -> getString(R.string.home_time_days_ago, days.toInt())
        }
    }

    private fun runScan() {
        if (scanning) return
        val ctx = context ?: return
        scanning = true
        btnScan.setText(R.string.home_cta_scanning)
        btnScan.icon = null
        btnScan.isEnabled = false
        var ok = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // ScanRunner manages its own dispatchers; setLastScanTime is set inside it.
                ScanRunner.runFullScan(ctx)
                ok = true
            } catch (e: Exception) {
                android.util.Log.e("HomeFragment", "Scan failed", e)
                if (isAdded) android.widget.Toast.makeText(
                    ctx, getString(R.string.home_scan_failed), android.widget.Toast.LENGTH_SHORT
                ).show()
            } finally {
                scanning = false
                // Always restore the button to a usable state, even on failure or if the
                // view is being torn down — otherwise the Scan CTA gets stuck on "Scanning…".
                if (isAdded && view != null) {
                    btnScan.setText(R.string.home_cta_scan)
                    btnScan.setIconResource(R.drawable.ic_glyph_refresh)
                    btnScan.isEnabled = true
                    renderGreetingAndStatus()
                    renderAttention()
                    if (ok) celebrate()
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tool cards
    // ──────────────────────────────────────────────────────────────────────

    private fun renderTools() {
        val ctx = context ?: return
        bindToolCard(
            view = toolWifi,
            icon = R.drawable.ic_glyph_wifi,
            titleRes = R.string.home_tool_wifi_title,
            descRes = R.string.home_tool_wifi_desc,
            isActive = PreferencesManager.isWifiToolEnabled(ctx),
            onToggle = {
                val flipped = !PreferencesManager.isWifiToolEnabled(ctx)
                PreferencesManager.setWifiToolEnabled(ctx, flipped)
                renderTools()
            },
            onCardClick = {
                if (PreferencesManager.isWifiToolEnabled(ctx)) {
                    startActivity(Intent(ctx, WifiSecurityActivity::class.java))
                }
            }
        )
        bindToolCard(
            view = toolSms,
            icon = R.drawable.ic_glyph_sms,
            titleRes = R.string.home_tool_sms_title,
            descRes = R.string.home_tool_sms_desc,
            isActive = PreferencesManager.isSmsScamDetectionEnabled(ctx),
            onToggle = {
                val flipped = !PreferencesManager.isSmsScamDetectionEnabled(ctx)
                PreferencesManager.setSmsScamDetectionEnabled(ctx, flipped)
                renderTools()
            },
            onCardClick = {
                val target = if (PreferencesManager.isSmsScamDetectionEnabled(ctx))
                    SmsScamActivity::class.java
                else
                    SmsOnboardingActivity::class.java
                startActivity(Intent(ctx, target))
            }
        )
        bindToolCard(
            view = toolBreach,
            icon = R.drawable.ic_glyph_eye,
            titleRes = R.string.home_tool_breach_title,
            descRes = R.string.home_tool_breach_desc,
            isActive = PreferencesManager.isBreachToolEnabled(ctx),
            onToggle = {
                val flipped = !PreferencesManager.isBreachToolEnabled(ctx)
                PreferencesManager.setBreachToolEnabled(ctx, flipped)
                renderTools()
            },
            onCardClick = {
                if (PreferencesManager.isBreachToolEnabled(ctx)) {
                    startActivity(Intent(ctx, BreachCheckerActivity::class.java))
                }
            }
        )
        bindToolCard(
            view = toolTerminator,
            icon = R.drawable.ic_glyph_terminator,
            titleRes = R.string.home_tool_terminator_title,
            descRes = R.string.home_tool_terminator_desc,
            isActive = PreferencesManager.isTerminatorToolEnabled(ctx),
            onToggle = {
                val flipped = !PreferencesManager.isTerminatorToolEnabled(ctx)
                PreferencesManager.setTerminatorToolEnabled(ctx, flipped)
                renderTools()
            },
            onCardClick = {
                if (PreferencesManager.isTerminatorToolEnabled(ctx)) {
                    startActivity(Intent(ctx, TerminatorActivity::class.java))
                }
            }
        )
        bindToolCard(
            view = toolNetworkMonitor,
            icon = R.drawable.ic_glyph_activity,
            titleRes = R.string.home_tool_netmon_title,
            descRes = R.string.home_tool_netmon_desc,
            isActive = PreferencesManager.isNetMonToolEnabled(ctx),
            onToggle = {
                val flipped = !PreferencesManager.isNetMonToolEnabled(ctx)
                PreferencesManager.setNetMonToolEnabled(ctx, flipped)
                renderTools()
            },
            onCardClick = {
                if (PreferencesManager.isNetMonToolEnabled(ctx)) {
                    NetworkMonitorActivity.start(ctx)
                }
            }
        )
        bindToolCard(
            view = toolDnsLeak,
            icon = R.drawable.ic_glyph_globe,
            titleRes = R.string.home_tool_dns_title,
            descRes = R.string.home_tool_dns_desc,
            isActive = PreferencesManager.isDnsToolEnabled(ctx),
            onToggle = {
                val flipped = !PreferencesManager.isDnsToolEnabled(ctx)
                PreferencesManager.setDnsToolEnabled(ctx, flipped)
                renderTools()
            },
            onCardClick = {
                if (PreferencesManager.isDnsToolEnabled(ctx)) {
                    DnsLeakActivity.start(ctx)
                }
            }
        )
    }

    /**
     * Binds an amber-tinted "Coming soon" card. The whole card is tappable but only
     * surfaces a toast — there is no toggle or destination yet.
     */
    private fun bindComingSoonCard(view: View, icon: Int, titleRes: Int, descRes: Int) {
        view.findViewById<ImageView>(R.id.toolIcon).setImageResource(icon)
        view.findViewById<TextView>(R.id.toolTitle).setText(titleRes)
        view.findViewById<TextView>(R.id.toolDesc).setText(descRes)
        view.setOnClickListener {
            android.widget.Toast.makeText(
                view.context,
                getString(R.string.home_tool_coming_soon_toast, getString(titleRes)),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun bindToolCard(
        view: View,
        icon: Int,
        titleRes: Int,
        descRes: Int,
        isActive: Boolean,
        onToggle: () -> Unit,
        onCardClick: () -> Unit,
    ) {
        val ctx = view.context

        view.setBackgroundResource(
            if (isActive) R.drawable.bg_v4_tool_card_active
            else R.drawable.bg_v4_tool_card_idle
        )

        val iconTile = view.findViewById<View>(R.id.toolIconTile)
        val iconView = view.findViewById<ImageView>(R.id.toolIcon)
        iconTile.setBackgroundResource(
            if (isActive) R.drawable.bg_v4_perm_icon_tile_active
            else R.drawable.bg_v4_perm_icon_tile
        )
        iconView.setImageResource(icon)
        iconView.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                ctx,
                if (isActive) R.color.v4_accent else R.color.v4_fg2
            )
        )

        view.findViewById<TextView>(R.id.toolTitle).setText(titleRes)
        view.findViewById<TextView>(R.id.toolDesc).setText(descRes)

        val toggle = view.findViewById<FrameLayout>(R.id.toolToggle)
        val thumb = view.findViewById<View>(R.id.toolToggleThumb)
        toggle.setBackgroundResource(
            if (isActive) R.drawable.bg_v4_tool_toggle_on else R.drawable.bg_v4_tool_toggle_off
        )
        thumb.setBackgroundResource(
            if (isActive) R.drawable.bg_v4_tool_toggle_thumb_on
            else R.drawable.bg_v4_tool_toggle_thumb_off
        )
        val thumbLp = thumb.layoutParams as FrameLayout.LayoutParams
        thumbLp.gravity = if (isActive)
            android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        else
            android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        thumbLp.marginStart = if (isActive) 0 else dp(2)
        thumbLp.marginEnd = if (isActive) dp(2) else 0
        thumb.layoutParams = thumbLp

        toggle.setOnClickListener { onToggle() }
        view.setOnClickListener { onCardClick() }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Needs attention rows
    // ──────────────────────────────────────────────────────────────────────

    private fun renderAttention() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val weekAgo = now - 7L * 24 * 60 * 60 * 1000
            val rows = withContext(Dispatchers.IO) {
                val db = ScanDatabase.getInstance(ctx)
                val pm = ctx.packageManager
                // Only genuine concerns: real sensor accesses (camera/mic/location) that
                // happened while the app was in the BACKGROUND. We deliberately do NOT surface
                // ordinary background network data here — virtually every app uses it (push,
                // sync), so flagging it just alarms users over something harmless.
                db.permissionAccessDao().recentAccesses(weekAgo, 40)
                    .filter { !it.foregroundAtStart }
                    .map { acc ->
                        val label = appLabel(pm, acc.packageName)
                        AttnRow(
                            timestamp = acc.startedAt,
                            severity = Severity.BAD,
                            packageName = acc.packageName,
                            title = "$label — ${SensorAccessFormat.title(acc).replaceFirstChar { it.lowercase() }}",
                            detail = SensorAccessFormat.detail(acc, now)
                        )
                    }
                    .sortedByDescending { it.timestamp }
                    .take(3)
            }

            if (!isAdded || view == null) return@launch

            attentionContainer.removeAllViews()
            attentionContainer.addView(tvAttentionEmpty)

            if (rows.isEmpty()) {
                tvAttentionEmpty.visibility = View.VISIBLE
                return@launch
            }
            tvAttentionEmpty.visibility = View.GONE

            val inflater = LayoutInflater.from(ctx)
            rows.forEachIndexed { i, item ->
                val rowView = inflater.inflate(
                    R.layout.item_home_finding_row, attentionContainer, false
                )
                bindAttnRow(rowView, item)
                attentionContainer.addView(rowView)
                if (i < rows.size - 1) {
                    val divider = View(ctx).apply {
                        setBackgroundColor(ContextCompat.getColor(ctx, R.color.v4_hairline))
                    }
                    attentionContainer.addView(divider, ViewGroup.LayoutParams.MATCH_PARENT, 1)
                }
            }
        }
    }

    private data class AttnRow(
        val timestamp: Long,
        val severity: Severity,
        val packageName: String,
        val title: String,
        val detail: String,
    )

    private fun appLabel(pm: android.content.pm.PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun bindAttnRow(row: View, item: AttnRow) {
        row.findViewById<View>(R.id.findingDot).setBackgroundResource(
            when (item.severity) {
                Severity.BAD -> R.drawable.bg_v4_sev_dot_bad
                Severity.WARN -> R.drawable.bg_v4_sev_dot_warn
                Severity.OK -> R.drawable.bg_v4_sev_dot_ok
            }
        )
        row.findViewById<TextView>(R.id.findingText).text = item.title
        row.findViewById<TextView>(R.id.findingDetail).text = item.detail

        row.setOnClickListener {
            val intent = Intent(row.context, AppDetailActivity::class.java).apply {
                putExtra(AppDetailActivity.EXTRA_PACKAGE_NAME, item.packageName)
            }
            startActivity(intent)
        }
    }

    private enum class Severity { BAD, WARN, OK }

    // ──────────────────────────────────────────────────────────────────────
    // Device security (background)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Records the device-security score in the background (no UI) so the PDF report and
     * other surfaces have real data to read. Best-effort; failures are logged and ignored.
     */
    private fun recordDeviceSecurity() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { DeviceSecurityChecker.checkAndSave(ctx) }
                    .onFailure { android.util.Log.e("HomeFragment", "Security check failed", it) }
            }
        }
    }

    /** A small confirmation haptic on scan completion — feels finished, can't crash. */
    private fun celebrate() {
        val haptic = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            android.view.HapticFeedbackConstants.CONFIRM
        else
            android.view.HapticFeedbackConstants.LONG_PRESS
        runCatching { btnScan.performHapticFeedback(haptic) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
