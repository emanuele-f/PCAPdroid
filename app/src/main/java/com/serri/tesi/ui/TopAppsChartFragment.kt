package serri.tesi.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.emanuelef.remote_capture.R
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import serri.tesi.repo.TrackerRepository
import com.github.mikephil.charting.formatter.PercentFormatter


class TopAppsChartFragment : Fragment(R.layout.fragment_top_apps_chart) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val chart = view.findViewById<PieChart>(R.id.topAppsPieChart)
        loadTopAppsChart(chart)
    }

    private fun loadTopAppsChart(chart: PieChart) {
        val repo = TrackerRepository(requireContext())
        val dataMap = repo.getTopAppsByBytes()

        val entries = dataMap.map {
            PieEntry(it.value.toFloat(), it.key)
        }

        val dataSet = PieDataSet(entries, "")
        dataSet.colors = listOf(
            Color.rgb(66, 133, 244),
            Color.rgb(219, 68, 55),
            Color.rgb(244, 180, 0),
            Color.rgb(15, 157, 88),
            Color.GRAY
        )

        dataSet.valueTextSize = 12f
        dataSet.valueFormatter = PercentFormatter(chart)

        chart.data = PieData(dataSet)
        chart.description.isEnabled = false

        chart.setUsePercentValues(true)
        chart.setDrawEntryLabels(false)

        chart.legend.isEnabled = true
        chart.legend.textSize = 12f
        chart.legend.formSize = 12f

        chart.invalidate()
    }
}
