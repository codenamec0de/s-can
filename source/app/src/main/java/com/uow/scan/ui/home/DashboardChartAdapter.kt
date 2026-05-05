package com.uow.scan.ui.home

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class DashboardChartAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> ScoreTrendChartFragment()
        1 -> RiskDistributionChartFragment()
        2 -> DataUsageChartFragment()
        3 -> PermissionChartFragment()
        else -> ScoreTrendChartFragment()
    }
}
