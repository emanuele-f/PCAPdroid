package serri.tesi.network

import com.google.gson.Gson // lib x serializzazione/deserializzazione json
import okhttp3.MediaType.Companion.toMediaType // utility x creare mediatype partendo da stringa mime
import okhttp3.OkHttpClient //client http x eseguire richieste di rete
import okhttp3.Request // rappresenta richiesta http
import okhttp3.RequestBody.Companion.toRequestBody //estensione x creare requestbody da stringa
import serri.tesi.dto.BatchDto //dto che rappresenta batch da inviare a backend
import serri.tesi.auth.SessionManager //permette a client http di accedere a token jwt salvato


/**
 * Client HTTP responsabile della comunicazione con il backend remoto
 *
 * Incapsula logica di serializzazione, costruzione richiesta http e invio dei dati al server tramite API REST,
 * Isola i dettagli di trasporto dal resto dell'applicazione.
 */
class BackendClient(
    private val baseUrl: String, //url base del backend
    private val sessionManager: SessionManager  //per accedere a token, non lo riceve direttamente, riferimento al gestore di sessione
) {
    // client utilizzabile per le richieste
    private val client = OkHttpClient()
    //istanza gson x conversione oggetti in json (serializzatore)
    private val gson = Gson()

    /**
     * Invia un batch di connessioni di rete al backend remoto
     *
     * @param batch = oggetto contenente connessioni da sincronizzare.
     * @return true se la richiesta è andata a buon fine, false altrimenti.
     */
    fun sendBatch(batch: BatchDto): Boolean {
        val json = gson.toJson(batch) //serializza oggetto batchdto in stringa Json

        // creazione body della richiesta, content-type Json
        val body = json.toRequestBody( //creazuine corpo request http (post json)
            "application/json; charset=utf-8".toMediaType()
        )

        // Recupero token JWT salvato
        val token = sessionManager.getToken()
            ?: return false // se non c'è token, no sync

        //costruzione richiesta http
        // - endpoint /network_requests/batch
        // - metodo post
        // - body contenente dati da inviare (json)
        val request = Request.Builder()
            .url("$baseUrl/network_requests/batch")
            .addHeader("Authorization", "Bearer $token") //aggiunge header jwt automaticamente
            .post(body)
            .build()

        //esecuzione richiesta in modo sincrono, use{} = garantisce chiusura automatica della risposta
        client.newCall(request).execute().use { response ->
            return response.isSuccessful //restituisce true se stato http e 2xx
        }
    }
}
