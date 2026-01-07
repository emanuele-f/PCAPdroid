package serri.tesi.model

/**
 * Modello dati che rappresenta connessione di rete conclusa.
 *
 * Ogni istanza descrive una connessione osservata
 * tramite VpnService, e include
 * metadati disponibili in presenza di traffico cifrato
 *
 * Il modello costituisce l'unità informativa centrale della cache locale
 * e viene utilizzato sia per la persistenza su SQLite sia per l'invio
 * batch verso il backend remoto.
 */
data class NetworkRequestRecord(
    //DB: metadati persistenza e sincronizzazione
    val id: Long? = null, //primary key, serve anche per segnare i dati inviati
    val synced: Int = 0, //rappresenta stato cache e evita duplicati

    //identificazione Utente
    val userUuid: String, //uuid anonimo dell'user

    //App
    val appName: String?, //nome app
    val appUid: Int, //uid android dell'app

    //info di rete
    val protocol: String, //Protocollo di rete (TCP, TLS, Http/Https)
    val domain: String?, //dominio o sni, se disp.
    val srcIp: String?, //indirizzo IP sorgente
    val srcPort: Int?, //porta sorgente
    val dstIp: String?, //ip di destinazione
    val dstPort: Int?, //porta di destinazione

    //Metriche di traffico
    val bytesTx: Long, //byte trasmessi
    val bytesRx: Long, //byte ricevuti
    val packetsTx: Int, //pacchetti trasm
    val packetsRx: Int, //pacchetti ric

    //Time
    val startTs: Long, //timestamp inizio connessione
    val endTs: Long, //fine connessione
    val durationMs: Long, //durata tot connessione in millisecondi

    //GPS: se disp
    val latitude: Double?,
    val longitude: Double?,

    //HTTP-only (non disponibili su HTTPS)
    val httpMethod: String?,   // GET, POST, ...
    val httpPath: String?,     // /api/search
    val httpHost: String?      // Host header (es. example.com)
)
