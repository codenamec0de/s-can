package com.uow.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.uow.scan.util.DataUsageHelper
import com.uow.scan.util.PreferencesManager

class PermissionsActivity : AppCompatActivity() {

    private enum class State { Auto, Idle, Requesting, Granted, Denied, Settings }

    private data class Row(
        val key: String,
        val rootId: Int,
        val icon: Int,
        val titleRes: Int,
        val whyRes: Int,
        val tags: List<String>,
        val optional: Boolean,
        val detailRes: Int? = null,
        val opensSettings: Boolean = false,
    )

    private val rows by lazy {
        listOf(
            Row("appAccess", R.id.rowAppAccess, R.drawable.ic_glyph_apps,
                R.string.perm_apps_title, R.string.perm_apps_why,
                listOf("QUERY_ALL_PACKAGES"), optional = false),
            Row("sms", R.id.rowSms, R.drawable.ic_glyph_sms,
                R.string.perm_sms_title, R.string.perm_sms_why,
                listOf("RECEIVE_SMS"), optional = false),
            Row("network", R.id.rowNetwork, R.drawable.ic_glyph_wifi,
                R.string.perm_network_title, R.string.perm_network_why,
                listOf("ACCESS_FINE_LOCATION", "NEARBY_WIFI_DEVICES"),
                optional = false, detailRes = R.string.perm_network_detail),
            Row("usage", R.id.rowUsage, R.drawable.ic_glyph_activity,
                R.string.perm_usage_title, R.string.perm_usage_why,
                listOf("PACKAGE_USAGE_STATS"), optional = false, opensSettings = true),
            Row("alerts", R.id.rowAlerts, R.drawable.ic_glyph_bell,
                R.string.perm_alerts_title, R.string.perm_alerts_why,
                listOf("POST_NOTIFICATIONS"), optional = true),
            Row("battery", R.id.rowBattery, R.drawable.ic_glyph_refresh,
                R.string.perm_battery_title, R.string.perm_battery_why,
                listOf("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
                optional = true, opensSettings = true),
        )
    }

    private val requiredKeys = listOf("appAccess", "sms", "network", "usage")

    private val states = mutableMapOf<String, State>()
    private var pendingSettingsKey: String? = null

    private lateinit var btnPrimary: MaterialButton
    private lateinit var btnDecideLater: TextView
    private lateinit var btnSkip: TextView
    private lateinit var tvProgressCount: TextView
    private lateinit var progressFill: View

    private val smsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> finishRowRequest("sms", granted) }

