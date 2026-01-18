package serri.tesi.network

import android.util.Log
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
        //Log.d("TESI_SYNC", "Entered sendBatch()")

        //try catch per evitare crash
        // intercetta backend spento, rete assente, timeout, errori okHttp
        return try {
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
                .url("$baseUrl/network-requests/batch")
                .addHeader("Authorization", "Bearer $token") //aggiunge header jwt automaticamente
                .post(body)
                .build()

            //esecuzione richiesta in modo sincrono, use{} = garantisce chiusura automatica della risposta
            client.newCall(request).execute().use { response ->
                Log.d("TESI_SYNC", "HTTP code = ${response.code}")
                //Log.d("TESI_SYNC", "Response body = ${response.body?.string()}")
                return when (response.code) {
                    200, 201 -> {
                        true //richiesta andata a buon fine
                    }

                    401 -> {
                        // token non valido o scaduto
                        sessionManager.logout() // logout automatico
                        false
                    }

                    else -> {
                        false // 5xx/4xx : sync fallita senza crash
                    }
                }
            }
        } catch (e: Exception) {
            // Log.e("TESI_NET", "Network error", e)
            false
        }
    }

    //metodo per download dati utente
    //endpoint GET /me/data/export (gdpr portabilità)
    fun downloadCsv(): ByteArray? {
        return try {
            val token = sessionManager.getToken() ?: return null
            Log.d("TESI_CSV", "Token = $token")

            val request = Request.Builder()
                .url("$baseUrl/network-requests/me/data/export")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                Log.d("TESI_CSV", "HTTP code = ${response.code}")
                val bytes = response.body?.bytes()
                Log.d("TESI_CSV", "Bytes null = ${bytes == null}")

                if (!response.isSuccessful) return null
                bytes
            }
        } catch (e: Exception) {
            Log.e("TESI_CSV", "Exception", e)
            null
        }
    }

    //metodo per eliminazione dati utente
    //endpoint DELETE /me/data (gdpr oblio)
    fun deleteMyData(): Boolean {
        return try {
            val token = sessionManager.getToken() ?: return false

            val request = Request.Builder()
                .url("$baseUrl/network-requests/me/data")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

}
