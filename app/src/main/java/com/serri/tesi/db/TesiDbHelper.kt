package serri.tesi.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * classe helper x gestione db SQLite locale.
 *
 * TesiDbHelper è responsabile di creazione e versionamento del db locale
 * utilizzato come cache persistente x memorizzare i dati delle connessioni intercettate
 *
 * il database memorizza esclusivamente metadati relativi a connessioni
 * concluse, nel rispetto dei limiti imposti dalla cifratura HTTPS e della privacy.
 */
class TesiDbHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    /**
     * Creazione iniziale db
     * Definita tabella principale "network_requests", in cui ogni riga
     * rappresenta una connessione di rete conclusa intercettata tramite VpnService.
     */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE network_requests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,

                user_uuid TEXT NOT NULL,

                app_name TEXT,
                app_uid INTEGER,

                protocol TEXT,
                domain TEXT,

                src_ip TEXT,
                src_port INTEGER,
                dst_ip TEXT,
                dst_port INTEGER,

                bytes_tx INTEGER,
                bytes_rx INTEGER,
                packets_tx INTEGER,
                packets_rx INTEGER,

                start_ts INTEGER,
                end_ts INTEGER,
                duration_ms INTEGER,

                latitude REAL,
                longitude REAL,
                
                synced INTEGER DEFAULT 0
            )
        """.trimIndent())
    }
    // nel db aggiunto synced per gestire sincro. con backend remoto
    // se 0 = non sincronizzato
    // se 1 = sincronizzato

    // ricreazione completa del db on upgrade
    // l'upgrade comporta ricreazione completa del db che è concepito come cache persistente (non come storage definitivo)
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS network_requests")
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "tesi.db" // nome del db locale
        const val DB_VERSION = 3 // versione dello schema db
    }
}
