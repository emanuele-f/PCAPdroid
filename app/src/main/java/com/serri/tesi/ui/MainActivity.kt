package serri.tesi.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.emanuelef.remote_capture.R
import serri.tesi.auth.SessionManager
import serri.tesi.service.SyncService
import android.content.Intent
import com.emanuelef.remote_capture.activities.MainActivity as PcapMainActivity
import kotlin.concurrent.thread
import serri.tesi.network.AuthClient
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import serri.tesi.network.BackendClient
import androidx.appcompat.app.AlertDialog
import serri.tesi.service.SyncResult






class MainActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        showFirstRunWarningIfNeeded() // warning al primo avvio

        sessionManager = SessionManager(this)

        val openPcapButton = findViewById<Button>(R.id.openPcapdroidButton)

        openPcapButton.setOnClickListener {
            val intent = Intent(this, PcapMainActivity::class.java)
            startActivity(intent)
        }


        val emailInput = findViewById<EditText>(R.id.emailInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)

        val loginButton = findViewById<Button>(R.id.loginButton)
        val syncButton = findViewById<Button>(R.id.syncButton)
        val exportButton = findViewById<Button>(R.id.exportButton)
        val deleteButton = findViewById<Button>(R.id.deleteButton)

        // Gestisce il click del pulsante di login
        loginButton.setOnClickListener {

            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString()

            if (email.isBlank() || password.isBlank()) {
                Toast.makeText(this, "Email e password obbligatorie", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            thread {
                val authClient = AuthClient("http://10.0.2.2:3000")
                val token = authClient.login(email, password)

                runOnUiThread {
                    if (token != null) {
                        sessionManager.saveToken(token)
                        Toast.makeText(this, "Login effettuato", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Login fallito", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }


        // Gestisce il click del pulsante di sync/ invio dati a backend
        syncButton.setOnClickListener {

            if (!sessionManager.isLoggedIn()) {
                Toast.makeText(this, "Devi fare login", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Thread {
                val result = SyncService(this).syncOnce()

                runOnUiThread {
                    when (result) {
                        SyncResult.NO_DATA ->
                            Toast.makeText(
                                this,
                                "Nessun dato nuovo da inviare",
                                Toast.LENGTH_SHORT
                            ).show()

                        SyncResult.SUCCESS ->
                            Toast.makeText(
                                this,
                                "Dati sincronizzati correttamente",
                                Toast.LENGTH_SHORT
                            ).show()

                        SyncResult.ERROR ->
                            Toast.makeText(
                                this,
                                "Errore durante la sincronizzazione",
                                Toast.LENGTH_SHORT
                            ).show()
                    }
                }
            }.start()

        }

        //Gestisce il click del pulsante di export dati a csv
        exportButton.setOnClickListener {

            if (!sessionManager.isLoggedIn()) {
                Toast.makeText(this, "Devi fare login", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Thread {
                val client = BackendClient(
                    baseUrl = "http://10.0.2.2:3000",
                    sessionManager = sessionManager
                )

                val csvBytes = client.downloadCsv()

                if (csvBytes == null || csvBytes.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Nessun dato da esportare",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@Thread
                }

                runOnUiThread {
                    if (csvBytes == null) {
                        Toast.makeText(this, "Errore download CSV", Toast.LENGTH_SHORT).show()
                    } else {
                        try {
                            val file = File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                "tesi_network_data.csv"
                            )

                            FileOutputStream(file).use {
                                it.write(csvBytes)
                            }

                            Toast.makeText(
                                this,
                                "CSV salvato in Download",
                                Toast.LENGTH_LONG
                            ).show()

                        } catch (e: Exception) {
                            Toast.makeText(this, "Errore salvataggio file", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }.start()
        }

        //Gestisce il click del pulsante di cancellazione dati utente
        deleteButton.setOnClickListener {

            if (!sessionManager.isLoggedIn()) {
                Toast.makeText(this, "Devi fare login", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Cancella dati")
                .setMessage(
                    "Questa operazione cancellerà definitivamente tutti i dati associati al tuo account.\n\nVuoi continuare?"
                )
                .setPositiveButton("Sì, cancella") { _, _ ->

                    Thread {
                        val client = BackendClient(
                            baseUrl = "http://10.0.2.2:3000",
                            sessionManager = sessionManager
                        )

                        val success = client.deleteMyData()

                        runOnUiThread {
                            if (success) {
                                Toast.makeText(
                                    this,
                                    "Operazione di cancellazione completata",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    this,
                                    "Errore durante la cancellazione",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }.start()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }
    }

    // Mostra un dialogo di avviso al primo avvio dell'applicazione
    private fun showFirstRunWarningIfNeeded() {
        val prefs = getSharedPreferences("tesi_prefs", MODE_PRIVATE)
        val alreadyShown = prefs.getBoolean("warning_shown", false)

        if (alreadyShown) return

        AlertDialog.Builder(this)
            .setTitle("Avviso importante")
            .setMessage(
                "Questa applicazione intercetta il traffico di rete del dispositivo " +
                        "al fine di analisi sperimentale.\n\n" +
                        "Non vengono registrati contenuti delle comunicazioni, ma solo metadati " +
                        "(indirizzi, porte, timestamp).\n\n" +
                        "I dati possono essere sincronizzati con un server remoto dopo autenticazione.\n\n" +
                        "I dati sensibili vengono anonimizzati prima dell'invio al server."
            )
            .setPositiveButton("Ho capito") { _, _ ->
                prefs.edit()
                    .putBoolean("warning_shown", true)
                    .apply()
            }
            .setCancelable(false)
            .show()
    }

}
