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
import serri.tesi.repo.TrackerRepository // x eliminazione dati locali

//Permessi Android
import android.Manifest
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager



class MainActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager //Gestisce lo stato di autenticazione e il token JWT
    private lateinit var captureStatusText: TextView // Elemento UI per visualizzare lo stato della cattura

    // variabili bottoni avvio/femra cattura, globali
    private lateinit var startCaptureButton: Button
    private lateinit var stopCaptureButton: Button

    // campi per info generali
    private lateinit var infoConnectionsText: TextView
    private lateinit var infoLastSyncText: TextView
    private lateinit var infoSyncStatusText: TextView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //Inizializzazione standard dell’Activity

        setContentView(R.layout.activity_main) //Associa layout XML aActivity

        showFirstRunWarningIfNeeded() //Mostra avviso informativo solo al primo avvio (privacy / consenso)

        sessionManager = SessionManager(this) // Inizializza gestore sessione usando il Context dell’Activity

        //assegnazione variabili --> elementi ui
        // button per operazioni
        val openDataButton = findViewById<Button>(R.id.openDataButton)
        val exportButton = findViewById<Button>(R.id.exportButton)
        val deleteButton = findViewById<Button>(R.id.deleteButton)
        // elementi gestione cattura dati
        startCaptureButton = findViewById<Button>(R.id.startCaptureButton)
        stopCaptureButton = findViewById<Button>(R.id.stopCaptureButton)
        captureStatusText = findViewById(R.id.captureStatusText)

        //info generali
        infoConnectionsText = findViewById(R.id.infoConnectionsText)
        infoLastSyncText = findViewById(R.id.infoLastSyncText)
        infoSyncStatusText = findViewById(R.id.infoSyncStatusText)


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

        // VISUALIZZA DATI
        openDataButton.setOnClickListener {
            val intent = Intent(this, TesiDataActivity::class.java)
            startActivity(intent)
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
                .setMessage( "Questa operazione cancellerà definitivamente tutti i dati associati al tuo account.\n\nVuoi continuare?" )

                .setPositiveButton("Sì, cancella") { _, _ ->

                    Thread {
                        val client = BackendClient(
                            baseUrl = BackendConfig.getBaseUrl(),
                            sessionManager = sessionManager
                        )

                        val success = client.deleteMyData() //Richiesta DELETE al backend

                        runOnUiThread {
                            if (success) {
                                TrackerRepository(this).clearAllNetworkRequests() // Cancella dati locali
                                Toast.makeText(this, "Operazione di cancellazione completata", Toast.LENGTH_LONG).show()
                                val prefs = getSharedPreferences("tesi_prefs", MODE_PRIVATE)
                                prefs.edit()
                                    .remove("last_sync_ts")
                                    .apply()

                                updateInfoPanel()

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
        updateInfoPanel() //aggirona info generali
    }

    //AVVISO PRIMO AVVIO
    private fun showFirstRunWarningIfNeeded() {

        val prefs = getSharedPreferences("tesi_prefs", MODE_PRIVATE) //SharedPreferences per memorizzare stato persistente semplice

        val alreadyShown = prefs.getBoolean("warning_shown", false) // Verifica se il warning è già stato mostrato

        if (alreadyShown) return // Se sì esce dal metodo

        AlertDialog.Builder(this)
            .setTitle("Avviso importante")
            .setMessage(
                "Questa applicazione intercetta il traffico di rete del dispositivo " +
                        "per analizzare le connessioni effettuate dalle applicazioni.\n\n" +
                        "I dati raccolti vengono anonimizzati e possono includere una " +
                        "posizione geografica approssimata.\n\n" +
                        "Le informazioni vengono inviate a un server remoto esclusivamente " +
                        "per fini di analisi e per migliorare user-experience."
            )
            .setPositiveButton("Ho capito") { _, _ ->
                prefs.edit()
                    .putBoolean("warning_shown", true) // Segna il warning come mostrato
                    .apply()

                requestLocationPermissionIfNeeded()
            }
            .setCancelable(false) //Impedisce chiusura senza consenso

            .show()
    }

    // al ritorno alla schermata, richiama metodo per aggiornare stato
    override fun onResume() {
        super.onResume()
        updateInfoPanel()
        updateCaptureStatus()
    }


    // metodo per aggiornare lo stato della cattura
    private fun updateCaptureStatus() {
        val active = isVpnActive()

        if (active) {
            captureStatusText.text = "Stato cattura: ATTIVA"
            captureStatusText.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_green_dark)
            )
            startCaptureButton.isEnabled = false
            stopCaptureButton.isEnabled = true
        } else {
            captureStatusText.text = "Stato cattura: FERMA"
            captureStatusText.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
            )
            startCaptureButton.isEnabled = true
            stopCaptureButton.isEnabled = false
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

    //metodo per aggiornare info panel
    private fun updateInfoPanel() {
        val repo = TrackerRepository(this)

        val count = repo.getLastNetworkRequests(1000).size
        infoConnectionsText.text = "Connessioni raccolte: $count"

        val prefs = getSharedPreferences("tesi_prefs", MODE_PRIVATE)

        val lastSync = prefs.getLong("last_sync_ts", 0L)
        val syncState = prefs.getString("last_sync_state", "IDLE")
        val pending = repo.countPendingNetworkRequests()

        // ultimo sync
        if (lastSync == 0L) {
            infoLastSyncText.text = "Ultimo sync: -"
        } else {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            infoLastSyncText.text =
                "Ultimo sync: ${sdf.format(java.util.Date(lastSync))}"
        }

        // stato sync
        infoSyncStatusText.text = when {
            syncState == "SYNCING" ->
                "Stato sync: sincronizzazione in corso…"

            syncState == "ERROR" ->
                "Stato sync: errore di connessione"

            pending > 0 ->
                "Stato sync: $pending dati in attesa"

            else ->
                "Stato sync: sincronizzato"
        }
    }


    //richiesta permessi location
    private fun requestLocationPermissionIfNeeded() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                1001
            )
        }
    }
}
