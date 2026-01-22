package serri.tesi.ui

import android.os.Bundle // usato per passare lo stato dell’Activity

//Componenti UI di base Android
import android.widget.Button
import android.widget.Toast
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity //Classe base per Activity compatibili con AppCompat
import com.emanuelef.remote_capture.R // Risorse grafiche/layout generate automaticamente
import serri.tesi.auth.SessionManager //Classe che gestisce la sessione utente (token JWT)
import serri.tesi.service.SyncService //Servizio che si occupa della sincronizzazione con il backend
import android.content.Intent // Intent x navigazione tra Activity
import com.emanuelef.remote_capture.activities.MainActivity as PcapMainActivity // per evitare conflitto di nome con questa MainActivity
import android.os.Environment // Accesso a directory standard del filesystem Android

// Classi Java per gestione file e scrittura binaria
import java.io.File
import java.io.FileOutputStream

import serri.tesi.network.BackendClient //Client HTTP per comunicazione col backend (sync, export, delete)
import androidx.appcompat.app.AlertDialog //Dialog per conferme utente
import serri.tesi.service.SyncResult // Enum che rappresenta l’esito della sincronizzazione
import android.util.Log //Utility per logging su Logcat
import serri.tesi.config.BackendConfig //x configurazione




class MainActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager //Gestisce lo stato di autenticazione e il token JWT
    private lateinit var captureStatusText: TextView // Elemento UI per visualizzare lo stato della cattura

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //Inizializzazione standard dell’Activity

        setContentView(R.layout.activity_main) //Associa layout XML aActivity

        showFirstRunWarningIfNeeded() //Mostra avviso informativo solo al primo avvio (privacy / consenso)

        sessionManager = SessionManager(this) // Inizializza gestore sessione usando il Context dell’Activity

        val openPcapButton = findViewById<Button>(R.id.openPcapdroidButton) // Recupera pulsante per aprire PCAPdroid

        //apre l’Activity di PCAPdroid
        openPcapButton.setOnClickListener {
            val intent = Intent(this, PcapMainActivity::class.java) // Intent esplicito verso l’Activity di PCAPdroid
            startActivity(intent) // Avvia PCAPdroid
        }

        // button per operazioni
        val syncButton = findViewById<Button>(R.id.syncButton)
        val exportButton = findViewById<Button>(R.id.exportButton)
        val deleteButton = findViewById<Button>(R.id.deleteButton)
        // elementi gestione cattura dati
        val startCaptureButton = findViewById<Button>(R.id.startCaptureButton)
        val stopCaptureButton = findViewById<Button>(R.id.stopCaptureButton)
        captureStatusText = findViewById(R.id.captureStatusText)

        // start cattura dati
        startCaptureButton.setOnClickListener {
            // avvio delegato a PCAPdroid
            val intent = Intent(this, PcapMainActivity::class.java)
            startActivity(intent)

            Toast.makeText(this, "Richiesta avvio cattura", Toast.LENGTH_SHORT).show()
        }

        //stop cattura dati
        stopCaptureButton.setOnClickListener {
            val intent = Intent(this, PcapMainActivity::class.java)
            startActivity(intent)

            Toast.makeText(this, "Richiesta stop cattura", Toast.LENGTH_SHORT).show()
        }

        // SYNC DATI
        syncButton.setOnClickListener {

            // Verifica presenza token valido
            if (!sessionManager.isLoggedIn()) {
                Toast.makeText(this, "Devi fare login", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            //thread separato per sync con backend
            Thread {
                val result = SyncService(this).syncOnce() //Esegue sincronizzazione

                runOnUiThread { // Aggiorna UI in base all’esito

                    when (result) {
                        SyncResult.NO_DATA ->
                            Toast.makeText(this, "Nessun dato nuovo da inviare", Toast.LENGTH_SHORT).show()

                        SyncResult.SUCCESS ->
                            Toast.makeText(this, "Dati sincronizzati correttamente", Toast.LENGTH_SHORT).show()

                        SyncResult.ERROR ->
                            Toast.makeText(this, "Errore durante la sincronizzazione", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        //EXPORT CSV
        exportButton.setOnClickListener {

            if (!sessionManager.isLoggedIn()) {
                Toast.makeText(this, "Devi fare login", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Thread per download file CSV
            Thread {
                // Client backend con autenticazione JWT
                val client = BackendClient(
                    baseUrl = BackendConfig.getBaseUrl(),
                    sessionManager = sessionManager
                )

                val csvBytes = client.downloadCsv() //Scarica CSV come array di byte

                if (csvBytes == null || csvBytes.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, "Nessun dato da esportare", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                runOnUiThread {
                    try {
                        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) //directory privata dell’app per download
                        // File CSV di output
                        val file = File(dir, "tesi_network_data.csv")

                        FileOutputStream(file).use {
                            it.write(csvBytes)
                            //Scrive i byte sul filesystem
                        }

                        Toast.makeText(
                            this,
                            "CSV salvato in ${file.absolutePath}",
                            Toast.LENGTH_LONG
                        ).show()

                    } catch (e: Exception) {
                        Log.e("TESI_CSV", "Errore salvataggio file", e)
                        Toast.makeText(this, "Errore salvataggio file", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        // CANCELLAZIONE DATI
        deleteButton.setOnClickListener {

            if (!sessionManager.isLoggedIn()) {
                Toast.makeText(this, "Devi fare login", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(this) //Dialog di conferma operazione

                .setTitle("Cancella dati")
                .setMessage(
                    "Questa operazione cancellerà definitivamente tutti i dati associati al tuo account.\n\nVuoi continuare?"
                )

                .setPositiveButton("Sì, cancella") { _, _ ->

                    Thread {
                        val client = BackendClient(
                            baseUrl = BackendConfig.getBaseUrl(),
                            sessionManager = sessionManager
                        )

                        val success = client.deleteMyData() //Richiesta DELETE al backend

                        runOnUiThread {
                            if (success) {
                                Toast.makeText(this, "Operazione di cancellazione completata", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this, "Errore durante la cancellazione", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                }

                .setNegativeButton("Annulla", null) // Chiude il dialog senza fare nulla

                .show()
        }
        updateCaptureStatus() // Aggiorna lo stato della cattura
    }

    //AVVISO PRIMO AVVIO
    private fun showFirstRunWarningIfNeeded() {

        val prefs = getSharedPreferences("tesi_prefs", MODE_PRIVATE) //SharedPreferences per memorizzare stato persistente semplice

        val alreadyShown = prefs.getBoolean("warning_shown", false) // Verifica se il warning è già stato mostrato

        if (alreadyShown) return // Se sì esce dal metodo

        AlertDialog.Builder(this)
            .setTitle("Avviso importante")
            .setMessage(
                "Questa applicazione intercetta il traffico di rete del dispositivo...\n\n" +
                        "I dati sensibili vengono anonimizzati prima dell'invio al server."
            )
            .setPositiveButton("Ho capito") { _, _ ->
                prefs.edit()
                    .putBoolean("warning_shown", true) // Segna il warning come mostrato

                    .apply()
            }
            .setCancelable(false) //Impedisce chiusura senza consenso

            .show()
    }

    // al ritorno alla schermata, richiama metodo per aggiornare stato
    override fun onResume() {
        super.onResume()
        updateCaptureStatus()
    }


    // metodo per aggiornare lo stato della cattura
    private fun updateCaptureStatus() {
        if (isVpnActive()) {
            captureStatusText.text = "Stato cattura: ATTIVA"
        } else {
            captureStatusText.text = "Stato cattura: FERMA"
        }
    }

    // metodo per verificare se la cattura è attiva/disattiva
    private fun isVpnActive(): Boolean {
        val connectivityManager =
            getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager

        val networks = connectivityManager.allNetworks
        for (network in networks) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                return true
            }
        }
        return false
    }

}
