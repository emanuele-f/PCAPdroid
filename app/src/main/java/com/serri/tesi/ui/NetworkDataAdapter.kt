package serri.tesi.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.emanuelef.remote_capture.R
import serri.tesi.model.NetworkRequestRecord

class NetworkDataAdapter(
    private val data: MutableList<NetworkRequestRecord>
) : RecyclerView.Adapter<NetworkDataAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val hostText: TextView = view.findViewById(R.id.hostText)
        val appText: TextView = view.findViewById(R.id.appText)
        val bytesText: TextView = view.findViewById(R.id.bytesText)
        val durationText: TextView = view.findViewById(R.id.durationText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network_data, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = data[position]

        // Dominio se disponibile, altrimenti IP destinazione
        holder.hostText.text = item.domain
            ?: item.dstIp
                    ?: "Destinazione sconosciuta"

        holder.appText.text =
            "${item.appName ?: "App sconosciuta"} • ${item.protocol}"

        // Byte totali = TX + RX (rielaborazione lato client)
        val totalBytes = item.bytesTx + item.bytesRx
        holder.bytesText.text = "Bytes: $totalBytes"

        // Durata connessione
        holder.durationText.text = "Durata: ${item.durationMs} ms"
    }
    override fun getItemCount(): Int = data.size

    fun updateData(newData: List<NetworkRequestRecord>) {
        data.clear()
        data.addAll(newData)
        notifyDataSetChanged()
    }


}
