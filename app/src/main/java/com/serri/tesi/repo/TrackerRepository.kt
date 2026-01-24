package serri.tesi.repo

import android.content.ContentValues //usata per mappare chiave-val, da inserire nel db
import android.content.Context //context necesario x inizializzare db helper
import serri.tesi.db.TesiDbHelper //helper sqlite, gestisce creazione e versionamento del db locale
import serri.tesi.model.ConnectionRecord //modello dati per connessioni (versione iniziale)
import serri.tesi.model.HttpRequestRecord //modello dati per richieste http (vers iniziale)
import serri.tesi.model.NetworkRequestRecord //modello dati principale che rappresenta connessione
import serri.tesi.service.SyncService //per sincronizzazione con backend auto



/**
 * Repository responsabile dell'accesso al db locale
 *
 * incapsula tutte le operazioni di persistenza
 * relative alle connessioni intercettate, fornendo un'interfaccia
 * semplice al resto dell'applicazione.
 *
 * implementa il pattern Repository, separando la logica
 * di accesso ai dati dalla logica di business.
 */
class TrackerRepository(private val context: Context) {
    //serve context dopo per chiamare SyncService(context)

    private val dbHelper = TesiDbHelper(context) //istanza di helper sqlite, x ottenere db leggibile/scrivibile

    // *vecchio insert a tab connections, con dati parziali (primo tentativo)*
    fun insertConnection(record: ConnectionRecord): Long {
        val db = dbHelper.writableDatabase

        val values = ContentValues().apply {
            put("user_uuid", record.userUuid)
            put("ip", record.ip)
            put("port", record.port)
            put("bytes", record.bytes)
            put("timestamp", record.timestamp)
            put("latitude", record.latitude)
            put("longitude", record.longitude)
            put("domain", record.domain)
            put("path", record.path)
        }

        return db.insert("connections", null, values)
    }

    // *vecchio insert a tab http_request (inizialmente due tab separate)*
    fun insertHttpRequest(record: HttpRequestRecord): Long {
        val db = dbHelper.writableDatabase

        val values = ContentValues().apply {
            //put("connection_id", record.connectionId)
            put("method", record.method)
            put("host", record.host)
            put("path", record.path)
            put("timestamp", record.timestamp)
        }

        return db.insert("http_requests", null, values)
    }

    // *Metodo Principale (versione finale modello dati)*
    /**
     * Inserisce nel db una nuova riga nella tabella "network_requests"
     * che rappresenta una connessione di rete conclusa.
     *
     * @param record oggetto che rappresenta una connessione aggregata,
     *               costruita al termine della connessione stessa.
     *
     * @return ID del record inserito nel database.
     */
    fun insertNetworkRequest(record: NetworkRequestRecord): Long {
        val db = dbHelper.writableDatabase //db in mod scrittura

        //inserimento dati nel db tramite ContentValues
        val values = ContentValues().apply {
            put("user_uuid", record.userUuid)

            put("app_name", record.appName)
            put("app_uid", record.appUid)

            put("protocol", record.protocol)
            put("domain", record.domain)

            put("http_method", record.httpMethod)
            put("http_path", record.httpPath)
            put("http_host", record.httpHost)


            put("src_ip", record.srcIp)
            put("src_port", record.srcPort)
            put("dst_ip", record.dstIp)
            put("dst_port", record.dstPort)

            put("bytes_tx", record.bytesTx)
            put("bytes_rx", record.bytesRx)
            put("packets_tx", record.packetsTx)
            put("packets_rx", record.packetsRx)

            put("start_ts", record.startTs)
            put("end_ts", record.endTs)
            put("duration_ms", record.durationMs)

            put("latitude", record.latitude)
            put("longitude", record.longitude)
        }
        //inserimento record nella tabella + return id generato
        //return db.insert("network_requests", null, values)

        //update: sync automatico ogni 30 record
        val id = db.insert("network_requests", null, values)

        // auto-sync: controllo soglia
        val pendingCount = countPendingNetworkRequests()

        if (pendingCount >= 30 && pendingCount % 30 == 0) {
            android.util.Log.d(
                "TESI_SYNC",
                "Auto-sync triggered: $pendingCount pending records"
            )

            // avvio sync automatico
            Thread {
                SyncService(context).syncOnce()
            }.start()
        }
        return id
    }

