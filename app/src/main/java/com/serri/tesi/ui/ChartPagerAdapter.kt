package com.serri.tesi.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import serri.tesi.ui.DurationChartFragment
import serri.tesi.ui.ProtocolChartFragment
import serri.tesi.ui.TopAppsChartFragment

class ChartPagerAdapter(activity: AppCompatActivity) :
    FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ProtocolChartFragment()
            1 -> TopAppsChartFragment()
            else -> DurationChartFragment()
        }
    }
}
