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
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.uow.scan.R
import com.uow.scan.util.AppScanner
import com.uow.scan.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PermissionChartFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.chart_permission_landscape, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadData(view)
    }

    private fun loadData(view: View) {
        val chart = view.findViewById<BarChart>(R.id.barChart)
        val emptyState = view.findViewById<TextView>(R.id.tvEmptyState)

        viewLifecycleOwner.lifecycleScope.launch {
            val groupData = withContext(Dispatchers.IO) {
                val apps = AppScanner.scanInstalledApps(requireContext())
                val groups = PermissionHelper.buildPermissionGroups(apps)
                groups.map { it.displayName to (it.apps?.size ?: 0) }
                    .filter { it.second > 0 }
                    .sortedByDescending { it.second }
            }

            if (groupData.isEmpty()) {
                chart.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                return@launch
            }

            val entries = groupData.mapIndexed { index, (_, count) ->
                BarEntry(index.toFloat(), count.toFloat())
            }
            val labels = groupData.map { it.first }

            // Gradient from primary to primary_light
            val barColors = listOf(
                ContextCompat.getColor(requireContext(), R.color.primary),
                ContextCompat.getColor(requireContext(), R.color.primary_light),
                ContextCompat.getColor(requireContext(), R.color.info),
                ContextCompat.getColor(requireContext(), R.color.risk_medium),
                ContextCompat.getColor(requireContext(), R.color.risk_low),
                ContextCompat.getColor(requireContext(), R.color.primary_variant),
                ContextCompat.getColor(requireContext(), R.color.risk_high),
                ContextCompat.getColor(requireContext(), R.color.warning),
                ContextCompat.getColor(requireContext(), R.color.success)
            )

            val dataSet = BarDataSet(entries, "").apply {
                colors = barColors.take(entries.size)
                setDrawValues(true)
                valueTextColor = ContextCompat.getColor(requireContext(), R.color.text_secondary_dark)
                valueTextSize = 9f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return value.toInt().toString()
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
                    textSize = 8f
                    setDrawGridLines(false)
                    setDrawAxisLine(false)
                    granularity = 1f
                    labelCount = labels.size
                    labelRotationAngle = -45f
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            val idx = value.toInt()
                            return if (idx in labels.indices) labels[idx] else ""
                        }
                    }
                }

                axisLeft.apply {
                    textColor = ContextCompat.getColor(requireContext(), R.color.text_secondary_dark)
                    textSize = 9f
                    axisMinimum = 0f
                    granularity = 1f
                    setDrawGridLines(true)
                    gridColor = ContextCompat.getColor(requireContext(), R.color.border_dark)
                    setDrawAxisLine(false)
                }

                axisRight.isEnabled = false
                setExtraOffsets(4f, 4f, 4f, 12f)
                animateY(600)
                invalidate()
            }
        }
    }
}
