package serri.tesi.ui

import android.content.Intent // intent usato per avviare altre activity
import android.os.Bundle //per passaggio/stato activity
import android.util.Log //per messaggi su logcat
import androidx.appcompat.app.AppCompatActivity //classe base per activity compatibili AppCompat
import serri.tesi.auth.SessionManager //gestore della sessione utente (verifica token jwt

/**
 * Activity di ingresso dell’app, no interfaccia grafica.
 * Scopo:
 * - verificare lo stato di autenticazione dell’utente
 * - indirizzare verso la schermata corretta
 */

class TesiEntryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // inizializzazione standard dell'activity

        //debug x verificare avvio entry point
        //Log.e("TESI", ">>> TesiEntryActivity avviata")

        // inizializzazione gestore di sessione con context
        val sessionManager = SessionManager(this)

        // se presente token jwt, avvia direttamente MainActivity
        if (sessionManager.isLoggedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            // altrimenti non autenticato, avvia login
            startActivity(Intent(this, TesiLoginActivity::class.java))
        }
        //Chiudo activity per imedire ritorno indietro
        finish()
    }
}
