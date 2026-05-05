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
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.uow.scan.R
import com.uow.scan.data.ScanDatabase
import com.uow.scan.util.DataUsageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DataUsageChartFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.chart_data_usage, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadData(view)
    }

    private fun loadData(view: View) {
        val chart = view.findViewById<HorizontalBarChart>(R.id.barChart)
        val emptyState = view.findViewById<TextView>(R.id.tvEmptyState)

        viewLifecycleOwner.lifecycleScope.launch {
            val topUsers = withContext(Dispatchers.IO) {
                ScanDatabase.getInstance(requireContext()).alertDao().getTopDataUsers(5)
            }

            if (topUsers.isEmpty()) {
                chart.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                return@launch
            }

            val entries = topUsers.reversed().mapIndexed { index, user ->
                BarEntry(index.toFloat(), user.totalData.toFloat())
            }
            val appNames = topUsers.reversed().map { it.appName }

            val dataSet = BarDataSet(entries, "").apply {
                color = ContextCompat.getColor(requireContext(), R.color.primary)
                setDrawValues(true)
                valueTextColor = ContextCompat.getColor(requireContext(), R.color.text_secondary_dark)
                valueTextSize = 9f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return DataUsageHelper.formatBytes(value.toLong())
                    }
                }
            }

            chart.apply {
                data = BarData(dataSet).apply {
                    barWidth = 0.6f
                }
                description.isEnabled = false
                legend.isEnabled = false
                setBackgroundColor(Color.TRANSPARENT)
                setTouchEnabled(false)
                setFitBars(true)

                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    textColor = ContextCompat.getColor(requireContext(), R.color.text_secondary_dark)
                    textSize = 9f
                    setDrawGridLines(false)
                    setDrawAxisLine(false)
                    granularity = 1f
                    labelCount = appNames.size
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            val idx = value.toInt()
                            return if (idx in appNames.indices) {
                                val name = appNames[idx]
                                if (name.length > 12) name.take(12) + ".." else name
                            } else ""
                        }
                    }
                }

                axisLeft.apply {
                    textColor = ContextCompat.getColor(requireContext(), R.color.text_secondary_dark)
                    textSize = 8f
                    axisMinimum = 0f
                    setDrawGridLines(true)
                    gridColor = ContextCompat.getColor(requireContext(), R.color.border_dark)
                    setDrawAxisLine(false)
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return DataUsageHelper.formatBytes(value.toLong())
                        }
                    }
                }

                axisRight.isEnabled = false
                setExtraOffsets(4f, 4f, 8f, 4f)
                animateY(600)
                invalidate()
            }
        }
    }
}
