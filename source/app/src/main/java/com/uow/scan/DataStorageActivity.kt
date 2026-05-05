package com.uow.scan

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.uow.scan.data.ScanDatabase
import com.uow.scan.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DataStorageActivity : AppCompatActivity() {

    private data class Segment(val labelRes: Int, val colorRes: Int, val weightBytes: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_storage)

        findViewById<TextView>(R.id.tvTopBarTitle).setText(R.string.storage_v4_title)
        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        renderRetentionRows()

        findViewById<View>(R.id.rowKeepHistory).setOnClickListener { pickHistoryDays() }
        findViewById<View>(R.id.rowKeepVerdicts).setOnClickListener { pickVerdictsDays() }
        findViewById<View>(R.id.rowAutoPurge).setOnClickListener { pickAutoPurge() }
        findViewById<View>(R.id.rowExportDb).setOnClickListener { showStubDialog(R.string.storage_v4_backup_export) }
        findViewById<View>(R.id.rowRestoreDb).setOnClickListener { showStubDialog(R.string.storage_v4_backup_restore) }
        findViewById<FrameLayout>(R.id.btnClearCache).setOnClickListener { confirmClearCache() }
        findViewById<FrameLayout>(R.id.btnWipe).setOnClickListener { confirmWipe() }

        refreshStorage()
    }

    override fun onResume() {
        super.onResume()
        refreshStorage()
    }

    private fun renderRetentionRows() {
        findViewById<TextView>(R.id.tvKeepHistoryValue).text =
            getString(R.string.storage_v4_days_format, PreferencesManager.getKeepHistoryDays(this))
        findViewById<TextView>(R.id.tvKeepVerdictsValue).text =
            getString(R.string.storage_v4_days_format, PreferencesManager.getKeepVerdictsDays(this))
        findViewById<TextView>(R.id.tvAutoPurgeValue).text =
            PreferencesManager.getAutoPurgeCache(this)
    }

    private fun refreshStorage() {
        lifecycleScope.launch {
            val totals = withContext(Dispatchers.IO) {
                val ctx = applicationContext
                val dbFile = ctx.getDatabasePath("scan_db")
                val parent = dbFile.parentFile
                val totalBytes = listOf(dbFile, File(parent, "scan_db-wal"), File(parent, "scan_db-shm"))
                    .filter { it.exists() }.sumOf { it.length() }

                val db = ScanDatabase.getInstance(ctx)
                val scans = runCatching { db.scanResultDao().getTotalCount() }.getOrDefault(0)
                val verdicts = runCatching { db.smsVerdictDao().getCount() }.getOrDefault(0)
                val alerts = runCatching { db.alertDao().getCount() }.getOrDefault(0)
                val apps = runCatching { db.monitoredAppDao().getTotalCount() }.getOrDefault(0)

                StorageTotals(totalBytes, scans, verdicts, alerts, apps)
            }
            applyTotals(totals)
        }
    }

    private data class StorageTotals(
        val totalBytes: Long,
        val scans: Int, val verdicts: Int, val alerts: Int, val apps: Int,
    )

    private fun applyTotals(t: StorageTotals) {
        // Estimated bytes-per-row weights (gives us a plausible breakdown when the
        // actual file size is dominated by SQLite page padding).
        val weights = listOf(
            Segment(R.string.storage_v4_seg_history, R.color.v4_accent, t.scans.toLong() * 220L),
            Segment(R.string.storage_v4_seg_verdicts, R.color.v4_warn, t.verdicts.toLong() * 480L),
            Segment(R.string.storage_v4_seg_findings, R.color.v4_bad, t.alerts.toLong() * 160L),
            Segment(R.string.storage_v4_seg_app_meta, R.color.v4_fg2, t.apps.toLong() * 90L),
        )
        val totalWeight = weights.sumOf { it.weightBytes }.coerceAtLeast(1)
        // Scale weights proportionally to fill the actual on-disk total.
        val scaled = weights.map {
            val bytes = if (totalWeight > 0)
                (t.totalBytes.toDouble() * it.weightBytes / totalWeight).toLong()
            else 0L
            it to bytes
        }

        findViewById<TextView>(R.id.tvStorageTotal).text =
            "%.1f".format(t.totalBytes / (1024.0 * 1024.0))

        renderStackedBar(scaled)
        renderLegend(scaled)

        val cacheBytes = scaled.firstOrNull { it.first.colorRes == R.color.v4_bad }?.second ?: 0L
        findViewById<TextView>(R.id.tvClearCacheLabel).text =
            getString(R.string.storage_v4_btn_clear_cache, formatMb(cacheBytes))
    }

    private fun renderStackedBar(scaled: List<Pair<Segment, Long>>) {
        val bar = findViewById<LinearLayout>(R.id.storageBar)
        bar.removeAllViews()
        val total = scaled.sumOf { it.second }.coerceAtLeast(1)
        for ((seg, bytes) in scaled) {
            if (bytes <= 0) continue
            val v = View(this)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, bytes.toFloat() / total.toFloat())
            v.layoutParams = lp
            v.setBackgroundColor(ContextCompat.getColor(this, seg.colorRes))
            bar.addView(v)
        }
    }

    private fun renderLegend(scaled: List<Pair<Segment, Long>>) {
        val legend = findViewById<LinearLayout>(R.id.storageLegend)
        legend.removeAllViews()
        val density = resources.displayMetrics.density
        for ((seg, bytes) in scaled) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (7 * density).toInt() }
            }
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 2 * density
                    setColor(ContextCompat.getColor(this@DataStorageActivity, seg.colorRes))
                }
                layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), (8 * density).toInt())
            }
            val label = TextView(this).apply {
                text = getString(seg.labelRes)
                setTextColor(ContextCompat.getColor(this@DataStorageActivity, R.color.v4_fg1))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                    it.marginStart = (8 * density).toInt()
                }
            }
            val value = TextView(this).apply {
                text = "%.1f MB".format(bytes / (1024.0 * 1024.0))
                setTextColor(ContextCompat.getColor(this@DataStorageActivity, R.color.v4_fg3))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            row.addView(dot); row.addView(label); row.addView(value)
            legend.addView(row)
        }
    }

    private fun formatMb(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return "%.1f MB".format(mb)
    }

    private fun pickHistoryDays() {
        val options = arrayOf("7 days", "30 days", "90 days", "1 year")
        val values = intArrayOf(7, 30, 90, 365)
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_v4_keep_history)
            .setItems(options) { _, which ->
                PreferencesManager.setKeepHistoryDays(this, values[which])
                renderRetentionRows()
            }
            .show()
    }

    private fun pickVerdictsDays() {
        val options = arrayOf("7 days", "30 days", "90 days", "Forever")
        val values = intArrayOf(7, 30, 90, Int.MAX_VALUE)
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_v4_keep_verdicts)
            .setItems(options) { _, which ->
                PreferencesManager.setKeepVerdictsDays(this, values[which])
                renderRetentionRows()
            }
            .show()
    }

    private fun pickAutoPurge() {
        val options = arrayOf("Daily", "Weekly", "Monthly", "Never")
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_v4_auto_purge)
            .setItems(options) { _, which ->
                PreferencesManager.setAutoPurgeCache(this, options[which])
                renderRetentionRows()
            }
            .show()
    }

    private fun showStubDialog(titleRes: Int) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage("Backup tooling ships with v1.5.")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_v4_btn_clear_cache.let { "Clear cache?" })
            .setMessage("Removes the findings cache. Scan history and SMS verdicts are kept.")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        ScanDatabase.getInstance(applicationContext).alertDao().clearAll()
                    }
                    Toast.makeText(this@DataStorageActivity, "Cache cleared", Toast.LENGTH_SHORT).show()
                    refreshStorage()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_v4_btn_wipe)
            .setMessage(getString(R.string.storage_v4_wipe_helper))
            .setPositiveButton("Wipe") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val db = ScanDatabase.getInstance(applicationContext)
                        runCatching { db.scanResultDao().clearAll() }
                        runCatching { db.smsVerdictDao().clearAll() }
                        runCatching { db.alertDao().clearAll() }
                        runCatching { db.breachResultDao().clearAll() }
                    }
                    Toast.makeText(this@DataStorageActivity, "All S'CAN data wiped", Toast.LENGTH_SHORT).show()
                    refreshStorage()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