    private val networkLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        finishRowRequest("network", allGranted)
    }

    private val notificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> finishRowRequest("alerts", granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)

        btnPrimary = findViewById(R.id.btnPrimary)
        btnDecideLater = findViewById(R.id.btnDecideLater)
        btnSkip = findViewById(R.id.btnSkip)
        tvProgressCount = findViewById(R.id.tvProgressCount)
        progressFill = findViewById(R.id.progressFill)

        states["appAccess"] = State.Auto
        rows.filter { it.key != "appAccess" }.forEach { row ->
            states[row.key] = computeInitialState(row)
        }

        rows.forEach { renderRow(it) }
        renderProgress()
        renderPrimaryCta()

        btnSkip.setOnClickListener { finishOnboardingAndGoHome() }
        btnDecideLater.setOnClickListener { finishOnboardingAndGoHome() }
    }

    override fun onResume() {
        super.onResume()
        // Refresh states for permissions that may have changed in Settings
        rows.forEach { row ->
            val current = computeInitialState(row)
            // Only overwrite if user actually granted in settings; never downgrade auto/granted
            if (states[row.key] != State.Granted && current == State.Granted) {
                states[row.key] = State.Granted
                renderRow(row)
            } else if (pendingSettingsKey == row.key) {
                states[row.key] = current
                renderRow(row)
            }
        }
        pendingSettingsKey = null
        renderProgress()
        renderPrimaryCta()
    }

    private fun computeInitialState(row: Row): State {
        return when (row.key) {
            "appAccess" -> State.Auto
            "sms" -> if (hasPermission(Manifest.permission.RECEIVE_SMS)) State.Granted else State.Idle
            "network" -> {
                val locOk = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                val nearbyOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES) else true
                if (locOk && nearbyOk) State.Granted else State.Idle
            }
            "usage" -> if (DataUsageHelper.hasUsageStatsPermission(this)) State.Granted else State.Idle
            "alerts" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) State.Granted else State.Idle
                } else State.Granted
            }
            "battery" -> {
                val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
                if (pm.isIgnoringBatteryOptimizations(packageName)) State.Granted else State.Idle
            }
            else -> State.Idle
        }
    }

    private fun hasPermission(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRow(key: String) {
        val row = rows.first { it.key == key }
        states[key] = State.Requesting
        renderRow(row)
        when (key) {
            "sms" -> smsLauncher.launch(Manifest.permission.RECEIVE_SMS)
            "network" -> {
                val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms += Manifest.permission.NEARBY_WIFI_DEVICES
                }
                networkLauncher.launch(perms.toTypedArray())
            }
            "usage" -> {
                pendingSettingsKey = key
                states[key] = State.Settings
                renderRow(row)
                val opened = DataUsageHelper.requestUsageStatsPermission(this)
                if (!opened) {
                    // No Usage-Access screen on this device — don't strand the row in "Open".
                    pendingSettingsKey = null
                    states[key] = State.Idle
                    renderRow(row)
                }
            }
            "alerts" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    finishRowRequest("alerts", true)
                }
            }
            "battery" -> {
                pendingSettingsKey = key
                states[key] = State.Settings
                renderRow(row)
                openBatterySettings()
            }
        }
    }

    private fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun finishRowRequest(key: String, granted: Boolean) {
        states[key] = if (granted) State.Granted else State.Denied
        rows.firstOrNull { it.key == key }?.let { renderRow(it) }
        renderProgress()
        renderPrimaryCta()
    }

    private fun grantAllRequired() {
        // Only batch the RUNTIME-dialog permissions. Settings-based grants (usage, battery)
        // launch an external Settings activity, which — if fired mid-batch — abruptly steals
        // focus from the runtime dialogs and can leave a required row ungranted. Those rows
        // are handled by their own per-row "Allow" tap instead.
        val pending = requiredKeys
            .mapNotNull { key -> rows.firstOrNull { it.key == key } }
            .filter { it.key != "appAccess" && !it.opensSettings }
            .filter { states[it.key] == State.Idle || states[it.key] == State.Denied }
        pending.forEachIndexed { i, row ->
            btnPrimary.postDelayed({ requestRow(row.key) }, i * 250L)
        }
    }

    private fun renderRow(row: Row) {
        val rootView = findViewById<View>(row.rootId) ?: return
        val state = states[row.key] ?: State.Idle

        // Icon tile
        val iconTile = rootView.findViewById<View>(R.id.permIconTile)
        val iconView = rootView.findViewById<ImageView>(R.id.permIcon)
        val resolved = state == State.Auto || state == State.Granted
        iconTile.background = ContextCompat.getDrawable(
            this,
            if (resolved) R.drawable.bg_v4_perm_icon_tile_active else R.drawable.bg_v4_perm_icon_tile
        )
        iconView.setImageResource(row.icon)
        iconView.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (resolved) R.color.v4_accent else R.color.v4_fg1)
        )

        // Title + optional badge
        rootView.findViewById<TextView>(R.id.permTitle).setText(row.titleRes)
        rootView.findViewById<TextView>(R.id.permOptionalBadge).visibility =
            if (row.optional) View.VISIBLE else View.GONE

        // Why
        rootView.findViewById<TextView>(R.id.permWhy).setText(row.whyRes)

        // Tags
        val tagsContainer = rootView.findViewById<LinearLayout>(R.id.permTagsContainer)
        tagsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        row.tags.forEachIndexed { i, tag ->
            val tv = TextView(this).apply {
                text = tag
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 9.5f
                setTextColor(ContextCompat.getColor(context, R.color.v4_fg3))
                background = ContextCompat.getDrawable(context, R.drawable.bg_v4_perm_tag)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                letterSpacing = 0.02f
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (i > 0) marginStart = dp(6)
            }
            tagsContainer.addView(tv, lp)
        }

        // State pill
        val pill = rootView.findViewById<LinearLayout>(R.id.permStatePill)
        val pillIcon = rootView.findViewById<ImageView>(R.id.permStateIcon)
        val pillLabel = rootView.findViewById<TextView>(R.id.permStateLabel)
        val (pillBg, pillColorRes, labelRes, iconRes) = when (state) {
            State.Auto -> PillStyle(R.drawable.bg_v4_perm_pill_ok, R.color.v4_ok, R.string.perm_state_auto, R.drawable.ic_glyph_check)
            State.Idle -> PillStyle(R.drawable.bg_v4_perm_pill_idle, R.color.v4_fg2, R.string.perm_state_idle, null)
            State.Requesting -> PillStyle(R.drawable.bg_v4_perm_pill_accent, R.color.v4_accent, R.string.perm_state_requesting, null)
            State.Granted -> PillStyle(R.drawable.bg_v4_perm_pill_ok, R.color.v4_ok, R.string.perm_state_granted, R.drawable.ic_glyph_check)
            State.Denied -> PillStyle(R.drawable.bg_v4_perm_pill_warn, R.color.v4_warn, R.string.perm_state_denied, R.drawable.ic_glyph_warn)
            State.Settings -> PillStyle(R.drawable.bg_v4_perm_pill_accent, R.color.v4_accent, R.string.perm_state_settings, R.drawable.ic_glyph_arrow_right)
        }
        pill.background = ContextCompat.getDrawable(this, pillBg)
        pillLabel.setText(labelRes)
        pillLabel.setTextColor(ContextCompat.getColor(this, pillColorRes))
        if (iconRes != null) {
            pillIcon.setImageResource(iconRes)
            pillIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, pillColorRes)
            )
            pillIcon.visibility = View.VISIBLE
        } else {
            pillIcon.visibility = View.GONE
        }

        // Detail
        val detail = rootView.findViewById<TextView>(R.id.permDetail)
        when {
            state == State.Denied -> {
                detail.setText(R.string.perm_detail_denied)
                detail.setTextColor(ContextCompat.getColor(this, R.color.v4_warn))
                detail.visibility = View.VISIBLE
            }
            state == State.Settings -> {
                detail.setText(R.string.perm_detail_settings)
                detail.setTextColor(ContextCompat.getColor(this, R.color.v4_fg2))
                detail.visibility = View.VISIBLE
            }
            row.detailRes != null && (state == State.Idle) -> {
                detail.setText(row.detailRes)
                detail.setTextColor(ContextCompat.getColor(this, R.color.v4_fg2))
                detail.visibility = View.VISIBLE
            }
            else -> detail.visibility = View.GONE
        }

        // CTA
        val cta = rootView.findViewById<LinearLayout>(R.id.permCta)
        val ctaLabel = rootView.findViewById<TextView>(R.id.permCtaLabel)
        val ctaIcon = rootView.findViewById<ImageView>(R.id.permCtaIcon)
        val ctaText = when (state) {
            State.Idle -> R.string.perm_cta_allow
            State.Denied -> R.string.perm_cta_retry
            State.Settings -> R.string.perm_cta_open
            else -> null
        }
        if (ctaText != null) {
            cta.visibility = View.VISIBLE
            ctaLabel.setText(ctaText)
            val accentBg = state != State.Denied
            cta.background = ContextCompat.getDrawable(
                this,
                if (accentBg) R.drawable.bg_v4_perm_cta_accent else R.drawable.bg_v4_perm_cta_neutral
            )
            val ctaColor = ContextCompat.getColor(
                this,
                if (accentBg) R.color.v4_accent else R.color.v4_fg0
            )
            ctaLabel.setTextColor(ctaColor)
            ctaIcon.imageTintList = android.content.res.ColorStateList.valueOf(ctaColor)
            ctaIcon.visibility = if (state == State.Requesting) View.GONE else View.VISIBLE
            cta.setOnClickListener { requestRow(row.key) }
        } else {
            cta.visibility = View.GONE
            cta.setOnClickListener(null)
        }
    }

    private fun renderProgress() {
        // Track REQUIRED grants only, so the bar reads 100% once onboarding is actually
        // complete. Optional rows (alerts, battery) shouldn't hold the bar back.
        val granted = requiredKeys.count {
            states[it] == State.Granted || states[it] == State.Auto
        }
        val total = requiredKeys.size
        tvProgressCount.text = buildString {
            append(granted)
            append(" / ")
            append(total)
        }
        progressFill.post {
            val parent = progressFill.parent as View
            val target = (parent.width * (granted.toFloat() / total)).toInt()
            val lp = progressFill.layoutParams
            lp.width = target
            progressFill.layoutParams = lp
        }
    }

    private fun renderPrimaryCta() {
        val requiredDone = requiredKeys.all {
            val s = states[it]; s == State.Granted || s == State.Auto
        }
        if (requiredDone) {
            btnPrimary.setText(R.string.perm_cta_continue)
            btnPrimary.setIconResource(R.drawable.ic_glyph_arrow_right)
            btnPrimary.setOnClickListener { finishOnboardingAndGoHome() }
            btnDecideLater.visibility = View.GONE
            btnSkip.visibility = View.GONE
        } else {
            btnPrimary.setText(R.string.perm_cta_allow_required)
            btnPrimary.setIconResource(R.drawable.ic_glyph_check)
            btnPrimary.setOnClickListener { grantAllRequired() }
            btnDecideLater.visibility = View.VISIBLE
            btnSkip.visibility = View.VISIBLE
        }
    }

    private fun finishOnboardingAndGoHome() {
        PreferencesManager.setOnboardingComplete(this, true)
        PreferencesManager.setPermissionsGranted(this, true)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        finish()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private data class PillStyle(
        val bg: Int,
        val colorRes: Int,
        val labelRes: Int,
        val iconRes: Int?,
    )
}
