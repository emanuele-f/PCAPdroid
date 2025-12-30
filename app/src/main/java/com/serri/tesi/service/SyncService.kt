package serri.tesi.service

import android.content.Context //context android x accedere a db locale
import android.util.Log //utility android per logcat
import serri.tesi.dto.BatchDto //dto batch di richieste da inviare a backend
import serri.tesi.mapper.NetworkRequestMapper //mapper converte modello richieste in dto, per la trasmissione
import serri.tesi.network.BackendClient //client responsabile di comunicazione http con backend remoto
import serri.tesi.repo.TrackerRepository //repo per accesso ai dati locali

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
        val repo = TrackerRepository(context) //crea istanza del repo x accedere a db locale
        val client = BackendClient("http://10.0.2.2:8080") //crea client x comunicare con backend

        //recuperare da db connessioni non sinc.
        val pending = repo.getPendingNetworkRequests(30)

        //se non trova record da sincronuzzare, esce
        if (pending.isEmpty()) {
            Log.d("TESI_SYNC", "No pending records")
            return
        }

        //convertire ogni request record in un dto (x trasmissione)
        val dtos = pending.map { NetworkRequestMapper.toDto(it) }
        val batch = BatchDto(dtos) // incapsula lista di dto in oggetto BatchDto

        // DEBUG anonimizzazione, da togliere*
        dtos.forEach {
            Log.d("TESI_PRIVACY", "DTO anonimizzato=$it")
        }

        val success = client.sendBatch(batch) //invia batch al backend, true se successo

        if (success) { //se invio a buon fine
            repo.markAsSynced(pending.mapNotNull { it.id }) //estrae id dei record sinc
            Log.d("TESI_SYNC", "Synced ${pending.size} records")//synced=1 nel db locale
        } else {
            Log.e("TESI_SYNC", "Sync failed")
        }

    }
}
