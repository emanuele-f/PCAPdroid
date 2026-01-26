package serri.tesi.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.emanuelef.remote_capture.R
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.serri.tesi.ui.ChartPagerAdapter

class TesiAnalysisActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tesi_analysis)

        val pager = findViewById<ViewPager2>(R.id.chartPager)
        val tabs = findViewById<TabLayout>(R.id.chartIndicator)

        pager.adapter = ChartPagerAdapter(this)
        TabLayoutMediator(tabs, pager) { _, _ -> }.attach()
    }
}
