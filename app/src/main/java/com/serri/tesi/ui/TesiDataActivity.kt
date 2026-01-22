package serri.tesi.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.emanuelef.remote_capture.R
import serri.tesi.repo.TrackerRepository


class TesiDataActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView // Recycler per visualizzare dati

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tesi_data)

        recyclerView = findViewById<RecyclerView>(R.id.dataRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        recyclerView.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )

        val repo = TrackerRepository(this)

        // ultimi 100 record
        val data = repo.getLastNetworkRequests(100)

        recyclerView.adapter = NetworkDataAdapter(data.toMutableList())
    }
    // aggiorna adapter con nuovi dati
    override fun onResume() {
        super.onResume()

        val repo = TrackerRepository(this)
        val data = repo.getLastNetworkRequests(100)
        (recyclerView.adapter as NetworkDataAdapter).updateData(data)
    }

}