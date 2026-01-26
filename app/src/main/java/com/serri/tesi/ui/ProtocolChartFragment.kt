package serri.tesi.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.emanuelef.remote_capture.R
import serri.tesi.repo.TrackerRepository
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter

class ProtocolChartFragment : Fragment(R.layout.fragment_protocol_chart) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val protocolBarChart = view.findViewById<BarChart>(R.id.protocolBarChart)
        loadProtocolBarChart(protocolBarChart)
    }

    private fun loadProtocolBarChart(chart: BarChart) {

        val repo = TrackerRepository(requireContext())
        val bytesByProtocol = repo.getBytesGroupedByProtocol()

        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()

        bytesByProtocol.entries.forEachIndexed { index, entry ->
            entries.add(BarEntry(index.toFloat(), entry.value.toFloat()))
            labels.add(entry.key)
        }

        val dataSet = BarDataSet(entries, "Byte totali per protocollo")
        dataSet.valueTextSize = 12f

        val barData = BarData(dataSet)
        barData.barWidth = 0.6f

        chart.data = barData

        chart.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(labels)
            granularity = 1f
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
        }

        chart.axisRight.isEnabled = false
        chart.description.isEnabled = false
        chart.invalidate()
    }
}
