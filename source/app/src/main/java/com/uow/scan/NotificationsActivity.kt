package com.uow.scan

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.uow.scan.util.PreferencesManager

class NotificationsActivity : AppCompatActivity() {

    private lateinit var swQuiet: SwitchCompat
    private lateinit var quietTimeRow: LinearLayout
    private lateinit var tvQuietFromValue: TextView
    private lateinit var tvQuietToValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)

        findViewById<TextView>(R.id.tvTopBarTitle).setText(R.string.notif_v4_title)
        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        val prefs = PreferencesManager.getNotificationPrefs(this)

        bindSwitch(R.id.swFindings, R.id.rowFindings, prefs.findings, PreferencesManager.NotifKeys.FINDINGS)
        bindSwitch(R.id.swSms, R.id.rowSms, prefs.sms, PreferencesManager.NotifKeys.SMS)
        bindSwitch(R.id.swScans, R.id.rowScans, prefs.scans, PreferencesManager.NotifKeys.SCANS)
        bindSwitch(R.id.swWeekly, R.id.rowWeekly, prefs.weekly, PreferencesManager.NotifKeys.WEEKLY)
        bindSwitch(R.id.swScoreDrop, R.id.rowScoreDrop, prefs.scoreDrop, PreferencesManager.NotifKeys.SCORE_DROP)
        bindSwitch(R.id.swSound, R.id.rowSound, prefs.sound, PreferencesManager.NotifKeys.SOUND)
        bindSwitch(R.id.swVibrate, R.id.rowVibrate, prefs.vibrate, PreferencesManager.NotifKeys.VIBRATE)
        bindSwitch(R.id.swLockScreen, R.id.rowLockScreen, prefs.lockScreen, PreferencesManager.NotifKeys.LOCK_SCREEN)

        swQuiet = findViewById(R.id.swQuiet)
        quietTimeRow = findViewById(R.id.quietTimeRow)
        tvQuietFromValue = findViewById(R.id.tvQuietFromValue)
        tvQuietToValue = findViewById(R.id.tvQuietToValue)

        swQuiet.isChecked = prefs.quietEnabled
        applyQuietEnabled(prefs.quietEnabled)
        tvQuietFromValue.text = prefs.quietStart
        tvQuietToValue.text = prefs.quietEnd

        swQuiet.setOnCheckedChangeListener { _, checked ->
            PreferencesManager.setQuietHours(this, checked, tvQuietFromValue.text.toString(), tvQuietToValue.text.toString())
            applyQuietEnabled(checked)
        }
        findViewById<FrameLayout>(R.id.quietFromBox).setOnClickListener {
            pickTime(tvQuietFromValue.text.toString()) { picked ->
                tvQuietFromValue.text = picked
                PreferencesManager.setQuietHours(this, swQuiet.isChecked, picked, tvQuietToValue.text.toString())
            }
        }
        findViewById<FrameLayout>(R.id.quietToBox).setOnClickListener {
            pickTime(tvQuietToValue.text.toString()) { picked ->
                tvQuietToValue.text = picked
                PreferencesManager.setQuietHours(this, swQuiet.isChecked, tvQuietFromValue.text.toString(), picked)
            }
        }
    }

    private fun bindSwitch(switchId: Int, rowId: Int, initial: Boolean, key: String) {
        val sw = findViewById<SwitchCompat>(switchId)
        val row = findViewById<View>(rowId)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, c -> PreferencesManager.setNotificationFlag(this, key, c) }
        // Tap-anywhere-on-row toggles the switch (matches Android settings UX).
        row.setOnClickListener { sw.isChecked = !sw.isChecked }
    }

    private fun applyQuietEnabled(enabled: Boolean) {
        quietTimeRow.alpha = if (enabled) 1f else 0.5f
        for (i in 0 until quietTimeRow.childCount) {
            quietTimeRow.getChildAt(i).isClickable = enabled
        }
    }

    private fun pickTime(current: String, onPicked: (String) -> Unit) {
        val parts = current.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 22
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        TimePickerDialog(this, { _, h, m ->
            onPicked(String.format("%02d:%02d", h, m))
        }, hour, minute, true).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
