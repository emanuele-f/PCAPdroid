package serri.tesi.service

import android.content.Context //context android x accedere a db locale
import android.util.Log //utility android per logcat
import serri.tesi.dto.BatchDto //dto batch di richieste da inviare a backend
import serri.tesi.mapper.NetworkRequestMapper //mapper converte modello richieste in dto, per la trasmissione
import serri.tesi.network.BackendClient //client responsabile di comunicazione http con backend remoto
import serri.tesi.repo.TrackerRepository //repo per accesso ai dati locali
import serri.tesi.auth.SessionManager //per accedere a token jwt


/**
 * Servizio responsabile di sincronizzazione dati con backend remoto.
 *
 * legge i record di rete non ancora sincronizzati dal database
 * locale, li converte in DTO(Data Transfer Object) e li invia in modalità batch al server.
 *
 * In caso di esito positivo, i record vengono marcati come sincronizzati,
 * garantendo consistenza ed evitando duplicati
 */

// per mostrare risultato operazione
enum class SyncResult {
    NO_DATA,
    SUCCESS,
    ERROR
}

class SyncService(private val context: Context) {

    /**
     * Singola operazione di sincronizzazione.
     *
     * metodo recupera un batch di record non sincronizzati, li invia al backend
     * remoto e, in caso di successo, aggiorna lo stato locale della cache.
     */
    fun syncOnce(): SyncResult {
        //accedere a db locale (cache SQLite)
        val repo = TrackerRepository(context) //crea istanza del repo x accedere a db locale
        // val client = BackendClient("http://10.0.2.2:8080") //crea client x comunicare con backend

        //istanzia sessionmanager, x gestione sessione utente
        val sessionManager = SessionManager(context) // consente di recuperare token jwt salvato e riutilizzarlo

        //gestione robustezza sessioni
        //punto in cui sync service parla con backend, nessun dato deve uscire se user non è autenticato

        //se utente non loggato, sync annullato
        if (!sessionManager.isLoggedIn()) {
            Log.w("TESI_SYNC", "Utente non loggato: sync annullato")
            return SyncResult.ERROR
        }

        //creazione client http (autenticato) per comunicazione con backend remoto
        val client = BackendClient(
            baseUrl = "http://10.0.2.2:3000", //indirizzo backend
            sessionManager = sessionManager // sessionManager passato a client x inclusione autom. token nell'header Auth. di ogni richiesta
        )
        //10.0.2.2 permette all'emulatore Android di raggiungere il localhost della macchina host

        //recuperare da db connessioni non sinc.
        val pending = repo.getPendingNetworkRequests(30)

        //se non trova record da sincronuzzare, esce
        if (pending.isEmpty()) {
            Log.d("TESI_SYNC", "No records to sync")
            return SyncResult.NO_DATA
        }

        //convertire ogni request record (locale) in un dto (x trasmissione)
        val dtos = pending.map { NetworkRequestMapper.toDto(it) }
        val batch = BatchDto(dtos) // incapsula lista di dto in oggetto BatchDto

        // DEBUG anonimizzazione, da togliere*
        dtos.forEach {
            Log.d("TESI_PRIVACY", "DTO anonimizzato=$it")
        }

        //Log.d("TESI_SYNC", "Calling sendBatch()")
        val success = client.sendBatch(batch) //invia batch al backend, true se successo

        if (success) { //se invio a buon fine
            repo.markAsSynced(pending.mapNotNull { it.id }) //estrae id dei record sinc
            Log.d("TESI_SYNC", "Synced ${pending.size} records")//synced=1 nel db locale
            return SyncResult.SUCCESS
        } else {
            Log.e("TESI_SYNC", "Sync failed")
            return SyncResult.ERROR
        }
    }
}

// Il SyncService non gestisce l'autenticazione.
// Utilizza il token JWT esclusivamente per identificare l'utente
// durante l'invio dei dati; l'associazione avviene lato backend.

