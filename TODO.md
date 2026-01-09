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
- [x] Definita politica di invio batch (non realtime)

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

## TODO successivi

### Backend – funzionalità GDPR e API core
- [x] Endpoint `POST /network-requests/batch`
- [x] Test end-to-end invio dati Android → backend
- [x] Endpoint `GET /me/data`
- [x] Endpoint `GET /me/data/export` (CSV)
- [x] Endpoint `DELETE /me/data`
- [ ] Endpoint CRUD amministratore su dati utenti
- [ ] Applicazione anonimizzazione lato mobile e/o backend
- [ ] Download dati utente in formato CSV (GDPR)

### Frontend / UI
- [ ] UI dedicata per esame LAM
- [ ] Avviso al primo utilizzo (informativa privacy)
- [ ] Login utente backend da mobile
- [ ] Pulsante invio batch manuale
- [ ] Pulsante download / delete dati

### Estensioni e analisi
- [ ] Analisi dei dati persistiti
- [ ] Query di aggregazione (per app, dominio, protocollo)
- [ ] Studio performance database
- [ ] Integrazione PostGIS per geo-query (opzionale)
