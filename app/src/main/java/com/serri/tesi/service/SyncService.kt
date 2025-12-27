package serri.tesi.service

import android.content.Context
import android.util.Log
import serri.tesi.dto.BatchDto
import serri.tesi.mapper.NetworkRequestMapper
import serri.tesi.network.BackendClient
import serri.tesi.repo.TrackerRepository

class SyncService(private val context: Context) {

    fun syncOnce() {
        val repo = TrackerRepository(context)
        val client = BackendClient("http://10.0.2.2:8080")

        val pending = repo.getPendingNetworkRequests(30)

        if (pending.isEmpty()) {
            Log.d("TESI_SYNC", "No pending records")
            return
        }

        val dtos = pending.map { NetworkRequestMapper.toDto(it) }
        val batch = BatchDto(dtos)

        val success = client.sendBatch(batch)

        if (success) {
            repo.markAsSynced(pending.mapNotNull { it.id })
            Log.d("TESI_SYNC", "Synced ${pending.size} records")
        } else {
            Log.e("TESI_SYNC", "Sync failed")
        }
    }
}
