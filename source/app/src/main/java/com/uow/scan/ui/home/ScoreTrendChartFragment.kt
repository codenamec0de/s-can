package com.uow.scan.ui.home

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.uow.scan.R
import com.uow.scan.data.ScanDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScoreTrendChartFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.chart_score_trend, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadData(view)
    }

    private fun loadData(view: View) {
        val chart = view.findViewById<LineChart>(R.id.lineChart)
        val emptyState = view.findViewById<TextView>(R.id.tvEmptyState)

        viewLifecycleOwner.lifecycleScope.launch {
            val checks = withContext(Dispatchers.IO) {
                ScanDatabase.getInstance(requireContext()).deviceCheckDao().getAll()
            }

            if (checks.size < 2) {
                chart.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                return@launch
            }

            val entries = checks.mapIndexed { index, check ->
                Entry(index.toFloat(), check.score.toFloat())
            }

            val dataSet = LineDataSet(entries, "Score").apply {
                color = ContextCompat.getColor(requireContext(), R.color.primary)
                lineWidth = 2.5f
                setDrawCircles(true)
                setCircleColor(ContextCompat.getColor(requireContext(), R.color.primary))
                circleRadius = 3f
                setDrawCircleHole(false)
                setDrawValues(false)
                setDrawFilled(true)
                fillColor = ContextCompat.getColor(requireContext(), R.color.primary)
                fillAlpha = 30
                mode = LineDataSet.Mode.CUBIC_BEZIER
            }

            val dateFormat = SimpleDateFormat("dd/MM", Locale.getDefault())
            val timestamps = checks.map { it.checkedAt }

            chart.apply {
                data = LineData(dataSet)
                description.isEnabled = false
                legend.isEnabled = false
                setBackgroundColor(Color.TRANSPARENT)
                setTouchEnabled(true)
                setScaleEnabled(false)
                setPinchZoom(false)

                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    textColor = ContextCompat.getColor(requireContext(), R.color.text_secondary_dark)
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
                    labelCount = minOf(checks.size, 5)
                }

                axisLeft.apply {
                    textColor = ContextCompat.getColor(requireContext(), R.color.text_secondary_dark)
                    textSize = 9f
                    axisMinimum = 0f
                    axisMaximum = 100f
                    setDrawGridLines(true)
                    gridColor = ContextCompat.getColor(requireContext(), R.color.border_dark)
                    setDrawAxisLine(false)
                    // Zone indicators
                    addLimitLine(LimitLine(90f).apply {
                        lineColor = ContextCompat.getColor(requireContext(), R.color.risk_low)
                        lineWidth = 0.5f
                        enableDashedLine(8f, 4f, 0f)
                    })
                    addLimitLine(LimitLine(50f).apply {
                        lineColor = ContextCompat.getColor(requireContext(), R.color.risk_medium)
                        lineWidth = 0.5f
                        enableDashedLine(8f, 4f, 0f)
                    })
                }

                axisRight.isEnabled = false
                setExtraOffsets(4f, 4f, 4f, 4f)
                animateX(600)
                invalidate()
            }
        }
    }
}
