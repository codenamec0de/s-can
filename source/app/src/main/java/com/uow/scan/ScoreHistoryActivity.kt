package com.uow.scan

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.DeviceCheckEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScoreHistoryActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var tvCurrentScore: TextView
    private lateinit var tvCurrentGrade: TextView
    private lateinit var tvScoreChange: TextView
    private lateinit var lineChart: LineChart
    private lateinit var tvChartEmpty: TextView
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var historyList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_score_history)

        initViews()
        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        loadData()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        tvCurrentScore = findViewById(R.id.tvCurrentScore)
        tvCurrentGrade = findViewById(R.id.tvCurrentGrade)
        tvScoreChange = findViewById(R.id.tvScoreChange)
        lineChart = findViewById(R.id.lineChart)
        tvChartEmpty = findViewById(R.id.tvChartEmpty)
        tvHistoryEmpty = findViewById(R.id.tvHistoryEmpty)
        historyList = findViewById(R.id.historyList)
    }

    private fun loadData() {
        lifecycleScope.launch {
            val checks = withContext(Dispatchers.IO) {
                ScanDatabase.getInstance(this@ScoreHistoryActivity)
                    .deviceCheckDao().getAll()
            }

            if (checks.isEmpty()) {
                tvCurrentScore.text = "--"
                tvCurrentGrade.text = "No checks yet"
                tvChartEmpty.visibility = View.VISIBLE
                lineChart.visibility = View.GONE
                tvHistoryEmpty.visibility = View.VISIBLE
                return@launch
            }

            // Latest is the last element (ordered ASC)
            val latest = checks.last()
            updateCurrentScore(latest, checks)
            setupChart(checks)
            buildHistoryList(checks)
        }
    }

    private fun updateCurrentScore(latest: DeviceCheckEntity, all: List<DeviceCheckEntity>) {
        tvCurrentScore.text = latest.score.toString()

        val grade = when {
            latest.score >= 90 -> "Excellent"
            latest.score >= 70 -> "Good"
            latest.score >= 50 -> "Fair"
            else -> "At Risk"
        }
        val scoreColor = when {
            latest.score >= 90 -> R.color.risk_low
            latest.score >= 70 -> R.color.info
            latest.score >= 50 -> R.color.risk_medium
            else -> R.color.risk_high
        }

        val passedCount = countPassed(latest)
        tvCurrentGrade.text = "$grade \u2022 $passedCount/8 passed"
        tvCurrentScore.setTextColor(ContextCompat.getColor(this, scoreColor))

        // Show change from previous check
        if (all.size >= 2) {
            val previous = all[all.size - 2]
            val diff = latest.score - previous.score
            when {
                diff > 0 -> {
                    tvScoreChange.text = "\u2191 +$diff since last check"
                    tvScoreChange.setTextColor(ContextCompat.getColor(this, R.color.risk_low))
                    tvScoreChange.visibility = View.VISIBLE
                }
                diff < 0 -> {
                    tvScoreChange.text = "\u2193 $diff since last check"
                    tvScoreChange.setTextColor(ContextCompat.getColor(this, R.color.risk_high))
                    tvScoreChange.visibility = View.VISIBLE
                }
                else -> {
                    tvScoreChange.text = "No change since last check"
                    tvScoreChange.setTextColor(ContextCompat.getColor(this, R.color.text_secondary_dark))
                    tvScoreChange.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupChart(checks: List<DeviceCheckEntity>) {
        if (checks.size < 2) {
            lineChart.visibility = View.GONE
            tvChartEmpty.visibility = View.VISIBLE
            return
        }

        val entries = checks.mapIndexed { index, check ->
            Entry(index.toFloat(), check.score.toFloat())
        }

        val dataSet = LineDataSet(entries, "Score").apply {
            color = ContextCompat.getColor(this@ScoreHistoryActivity, R.color.primary)
            lineWidth = 2.5f
            setDrawCircles(true)
            setCircleColor(ContextCompat.getColor(this@ScoreHistoryActivity, R.color.primary))
            circleRadius = 4f
            setDrawCircleHole(true)
            circleHoleColor = ContextCompat.getColor(this@ScoreHistoryActivity, R.color.card_dark)
            circleHoleRadius = 2f
            setDrawValues(true)
            valueTextColor = ContextCompat.getColor(this@ScoreHistoryActivity, R.color.text_secondary_dark)
            valueTextSize = 9f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = value.toInt().toString()
            }
            setDrawFilled(true)
            fillColor = ContextCompat.getColor(this@ScoreHistoryActivity, R.color.primary)
            fillAlpha = 25
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        val dateFormat = SimpleDateFormat("dd/MM", Locale.getDefault())
        val timestamps = checks.map { it.checkedAt }

        lineChart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            setTouchEnabled(true)
            setScaleEnabled(false)
            setPinchZoom(false)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = ContextCompat.getColor(this@ScoreHistoryActivity, R.color.text_secondary_dark)
                textSize = 9f
                setDrawGridLines(false)
                setDrawAxisLine(false)
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        val idx = value.toInt()
                        return if (idx in timestamps.indices) {
                            dateFormat.format(Date(timestamps[idx]))
                        } else ""
                    }
                }
                labelCount = minOf(checks.size, 6)
            }

            axisLeft.apply {
                textColor = ContextCompat.getColor(this@ScoreHistoryActivity, R.color.text_secondary_dark)
                textSize = 9f
                axisMinimum = 0f
                axisMaximum = 100f
                setDrawGridLines(true)
                gridColor = ContextCompat.getColor(this@ScoreHistoryActivity, R.color.border_dark)
                setDrawAxisLine(false)
                addLimitLine(LimitLine(90f).apply {
                    lineColor = ContextCompat.getColor(this@ScoreHistoryActivity, R.color.risk_low)
                    lineWidth = 0.5f
                    enableDashedLine(8f, 4f, 0f)
                    label = "Excellent"
                    textColor = ContextCompat.getColor(this@ScoreHistoryActivity, R.color.risk_low)
                    textSize = 8f
                })
                addLimitLine(LimitLine(50f).apply {
                    lineColor = ContextCompat.getColor(this@ScoreHistoryActivity, R.color.risk_medium)
                    lineWidth = 0.5f
                    enableDashedLine(8f, 4f, 0f)
                    label = "Fair"
                    textColor = ContextCompat.getColor(this@ScoreHistoryActivity, R.color.risk_medium)
                    textSize = 8f
                })
            }

            axisRight.isEnabled = false
            setExtraOffsets(4f, 8f, 4f, 4f)
            animateX(800)
            invalidate()
        }
    }

    private fun buildHistoryList(checks: List<DeviceCheckEntity>) {
        historyList.removeAllViews()
        val dateFormat = SimpleDateFormat("MMM dd, yyyy \u2022 HH:mm", Locale.getDefault())

        // Show newest first
        val reversed = checks.reversed()
        for ((index, check) in reversed.withIndex()) {
            val itemView = layoutInflater.inflate(R.layout.item_score_history, historyList, false)

            val tvScore = itemView.findViewById<TextView>(R.id.tvScore)
            val tvGrade = itemView.findViewById<TextView>(R.id.tvGrade)
            val tvDate = itemView.findViewById<TextView>(R.id.tvDate)
            val tvPassedChecks = itemView.findViewById<TextView>(R.id.tvPassedChecks)
            val tvChange = itemView.findViewById<TextView>(R.id.tvChange)
            val viewScoreBg = itemView.findViewById<View>(R.id.viewScoreBg)

            tvScore.text = check.score.toString()
            tvDate.text = dateFormat.format(Date(check.checkedAt))

            val grade = when {
                check.score >= 90 -> "Excellent"
                check.score >= 70 -> "Good"
                check.score >= 50 -> "Fair"
                else -> "At Risk"
            }
            tvGrade.text = grade

            val passed = countPassed(check)
            tvPassedChecks.text = "$passed of 8 checks passed"

            // Score background tint
            val bgTint = when {
                check.score >= 90 -> R.color.risk_low
                check.score >= 70 -> R.color.info
                check.score >= 50 -> R.color.risk_medium
                else -> R.color.risk_high
            }
            viewScoreBg.background.setTint(ContextCompat.getColor(this, bgTint))

            // Change from next older check (index+1 in reversed = previous chronologically)
            if (index < reversed.size - 1) {
                val older = reversed[index + 1]
                val diff = check.score - older.score
                when {
                    diff > 0 -> {
                        tvChange.text = "+$diff"
                        tvChange.setTextColor(ContextCompat.getColor(this, R.color.risk_low))
                    }
                    diff < 0 -> {
                        tvChange.text = "$diff"
                        tvChange.setTextColor(ContextCompat.getColor(this, R.color.risk_high))
                    }
                    else -> {
                        tvChange.text = "0"
                        tvChange.setTextColor(ContextCompat.getColor(this, R.color.text_secondary_dark))
                    }
                }
            } else {
                tvChange.text = "-"
                tvChange.setTextColor(ContextCompat.getColor(this, R.color.text_secondary_dark))
            }

            historyList.addView(itemView)
        }
    }

    private fun countPassed(check: DeviceCheckEntity): Int {
        var count = 0
        if (check.screenLockEnabled) count++
        if (check.biometricEnrolled) count++
        if (check.diskEncrypted) count++
        if (check.osUpToDate) count++
        if (check.developerOptionsOff) count++
        if (check.usbDebuggingOff) count++
        if (check.unknownSourcesOff) count++
        if (check.wifiNetworkSafe) count++
        // scanProtectionOn is not stored in entity, so we count 8 stored + skip that one
        return count
    }
}