    //DEBUG
    //funzione per verificare db connections
    fun debugDumpConnections(limit: Int = 10) {
        val db = dbHelper.readableDatabase

        val cursor = db.rawQuery(
            """
        SELECT id, ip, port, bytes, latitude, longitude, domain, path, timestamp
        FROM connections
        ORDER BY timestamp DESC
        LIMIT ?
        """,
            arrayOf(limit.toString())
        )

        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val ip = cursor.getString(1)
            val port = cursor.getInt(2)
            val bytes = cursor.getLong(3)
            val lat = if (cursor.isNull(4)) null else cursor.getDouble(4)
            val lon = if (cursor.isNull(5)) null else cursor.getDouble(5)
            val domain = cursor.getString(6)
            val path = cursor.getString(7)
            val ts = cursor.getLong(8)

            android.util.Log.d(
                "TESI_DB",
                "ID=$id ip=$ip:$port bytes=$bytes lat=$lat lon=$lon domain=$domain path=$path ts=$ts"
            )
        }

        cursor.close()
    }
    //DEBUG
    //funzione per verificare db http request
    fun debugDumpHttpRequests(limit: Int = 10) {
        val db = dbHelper.readableDatabase

        val cursor = db.rawQuery(
            """
    SELECT id, method, path, host, timestamp
    FROM http_requests
    ORDER BY timestamp DESC
    LIMIT ?
    """,
            arrayOf(limit.toString())
        )

        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val method = cursor.getString(1)
            val path = cursor.getString(2)
            val host = cursor.getString(3)
            val ts = cursor.getLong(4)

            android.util.Log.d(
                "TESI_DB_HTTP",
                "ID=$id method=$method host=$host path=$path ts=$ts"
            )
        }

        cursor.close()
    }

    //Sincronizzazione con backend

    //aggiunto metodo per recupero batch non sincronizzato
    //leggere tutte le connessioni non sincronizzate (synced=0)
    /**
     * Recupera batch di connessioni non ancora sincronizzate con il backend.
     *
     * I record vengono selezionati in base al campo `synced` e ordinati per ID,
     * consentendo un invio incrementale dei dati.
     *
     * @param limit numero massimo di record da restituire.
     */
    fun getPendingNetworkRequests(limit: Int): List<NetworkRequestRecord> {
        val db = dbHelper.readableDatabase //db in lettura
        val results = mutableListOf<NetworkRequestRecord>() //lista x risultati

        //query sql x selezionare solo i record non sincronizzati
        val cursor = db.rawQuery(
            """
        SELECT 
            id,
            user_uuid,
            app_name,
            app_uid,
            protocol,
            domain,
            src_ip,
            src_port,
            dst_ip,
            dst_port,
            http_method,
            http_path,
            http_host,
            bytes_tx,
            bytes_rx,
            packets_tx,
            packets_rx,
            start_ts,
            end_ts,
            duration_ms,
            latitude,
            longitude
        FROM network_requests
        WHERE synced = 0
        ORDER BY id ASC
        LIMIT ?
        """.trimIndent(),
            arrayOf(limit.toString())
        )

        //converte ogni rifa del cursor in un oggetto NetworkRequestRecord
        while (cursor.moveToNext()) {
            val record = NetworkRequestRecord(
                id = cursor.getLong(0),
                userUuid = cursor.getString(1),
                appName = cursor.getString(2),
                appUid = cursor.getInt(3),
                protocol = cursor.getString(4),
                domain = cursor.getString(5),
                srcIp = cursor.getString(6),
                srcPort = if (cursor.isNull(7)) null else cursor.getInt(7),
                dstIp = cursor.getString(8),
                dstPort = cursor.getInt(9),
                httpMethod = cursor.getString(10),
                httpPath   = cursor.getString(11),
                httpHost   = cursor.getString(12),
                bytesTx = cursor.getLong(13),
                bytesRx = cursor.getLong(14),
                packetsTx = cursor.getInt(15),
                packetsRx = cursor.getInt(16),
                startTs = cursor.getLong(17),
                endTs = cursor.getLong(18),
                durationMs = cursor.getLong(19),
                latitude = if (cursor.isNull(20)) null else cursor.getDouble(20),
                longitude = if (cursor.isNull(21)) null else cursor.getDouble(21)
            )

            results.add(record) //aggiunge record alla lista finale
        }

        cursor.close() //chiusura cursor
        return results  //ritorna il batch di record non sinc
    }

    /**
     * Recupera le ultime connessioni di rete dal database locale
     * per visualizzazione lato UI.
     *
     * @param limit numero massimo di record
     */
    fun getLastNetworkRequests(limit: Int): List<NetworkRequestRecord> {
        val db = dbHelper.readableDatabase
        val results = mutableListOf<NetworkRequestRecord>()

        val cursor = db.rawQuery(
            """
        SELECT 
            id,
            user_uuid,
            app_name,
            app_uid,
            protocol,
            domain,
            src_ip,
            src_port,
            dst_ip,
            dst_port,
            http_method,
            http_path,
            http_host,
            bytes_tx,
            bytes_rx,
            packets_tx,
            packets_rx,
            start_ts,
            end_ts,
            duration_ms,
            latitude,
            longitude,
            synced
        FROM network_requests
        ORDER BY end_ts DESC
        LIMIT ?
        """.trimIndent(),
            arrayOf(limit.toString())
        )

        while (cursor.moveToNext()) {
            val record = NetworkRequestRecord(
                id = cursor.getLong(0),
                userUuid = cursor.getString(1),
                appName = cursor.getString(2),
                appUid = cursor.getInt(3),
                protocol = cursor.getString(4),
                domain = cursor.getString(5),
                srcIp = cursor.getString(6),
                srcPort = if (cursor.isNull(7)) null else cursor.getInt(7),
                dstIp = cursor.getString(8),
                dstPort = if (cursor.isNull(9)) null else cursor.getInt(9),
                httpMethod = cursor.getString(10),
                httpPath = cursor.getString(11),
                httpHost = cursor.getString(12),
                bytesTx = cursor.getLong(13),
                bytesRx = cursor.getLong(14),
                packetsTx = cursor.getInt(15),
                packetsRx = cursor.getInt(16),
                startTs = cursor.getLong(17),
                endTs = cursor.getLong(18),
                durationMs = cursor.getLong(19),
                latitude = if (cursor.isNull(20)) null else cursor.getDouble(20),
                longitude = if (cursor.isNull(21)) null else cursor.getDouble(21),
                synced = cursor.getInt(22)
            )

            results.add(record)
        }

        cursor.close()
        return results
    }

    //aggiunto metodo per segnare i dati inviati
    /**
     * Marca come sincronizzati i record che sono stati inviati correttamente
     * al backend remoto.
     *
     * @param ids lista degli identificativi dei record da aggiornare.
     */
    fun markAsSynced(ids: List<Long>) {
        // se non ci sono id, non eseguire
        if (ids.isEmpty()) return

        val db = dbHelper.writableDatabase //db in scrittura

        // costruzione dinamica dei placeholder (?, ?, ?)
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray() // conversione id --> stringe per execSQL
        //query di aggiornamento
        val query = """
        UPDATE network_requests
        SET synced = 1
        WHERE id IN ($placeholders)
    """.trimIndent()

        db.execSQL(query, args) //esegue update
    }

    //metodo per contare i record non sincronizzati
    fun countPendingNetworkRequests(): Int {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM network_requests WHERE synced = 0",
            null
        )
        cursor.moveToFirst()
        val count = cursor.getInt(0)
        cursor.close()
        return count
    }

    // elimina tutte le righe dalla tabella network_requests
    // es: se utente decide di eliminare dati
    fun clearAllNetworkRequests() {
        val db = dbHelper.writableDatabase
        db.execSQL("DELETE FROM network_requests")
    }
}
