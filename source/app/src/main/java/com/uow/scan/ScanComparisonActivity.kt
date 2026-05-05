package com.uow.scan

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.uow.scan.data.ScanDatabase
import com.uow.scan.util.ScanSnapshotManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanComparisonActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var cardSummary: CardView
    private lateinit var tvSummaryTitle: TextView
    private lateinit var tvSummaryDates: TextView
    private lateinit var tvSummaryText: TextView
    private lateinit var tvNoComparison: TextView
    private lateinit var changesContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_comparison)

        initViews()
        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        loadComparison()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        cardSummary = findViewById(R.id.cardSummary)
        tvSummaryTitle = findViewById(R.id.tvSummaryTitle)
        tvSummaryDates = findViewById(R.id.tvSummaryDates)
        tvSummaryText = findViewById(R.id.tvSummaryText)
        tvNoComparison = findViewById(R.id.tvNoComparison)
        changesContainer = findViewById(R.id.changesContainer)
    }

    private fun loadComparison() {
        lifecycleScope.launch {
            val previous = withContext(Dispatchers.IO) {
                ScanSnapshotManager.loadPreviousSnapshot(this@ScanComparisonActivity)
            }

            if (previous == null) {
                cardSummary.visibility = View.GONE
                tvNoComparison.visibility = View.VISIBLE
                return@launch
            }

            val current = withContext(Dispatchers.IO) {
                ScanDatabase.getInstance(this@ScanComparisonActivity)
                    .scanResultDao().getAll()
            }

            if (current.isEmpty()) {
                cardSummary.visibility = View.GONE
                tvNoComparison.visibility = View.VISIBLE
                tvNoComparison.text = "No current scan data.\nRun a scan first."
                return@launch
            }

            val comparison = ScanSnapshotManager.compare(previous, current)
            displayComparison(comparison)
        }
    }

    private fun displayComparison(comp: ScanSnapshotManager.ScanComparison) {
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

        // Dates
        val prevDate = if (comp.previousScanTime > 0) dateFormat.format(Date(comp.previousScanTime)) else "Unknown"
        val currDate = if (comp.currentScanTime > 0) dateFormat.format(Date(comp.currentScanTime)) else "Unknown"
        tvSummaryDates.text = "$prevDate  \u2192  $currDate"

        // Summary text
        val parts = mutableListOf<String>()
        val appDiff = comp.currentAppCount - comp.previousAppCount
        when {
            appDiff > 0 -> parts.add("$appDiff new app${if (appDiff > 1) "s" else ""} installed")
            appDiff < 0 -> parts.add("${-appDiff} app${if (-appDiff > 1) "s" else ""} removed")
        }
        if (comp.newApps.isNotEmpty() && appDiff <= 0) {
            parts.add("${comp.newApps.size} new app${if (comp.newApps.size > 1) "s" else ""}")
        }
        if (comp.removedApps.isNotEmpty() && appDiff >= 0) {
            parts.add("${comp.removedApps.size} app${if (comp.removedApps.size > 1) "s" else ""} removed")
        }
        if (comp.riskChanges.isNotEmpty()) {
            parts.add("${comp.riskChanges.size} risk level change${if (comp.riskChanges.size > 1) "s" else ""}")
        }
        if (comp.permissionChanges.isNotEmpty()) {
            parts.add("${comp.permissionChanges.size} permission change${if (comp.permissionChanges.size > 1) "s" else ""}")
        }

        if (!comp.hasChanges) {
            tvSummaryTitle.text = "No changes detected"
            tvSummaryText.text = "Your app landscape is the same as last scan.\n${comp.currentAppCount} apps scanned."
        } else {
            tvSummaryTitle.text = "Since your last scan"
            tvSummaryText.text = parts.joinToString("\n")
        }

        // Build change sections
        changesContainer.removeAllViews()

        // New Apps
        if (comp.newApps.isNotEmpty()) {
            addSectionHeader("New Apps (${comp.newApps.size})")
            for (app in comp.newApps) {
                addChangeItem(
                    icon = R.drawable.ic_circle_complete,
                    iconColor = R.color.info,
                    primary = app.appName,
                    secondary = app.packageName,
                    badge = app.riskLevel,
                    badgeColor = riskColor(app.riskLevel)
                )
            }
        }

        // Removed Apps
        if (comp.removedApps.isNotEmpty()) {
            addSectionHeader("Removed Apps (${comp.removedApps.size})")
            for (app in comp.removedApps) {
                addChangeItem(
                    icon = R.drawable.ic_warning_circle,
                    iconColor = R.color.text_secondary_dark,
                    primary = app.appName,
                    secondary = app.packageName,
                    badge = "Removed",
                    badgeColor = R.color.text_secondary_dark
                )
            }
        }

        // Risk Changes
        if (comp.riskChanges.isNotEmpty()) {
            addSectionHeader("Risk Level Changes (${comp.riskChanges.size})")
            for (change in comp.riskChanges) {
                val worsened = riskOrdinal(change.newRisk) > riskOrdinal(change.oldRisk)
                addChangeItem(
                    icon = if (worsened) R.drawable.ic_warning_circle else R.drawable.ic_check_circle,
                    iconColor = if (worsened) R.color.risk_high else R.color.risk_low,
                    primary = change.appName,
                    secondary = "${change.oldRisk} \u2192 ${change.newRisk}",
                    badge = if (worsened) "\u2191 Risk" else "\u2193 Risk",
                    badgeColor = if (worsened) R.color.risk_high else R.color.risk_low
                )
            }
        }

        // Permission Changes
        if (comp.permissionChanges.isNotEmpty()) {
            addSectionHeader("Permission Changes (${comp.permissionChanges.size})")
            for (change in comp.permissionChanges) {
                val addedCount = change.addedPermissions.size
                val removedCount = change.removedPermissions.size
                val details = mutableListOf<String>()
                if (addedCount > 0) details.add("+$addedCount granted")
                if (removedCount > 0) details.add("-$removedCount revoked")

                addChangeItem(
                    icon = R.drawable.ic_info,
                    iconColor = if (addedCount > 0) R.color.risk_medium else R.color.risk_low,
                    primary = change.appName,
                    secondary = details.joinToString(", "),
                    badge = "${addedCount + removedCount} changes",
                    badgeColor = R.color.text_secondary_dark
                )
            }
        }
    }

    private fun addSectionHeader(title: String) {
        val tv = TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(this@ScanComparisonActivity, R.color.text_primary_dark))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24; bottomMargin = 8 }
            layoutParams = params
        }
        changesContainer.addView(tv)
    }

    private fun addChangeItem(
        icon: Int,
        iconColor: Int,
        primary: String,
        secondary: String,
        badge: String,
        badgeColor: Int
    ) {
        val itemView = layoutInflater.inflate(R.layout.item_scan_change, changesContainer, false)

        val ivIcon = itemView.findViewById<ImageView>(R.id.ivChangeIcon)
        val tvPrimary = itemView.findViewById<TextView>(R.id.tvChangePrimary)
        val tvSecondary = itemView.findViewById<TextView>(R.id.tvChangeSecondary)
        val tvBadge = itemView.findViewById<TextView>(R.id.tvChangeBadge)

        ivIcon.setImageResource(icon)
        ivIcon.setColorFilter(ContextCompat.getColor(this, iconColor))
        tvPrimary.text = primary
        tvSecondary.text = secondary
        tvBadge.text = badge
        tvBadge.setTextColor(ContextCompat.getColor(this, badgeColor))

        changesContainer.addView(itemView)
    }

    private fun riskColor(level: String): Int = when (level) {
        "HIGH" -> R.color.risk_high
        "MEDIUM" -> R.color.risk_medium
        else -> R.color.risk_low
    }

    private fun riskOrdinal(level: String): Int = when (level) {
        "HIGH" -> 2
        "MEDIUM" -> 1
        else -> 0
    }
}
