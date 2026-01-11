package serri.tesi.dto
/**
 * Data Transfer Object utilizzato per l'invio delle connessioni di rete
 * al backend remoto.
 *
 * Definisce il formato dei dati serializzati in JSON e
 * rappresenta comunicazione tra l'applicazione Android
 * e il server.
 *
 * Il DTO è separato dal modello di persistenza per garantire
 * flessibilità, sicurezza e disaccoppiamento architetturale.
 */
data class NetworkRequestDto(

    // App che ha generato la connessione
    val appName: String?,
    val appUid: Int,

    // Informazioni di rete (anonimizzate)
    val protocol: String,
    val domainHash: String?,
    val dstIpHash: String?,
    val dstPort: Int?,

    // Metriche di traffico
    val bytesTx: Long,
    val bytesRx: Long,
    val packetsTx: Int,
    val packetsRx: Int,

    // Informazioni temporali
    val startTs: Long,
    val endTs: Long,
    val durationMs: Long,

    // Geolocalizzazione approssimata
    val latitude: Double?,
    val longitude: Double?
)
//update dopo configurazione backend
// rimosso user uuid, associazione utente–dato avviene SOLO lato backend tramite JWT
