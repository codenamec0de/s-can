package com.uow.scan

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.uow.scan.service.ScanMonitorService
import com.uow.scan.ui.alerts.AlertsFragment
import com.uow.scan.ui.audit.AuditFragment
import com.uow.scan.ui.home.HomeFragment
import com.uow.scan.ui.settings.SettingsFragment
import com.uow.scan.util.BatteryOptimizationHelper
import com.uow.scan.worker.PermissionMonitorWorker
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private enum class Tab { HOME, APPS, ACTIVITY, SETTINGS }

    private lateinit var navHome: LinearLayout
    private lateinit var navApps: LinearLayout
    private lateinit var navActivity: LinearLayout
    private lateinit var navSettings: LinearLayout

    private lateinit var navHomeIcon: ImageView
    private lateinit var navAppsIcon: ImageView
    private lateinit var navActivityIcon: ImageView
    private lateinit var navSettingsIcon: ImageView

    private lateinit var navHomeLabel: TextView
    private lateinit var navAppsLabel: TextView
    private lateinit var navActivityLabel: TextView
    private lateinit var navSettingsLabel: TextView

    private var currentTab: Tab = Tab.HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindNavViews()
        setupNavListeners()
        schedulePermissionMonitor()
        startMonitorService()

        if (savedInstanceState == null) {
            val navigateTo = intent?.getStringExtra("navigate_to")
            val initial = when (navigateTo) {
                "alerts", "activity" -> Tab.ACTIVITY
                "apps", "audit" -> Tab.APPS
                "settings" -> Tab.SETTINGS
                else -> Tab.HOME
            }
            selectTab(initial)
        }
    }

    override fun onResume() {
        super.onResume()
        promptBatteryOptimization()
    }

    // ─── Public navigation API (called from fragments) ───────────────────────

    fun navigateToHome() = selectTab(Tab.HOME)
    fun navigateToApps() = selectTab(Tab.APPS)
    fun navigateToAudit() = selectTab(Tab.APPS) // legacy alias
    fun navigateToActivity() = selectTab(Tab.ACTIVITY)
    fun navigateToSettings() = selectTab(Tab.SETTINGS)

    // ─── Nav wiring ──────────────────────────────────────────────────────────

    private fun bindNavViews() {
        navHome = findViewById(R.id.navHome)
        navApps = findViewById(R.id.navApps)
        navActivity = findViewById(R.id.navActivity)
        navSettings = findViewById(R.id.navSettings)

        navHomeIcon = findViewById(R.id.navHomeIcon)
        navAppsIcon = findViewById(R.id.navAppsIcon)
        navActivityIcon = findViewById(R.id.navActivityIcon)
        navSettingsIcon = findViewById(R.id.navSettingsIcon)

        navHomeLabel = findViewById(R.id.navHomeLabel)
        navAppsLabel = findViewById(R.id.navAppsLabel)
        navActivityLabel = findViewById(R.id.navActivityLabel)
        navSettingsLabel = findViewById(R.id.navSettingsLabel)
    }

    private fun setupNavListeners() {
        navHome.setOnClickListener { selectTab(Tab.HOME) }
        navApps.setOnClickListener { selectTab(Tab.APPS) }
        navActivity.setOnClickListener { selectTab(Tab.ACTIVITY) }
        navSettings.setOnClickListener { selectTab(Tab.SETTINGS) }
    }

    private fun selectTab(tab: Tab) {
        if (tab == currentTab && supportFragmentManager.findFragmentById(R.id.fragmentContainer) != null) {
            applyNavStyling()
            return
        }
        currentTab = tab
        val fragment: Fragment = when (tab) {
            Tab.HOME -> HomeFragment()
            Tab.APPS -> AuditFragment()
            Tab.ACTIVITY -> AlertsFragment()
            Tab.SETTINGS -> SettingsFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
        applyNavStyling()
    }

    private fun applyNavStyling() {
        listOf(
            Tab.HOME to Triple(navHome, navHomeIcon, navHomeLabel),
            Tab.APPS to Triple(navApps, navAppsIcon, navAppsLabel),
            Tab.ACTIVITY to Triple(navActivity, navActivityIcon, navActivityLabel),
            Tab.SETTINGS to Triple(navSettings, navSettingsIcon, navSettingsLabel)
        ).forEach { (tab, views) ->
            val (container, icon, label) = views
            val active = tab == currentTab
            container.background =
                if (active) ContextCompat.getDrawable(this, R.drawable.bg_v2_nav_active) else null
            val tint = ContextCompat.getColor(this, if (active) R.color.white else R.color.fg_3)
            icon.setColorFilter(tint)
            label.visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    // ─── Services / workers ──────────────────────────────────────────────────

    private fun startMonitorService() {
        if (!ScanMonitorService.isRunning(this)) {
            ScanMonitorService.start(this)
        }
    }

    private fun schedulePermissionMonitor() {
        val workRequest = PeriodicWorkRequestBuilder<PermissionMonitorWorker>(
            15, TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PermissionMonitorWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun promptBatteryOptimization() {
        if (BatteryOptimizationHelper.isExempt(this)) return
        val prefs = getSharedPreferences("scan_prefs", MODE_PRIVATE)
        val prompted = prefs.getBoolean("battery_opt_prompted", false)
        if (prompted) return
        prefs.edit().putBoolean("battery_opt_prompted", true).apply()

        AlertDialog.Builder(this)
            .setTitle("Keep S'CAN Running")
            .setMessage(
                "For continuous protection, S'CAN needs to be exempt from battery optimization. " +
                "This ensures background monitoring keeps running even when the app is closed.\n\n" +
                "Tap \"Allow\" on the next screen."
            )
            .setPositiveButton("Enable") { _, _ ->
                BatteryOptimizationHelper.requestExemption(this)
            }
            .setNegativeButton("Later", null)
            .show()
    }
}
