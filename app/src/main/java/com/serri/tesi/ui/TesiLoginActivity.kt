package serri.tesi.ui

import android.content.Intent //x avviare altre activity
import android.os.Bundle //x stato activity
// componenti ui x input e feedback utente
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity //classe base x activity compatibily con AppCompat
import kotlin.concurrent.thread //utility x eseguire operazioni su thread separati/background
import serri.tesi.auth.SessionManager //gestore della sessione utente (salvataggio token jwt)
import serri.tesi.network.AuthClient //client http per autenticazione verso backend
import com.emanuelef.remote_capture.R //risorse grafiche/layout app
import serri.tesi.config.BackendConfig

/**
 * Activity responsabile di autenticazione utente.
 *
 * Permette all’utente di inserire le credenziali:
 * in caso di successo, salva il token JWT e reindirizza alla schermata principale.
 */
class TesiLoginActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager //variabile x gestione persistenza token

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_tesi_login)

        sessionManager = SessionManager(this) //inizializza gestore sessione con context

        //campi input
        val emailInput = findViewById<EditText>(R.id.emailInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val loginButton = findViewById<Button>(R.id.loginButton) //bottone login

        //login
        loginButton.setOnClickListener {

            //leggo valori input e li salvo
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString()

            //validazione lato client delle credenziali
            if (email.isBlank() || password.isBlank()) {
                Toast.makeText(this, "Email e password obbligatorie", Toast.LENGTH_SHORT).show()
                return@setOnClickListener //interrompe esecuzione listener
            }

            //Avvia thread separato per evitare operazioni di rete sul main thread
            thread {
                val authClient = AuthClient(
                    BackendConfig.getBaseUrl()
                ) //Client http, comunica con backend

                //invia credenziali e riceve token jwt/null
                val token = authClient.login(email, password)

                //torna su thread principale x aggiornare interfaccia
                runOnUiThread {
                    //controllo del token
                    if (token != null) {
                        sessionManager.saveToken(token) //salva token
                        Toast.makeText(this, "Login effettuato", Toast.LENGTH_SHORT).show()

                        //Avvia main activity
                        startActivity(
                            Intent(this, MainActivity::class.java)
                        )
                        finish() //chiudo login activity
                    } else {
                        Toast.makeText(this, "Login fallito", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
