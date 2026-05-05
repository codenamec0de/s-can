package com.uow.scan.ui.home

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.uow.scan.MainActivity
import com.uow.scan.R
import com.uow.scan.SmsOnboardingActivity
import com.uow.scan.SmsScamActivity
import com.uow.scan.WifiSecurityActivity
import com.uow.scan.data.ScanDatabase
import com.uow.scan.model.PermissionAlert
import com.uow.scan.util.AlertStorage
import com.uow.scan.util.PreferencesManager
import com.uow.scan.util.ScanRunner
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
    }

    override fun onResume() {
        super.onResume()
        renderGreetingAndStatus()
        renderTools()
        renderAttention()
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
        lifecycleScope.launch {
            val findings = withContext(Dispatchers.IO) {
                AlertStorage.getAlerts(ctx)
            }
            val findingCount = findings.size
            val appsCount = withContext(Dispatchers.IO) {
                ScanDatabase.getInstance(ctx).scanResultDao().getAll().size
            }
            val lastScan = PreferencesManager.getLastScanTime(ctx)

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
        lifecycleScope.launch {
            ScanRunner.runFullScan(ctx)
            PreferencesManager.setLastScanTime(ctx, System.currentTimeMillis())
            scanning = false
            btnScan.setText(R.string.home_cta_scan)
            btnScan.setIconResource(R.drawable.ic_glyph_refresh)
            btnScan.isEnabled = true
            renderGreetingAndStatus()
            renderAttention()
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
        bindComingSoonCard(
            view = toolTerminator,
            icon = R.drawable.ic_glyph_terminator,
            titleRes = R.string.home_tool_terminator_title,
            descRes = R.string.home_tool_terminator_desc,
        )
        bindComingSoonCard(
            view = toolNetworkMonitor,
            icon = R.drawable.ic_glyph_activity,
            titleRes = R.string.home_tool_netmon_title,
            descRes = R.string.home_tool_netmon_desc,
        )
        bindComingSoonCard(
            view = toolDnsLeak,
            icon = R.drawable.ic_glyph_shield,
            titleRes = R.string.home_tool_dns_title,
            descRes = R.string.home_tool_dns_desc,
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
        lifecycleScope.launch {
            val alerts = withContext(Dispatchers.IO) {
                AlertStorage.getAlerts(ctx)
                    .sortedByDescending { it.timestamp }
                    .take(3)
            }

            attentionContainer.removeAllViews()
            attentionContainer.addView(tvAttentionEmpty)

            if (alerts.isEmpty()) {
                tvAttentionEmpty.visibility = View.VISIBLE
                return@launch
            }
            tvAttentionEmpty.visibility = View.GONE

            val inflater = LayoutInflater.from(ctx)
            alerts.forEachIndexed { i, alert ->
                val row = inflater.inflate(
                    R.layout.item_home_finding_row, attentionContainer, false
                )
                bindFindingRow(row, alert, isLast = i == alerts.size - 1)
                attentionContainer.addView(row)
            }
        }
    }

    private fun bindFindingRow(row: View, alert: PermissionAlert, isLast: Boolean) {
        val severity = severityFor(alert)
        row.findViewById<View>(R.id.findingDot).setBackgroundResource(
            when (severity) {
                Severity.BAD -> R.drawable.bg_v4_sev_dot_bad
                Severity.WARN -> R.drawable.bg_v4_sev_dot_warn
                Severity.OK -> R.drawable.bg_v4_sev_dot_ok
            }
        )

        val title = row.findViewById<TextView>(R.id.findingText)
        val detail = row.findViewById<TextView>(R.id.findingDetail)
        title.text = naturalTitle(alert)
        detail.text = naturalDetail(alert)

        if (!isLast) {
            // Add a 1dp hairline divider after this row
            val divider = View(row.context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.v4_hairline))
            }
            attentionContainer.addView(divider, ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }

        row.setOnClickListener {
            val intent = Intent(row.context, AppDetailActivity::class.java).apply {
                putExtra("packageName", alert.packageName)
                putExtra("appName", alert.appName)
            }
            startActivity(intent)
        }
    }

    private enum class Severity { BAD, WARN, OK }

    private fun severityFor(alert: PermissionAlert): Severity {
        // Heuristic: silent-background camera/mic/location → bad; large bg duration → warn.
        val perm = alert.permissions.firstOrNull().orEmpty()
        return when {
            alert.isSilentBackground &&
                (perm.contains("CAMERA") || perm.contains("MICROPHONE") ||
                    perm.contains("RECORD_AUDIO") || perm.contains("LOCATION")) -> Severity.BAD
            alert.backgroundDurationMs > 60 * 60 * 1000L -> Severity.BAD
            else -> Severity.WARN
        }
    }

    /**
     * "Instagram — camera accessed in background", "WhatsApp — silent contacts read".
     * Reads as a natural finding, not a debug log line.
     */
    private fun naturalTitle(alert: PermissionAlert): String {
        val verb = permissionVerb(alert.permissions.firstOrNull())
        val prefix = if (alert.isSilentBackground) "silently " else ""
        return "${alert.appName} — $prefix$verb"
    }

    private fun permissionVerb(p: String?): String {
        if (p.isNullOrEmpty()) return "ran in the background"
        val tail = p.substringAfterLast('.')
        return when (tail) {
            "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "ACCESS_COARSE_LOCATION" ->
                "read your location"
            "CAMERA" -> "accessed the camera"
            "RECORD_AUDIO" -> "opened the microphone"
            "READ_CONTACTS" -> "read your contacts"
            "READ_SMS", "RECEIVE_SMS" -> "read your SMS"
            "READ_CALL_LOG", "READ_PHONE_STATE" -> "touched call data"
            "READ_CALENDAR" -> "read your calendar"
            "READ_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES",
            "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO" -> "read your media"
            else -> "used \"${tail.lowercase().replace('_', ' ')}\""
        }
    }

    /**
     * Relative timestamp + the strongest piece of evidence we have. The exact format
     * we pick depends on how recently the alert fired and whether it left a data
     * footprint — silent-background events read as "silent · just now" rather than
     * the misleading "0 min · 0 B".
     */
    private fun naturalDetail(alert: PermissionAlert): String {
        val whenStr = relativeTime(alert.timestamp)
        return when {
            alert.isSilentBackground -> "silent · $whenStr"
            alert.dataUsedBytes > 0 ->
                "${alert.formattedDuration} in background · ${alert.formattedDataUsed} · $whenStr"
            else -> "${alert.formattedDuration} in background · $whenStr"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
