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
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.uow.scan.R
import com.uow.scan.data.ScanDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RiskDistributionChartFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.chart_risk_distribution, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadData(view)
    }

    private fun loadData(view: View) {
        val chart = view.findViewById<PieChart>(R.id.pieChart)
        val emptyState = view.findViewById<TextView>(R.id.tvEmptyState)

        viewLifecycleOwner.lifecycleScope.launch {
            val db = ScanDatabase.getInstance(requireContext())
            val (high, medium, low, total) = withContext(Dispatchers.IO) {
                val h = db.scanResultDao().countByRiskLevel("HIGH")
                val m = db.scanResultDao().countByRiskLevel("MEDIUM")
                val l = db.scanResultDao().countByRiskLevel("LOW")
                val t = db.scanResultDao().getTotalCount()
                arrayOf(h, m, l, t)
            }

            if (total == 0) {
                chart.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                return@launch
            }

            val entries = mutableListOf<PieEntry>()
            val colors = mutableListOf<Int>()

            if (high > 0) {
                entries.add(PieEntry(high.toFloat(), "High"))
                colors.add(ContextCompat.getColor(requireContext(), R.color.risk_high))
            }
            if (medium > 0) {
                entries.add(PieEntry(medium.toFloat(), "Medium"))
                colors.add(ContextCompat.getColor(requireContext(), R.color.risk_medium))
            }
            if (low > 0) {
                entries.add(PieEntry(low.toFloat(), "Low"))
                colors.add(ContextCompat.getColor(requireContext(), R.color.risk_low))
            }

            val dataSet = PieDataSet(entries, "").apply {
                this.colors = colors
                sliceSpace = 2f
                selectionShift = 4f
                setDrawValues(true)
                valueTextColor = Color.WHITE
                valueTextSize = 11f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return value.toInt().toString()
                    }
                }
            }

            chart.apply {
                data = PieData(dataSet)
                description.isEnabled = false
                legend.apply {
                    textColor = ContextCompat.getColor(requireContext(), R.color.text_secondary_dark)
                    textSize = 10f
                }
                setBackgroundColor(Color.TRANSPARENT)
                setHoleColor(ContextCompat.getColor(requireContext(), R.color.card_dark))
                holeRadius = 55f
                transparentCircleRadius = 60f
                setTransparentCircleColor(ContextCompat.getColor(requireContext(), R.color.card_dark))
                setTransparentCircleAlpha(100)
                setDrawEntryLabels(false)

                // Center text shows total
                centerText = "$total\napps"
                setCenterTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary_dark))
                setCenterTextSize(13f)

                setExtraOffsets(4f, 4f, 4f, 4f)
                animateY(600)
                invalidate()
            }
        }
    }
}
