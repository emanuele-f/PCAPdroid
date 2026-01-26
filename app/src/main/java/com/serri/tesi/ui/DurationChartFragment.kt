package serri.tesi.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.emanuelef.remote_capture.R
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import serri.tesi.repo.TrackerRepository

class DurationChartFragment : Fragment(R.layout.fragment_duration_chart) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val chart = view.findViewById<BarChart>(R.id.durationBarChart)
        loadDurationChart(chart)
    }

    private fun loadDurationChart(chart: BarChart) {
        val repo = TrackerRepository(requireContext())
        val histogram = repo.getConnectionDurationHistogram()

        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()

        histogram.entries.forEachIndexed { index, entry ->
            entries.add(BarEntry(index.toFloat(), entry.value.toFloat()))
            labels.add(entry.key)
        }

        val dataSet = BarDataSet(entries, "Numero connessioni per durata")
        dataSet.valueTextSize = 12f

        val data = BarData(dataSet)
        data.barWidth = 0.7f

        chart.data = data

        chart.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(labels)
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
            setDrawGridLines(false)
        }

        chart.axisRight.isEnabled = false
        chart.description.isEnabled = false
        chart.invalidate()
    }
}
