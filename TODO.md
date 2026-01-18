# TODO - Tesi (PCAPdroid integration)

## Fase 1 - Setup progetto e analisi PCAPdroid
- [x] Creazione fork del repository PCAPdroid
- [x] Clonato repository in Android Studio
- [x] Creato branch dedicato `tesi`
- [x] Studio dell’architettura interna di PCAPdroid
- [x] Identificazione dei punti chiave di intercettazione del traffico

### Componenti principali analizzati
- **VpnService**
    - [x] Analisi del flusso di intercettazione tramite VPN locale
    - [x] Comprensione dei limiti di intercettazione su Android (HTTPS, TLS)

- **CaptureService**
    - [x] Analizzato come entry-point della cattura
    - [x] Verificato il dispatch degli update di connessione

- **ConnectionsRegister**
    - [x] Studio della struttura di gestione delle connessioni
    - [x] Distinzione tra connessioni attive e terminate
    - [x] Identificato `connectionsUpdates()` come punto corretto per il logging
    - [x] Scartato l’uso di `newConnections()` per la persistenza (dati incompleti)

- **ConnectionDescriptor**
    - [x] Analisi dei campi disponibili a connessione conclusa
    - [x] Verifica disponibilità: byte TX/RX, pacchetti, durata, dominio (SNI), app UID

- **HTTPReassembly**
    - [x] Analisi del meccanismo di ricostruzione HTTP
    - [x] Verificato che path e payload non sono disponibili per HTTPS
    - [x] Decisione di non utilizzare HTTPReassembly come fonte primaria dei dati

- **AppsResolver / AppsLoader**
    - [x] Mappatura UID → nome applicazione

- **Geolocation**
    - [x] Recupero coordinate GPS associate alla connessione

---

## Fase 2 - Integrazione con codice di tesi (Android)

- [x] Creato package `serri.tesi`
- [x] Implementato `TrackerService` come punto centrale di raccolta dati
- [x] Implementato `TrackerRepository` per persistenza su SQLite
- [x] Implementato `TesiDbHelper` con schema dedicato
- [x] Definito modello dati finale `NetworkRequestRecord`
    - [x] Ogni record rappresenta una connessione di rete conclusa
    - [x] Include metadata di rete, applicazione, traffico, durata e GPS
    - [x] Include parametri URL per HTTP (method, host, path)
    - [x] Gestione limiti HTTPS (solo dominio/SNI)

---

## Fase 3 - Hook su PCAPdroid

- [x] Rimosso qualsiasi logging da `newConnections()`
- [x] Aggiunto hook in `connectionsUpdates()`
- [x] Logging effettuato solo a connessione conclusa (`STATUS_CLOSED`)
- [x] Inserito flag di protezione per evitare duplicati (`loggedFinal`)
- [x] Recupero informazioni finali da `ConnectionDescriptor`
- [x] Recupero nome applicazione tramite `AppsResolver`
- [x] Persistenza su database SQLite

---

## Fase 4 - Verifica funzionamento Android

- [x] Verifica inserimento dati tramite Database Inspector
- [x] Confermata persistenza di connessioni reali
- [x] Verificata coerenza con traffico mostrato da UI PCAPdroid
- [x] Confermata intercettazione di DNS, TLS, HTTPS, QUIC
- [x] Confermata assenza di path HTTP su HTTPS (limite tecnico documentato)

---

## Fase 5 - Cache locale e sincronizzazione backend (Android)

- [x] Esteso schema DB con stato di sincronizzazione (`synced`)
- [x] Incrementata versione del database
- [x] Aggiornato modello `NetworkRequestRecord` con `id` e `synced`
- [x] Implementate query di batch:
    - [x] Recupero record non sincronizzati (`synced = 0`)
    - [x] Marcatura record sincronizzati (`markAsSynced`)
- [x] Definiti DTO di rete (`NetworkRequestDto`)
- [x] Definito wrapper batch (`BatchDto`)
- [x] Implementato mapping DB → DTO
- [x] Implementato client HTTP Android (OkHttp)
- [x] Implementato servizio di sincronizzazione (`SyncService`)
- [x] Definita politica di invio batch (manuale, non realtime)
- [x] Gestione robusta errori di rete e autenticazione

---

## Fase 6 - Backend (NestJS + PostgreSQL + Prisma)

- [x] Setup progetto backend NestJS
- [x] Setup PostgreSQL tramite Docker
- [x] Integrazione Prisma ORM
- [x] Definito schema Prisma:
    - [x] Modello `User` con ruoli (`USER`, `ADMIN`)
    - [x] Modello `NetworkRequest` con dati anonimizzati
- [x] Applicata migrazione iniziale del database
- [x] Implementato `PrismaModule` e `PrismaService`

---

## Fase 7 - Autenticazione e autorizzazione (JWT)

- [x] Implementato modulo `Auth`
- [x] Implementato login con email/password
- [x] Password hashate con bcrypt
- [x] Generazione JWT con ruolo e userId
- [x] Implementato `JwtAuthGuard`
- [x] Test autenticazione via terminale (curl / Invoke-WebRequest)
- [x] Implementato seed utenti (USER / ADMIN)
- [x] Verificato accesso a endpoint protetti

---

## Fase 8 - GDPR e gestione dati utente (Backend)

- [x] Endpoint `POST /network-requests/batch`
- [x] Test end-to-end invio dati Android → backend
- [x] Endpoint `GET /network-requests/me/data`
- [x] Endpoint `GET /network-requests/me/data/export` (CSV)
- [x] Endpoint `DELETE /network-requests/me/data`
- [x] Gestione BigInt → JSON per compatibilità API
- [x] Verifica completa tramite Prisma Studio

---

## Fase 9 - Integrazione Android ↔ Backend (JWT reale)

- [x] Implementata classe `SessionManager` (SharedPreferences)
- [x] Gestione login JWT reale da Android
- [x] Separazione AuthClient / BackendClient
- [x] Inclusione automatica JWT in header Authorization
- [x] Gestione token mancante o scaduto (401 → logout)
- [x] Blocco invio dati se utente non autenticato
- [x] Gestione errori e assenza di crash

---

## Fase 10 - UI minima 

- [x] Creata Activity dedicata alla tesi
- [x] Login utente backend
- [x] Avvio cattura PCAPdroid
- [x] Pulsante invio batch manuale
- [x] Pulsante export CSV
- [x] Pulsante cancellazione dati GDPR
- [x] Feedback utente tramite Toast / Log

---

## Fase 11 - Trasparenza e robustezza

- [x] Warning al primo utilizzo (informativa privacy)
- [x] Persistenza consenso tramite SharedPreferences
- [x] Nessuna chiamata backend sul main thread
- [x] Gestione completa degli errori di rete
- [x] Applicazione stabile (no crash)

---

## Estensioni future (opzionali)

- [ ] Endpoint CRUD amministratore
- [ ] Analisi aggregata dei dati
- [ ] Query per app / dominio / protocollo
- [ ] Integrazione PostGIS per geo-query
