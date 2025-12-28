package serri.tesi.network

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import serri.tesi.dto.BatchDto

/**
 * Client HTTP responsabile della comunicazione con il backend remoto
 *
 * Incapsula logica di serializzazione e invio dei dati
 * al server tramite API REST,
 * Isola i dettagli di trasporto dal resto dell'applicazione.
 */
class BackendClient(
    private val baseUrl: String
) {

    private val client = OkHttpClient()
    private val gson = Gson()

    /**
     * Invia un batch di connessioni di rete al backend remoto
     *
     * @param batch = oggetto contenente connessioni da sincronizzare.
     * @return true se la richiesta è andata a buon fine, false altrimenti.
     */
    fun sendBatch(batch: BatchDto): Boolean {
        val json = gson.toJson(batch)

        val body = json.toRequestBody(
            "application/json; charset=utf-8".toMediaType()
        )

        val request = Request.Builder()
            .url("$baseUrl/network_requests/batch")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }
}
