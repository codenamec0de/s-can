package com.uow.scan

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.uow.scan.util.PreferencesManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ScanScheduleActivity : AppCompatActivity() {

    private lateinit var tvScheduleTime: TextView
    private lateinit var tvNextScan: TextView
    private lateinit var tvNextScanMeta: TextView

    private data class FreqMapping(val key: String, val frameId: Int, val labelId: Int)

    private val freqMappings = listOf(
        FreqMapping("realtime", R.id.freqRealtime, R.id.tvFreqRealtime),
        FreqMapping("hourly", R.id.freqHourly, R.id.tvFreqHourly),
        FreqMapping("daily", R.id.freqDaily, R.id.tvFreqDaily),
        FreqMapping("manual", R.id.freqManual, R.id.tvFreqManual),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_schedule)

        findViewById<TextView>(R.id.tvTopBarTitle).setText(R.string.schedule_v4_title)
        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        tvScheduleTime = findViewById(R.id.tvScheduleTime)
        tvNextScan = findViewById(R.id.tvNextScan)
        tvNextScanMeta = findViewById(R.id.tvNextScanMeta)

        val prefs = PreferencesManager.getScanSchedulePrefs(this)
        applyFrequency(prefs.frequency)
        tvScheduleTime.text = prefs.time

        bindFrequencyTaps()
        bindConditionSwitches(prefs)

        findViewById<View>(R.id.rowTime).setOnClickListener { pickScheduleTime() }
        findViewById<FrameLayout>(R.id.btnRunNow).setOnClickListener { runScanNow() }

        renderNextScan()
    }

    private fun bindFrequencyTaps() {
        findViewById<View>(R.id.freqRealtime).setOnClickListener { setFrequency("realtime") }
        findViewById<View>(R.id.freqHourly).setOnClickListener { setFrequency("hourly") }
        findViewById<View>(R.id.freqDaily).setOnClickListener { setFrequency("daily") }
        findViewById<View>(R.id.freqManual).setOnClickListener { setFrequency("manual") }
    }

    private fun bindConditionSwitches(prefs: PreferencesManager.ScanSchedulePrefs) {
        bindSwitch(R.id.swWifiOnly, R.id.rowWifiOnly, prefs.wifiOnly, PreferencesManager.ScanKeys.WIFI_ONLY)
        bindSwitch(R.id.swCharging, R.id.rowCharging, prefs.charging, PreferencesManager.ScanKeys.CHARGING)
        bindSwitch(R.id.swDeep, R.id.rowDeep, prefs.deepScan, PreferencesManager.ScanKeys.DEEP)
    }

    private fun bindSwitch(switchId: Int, rowId: Int, initial: Boolean, key: String) {
        val sw = findViewById<SwitchCompat>(switchId)
        val row = findViewById<View>(rowId)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, c ->
            PreferencesManager.setScanCondition(this, key, c)
            renderNextScan()
        }
        row.setOnClickListener { sw.isChecked = !sw.isChecked }
    }

    private fun setFrequency(value: String) {
        PreferencesManager.setScanFrequency(this, value)
        applyFrequency(value)
        renderNextScan()
    }

    private fun applyFrequency(active: String) {
        for (m in freqMappings) {
            val isActive = active == m.key
            findViewById<View>(m.frameId)
                .setBackgroundResource(if (isActive) R.drawable.bg_v4_apps_segment_active else 0)
            findViewById<TextView>(m.labelId).setTextColor(
                ContextCompat.getColor(this, if (isActive) R.color.v4_fg0 else R.color.v4_fg2)
            )
        }
    }

    private fun pickScheduleTime() {
        val parts = tvScheduleTime.text.toString().split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 3
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        TimePickerDialog(this, { _, h, m ->
            val picked = String.format("%02d:%02d", h, m)
            tvScheduleTime.text = picked
            PreferencesManager.setScanTime(this, picked)
            renderNextScan()
        }, hour, minute, true).show()
    }

    private fun renderNextScan() {
        val prefs = PreferencesManager.getScanSchedulePrefs(this)
        val parts = prefs.time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 3
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val isToday = isSameDay(cal, Calendar.getInstance())
        val prefix = if (isToday) "Today" else "Tonight"
        tvNextScan.text = "$prefix · ${prefs.time}"

        val deltaMs = cal.timeInMillis - System.currentTimeMillis()
        val hours = TimeUnit.MILLISECONDS.toHours(deltaMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMs) - hours * 60
        val countdown = "${hours}h ${minutes}m"
        val net = if (prefs.wifiOnly)
            getString(R.string.schedule_v4_meta_wifi)
        else
            getString(R.string.schedule_v4_meta_any)
        tvNextScanMeta.text = getString(R.string.schedule_v4_next_meta_format, countdown, net)
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun runScanNow() {
        startActivity(Intent(this, ScanActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
