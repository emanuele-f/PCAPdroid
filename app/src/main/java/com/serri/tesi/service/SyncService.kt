package serri.tesi.service

import android.content.Context
import android.util.Log
import serri.tesi.dto.BatchDto
import serri.tesi.mapper.NetworkRequestMapper
import serri.tesi.network.BackendClient
import serri.tesi.repo.TrackerRepository

/**
 * Servizio responsabile di sincronizzazione dati con backend remoto.
 *
 * legge i record di rete non ancora sincronizzati dal database
 * locale, li converte in DTO(Data Transfer Object) e li invia in modalità batch al server.
 *
 * In caso di esito positivo, i record vengono marcati come sincronizzati,
 * garantendo consistenza ed evitando duplicati
 */
class SyncService(private val context: Context) {

    /**
     * Singola operazione di sincronizzazione.
     *
     * metodo recupera un batch di record non sincronizzati, li invia al backend
     * remoto e, in caso di successo, aggiorna lo stato locale della cache.
     */
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
