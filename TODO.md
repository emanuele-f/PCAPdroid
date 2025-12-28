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

## Fase 2 - Integrazione con codice di tesi

- [x] Creato package `serri.tesi`
- [x] Implementato `TrackerService` per la gestione centralizzata del logging
- [x] Implementato `TrackerRepository` per persistenza su SQLite
- [x] Implementato `TesiDbHelper` con schema dedicato
- [x] Definito nuovo modello dati `NetworkRequestRecord`
    - [x] Ogni record rappresenta una connessione di rete conclusa
    - [x] Include metadata di rete, app, durata, traffico e GPS

---

## Fase 3 - Hook su PCAPdroid

- [x] Rimosso qualsiasi logging da `newConnections()`
- [x] Aggiunto hook in `connectionsUpdates()`
- [x] Logging effettuato solo a connessione conclusa (`STATUS_CLOSED`)
- [x] Inserito flag di protezione per evitare duplicati (`loggedFinal`)
- [x] Recupero informazioni finali da `ConnectionDescriptor`
- [x] Recupero nome app tramite `AppsResolver`
- [x] Persistenza su database SQLite

---

## Fase 4 - Verifica funzionamento

- [x] Verifica inserimento dati tramite Database Inspector
- [x] Confermata persistenza di connessioni reali
- [x] Verificata coerenza con traffico mostrato da UI PCAPdroid
- [x] Confermata intercettazione di DNS, TLS, HTTPS, QUIC
- [x] Confermata assenza di path HTTP su HTTPS (limite tecnico)

---

## Fase 5 - Cache locale e preparazione invio backend (Android)

- [x] Esteso schema DB con stato di sincronizzazione (`synced`)
- [x] Incrementata versione del database
- [x] Aggiornato modello `NetworkRequestRecord` con `id` e `synced`
- [x] Implementate query di batch:
    - [x] Recupero record non sincronizzati (`synced = 0`)
    - [x] Marcatura record sincronizzati (`markAsSynced`)
- [x] Definiti DTO di invio (`NetworkRequestDto`)
- [x] Definito wrapper batch (`BatchDto`)
- [x] Implementato mapping DB → DTO
- [x] Implementato client HTTP Android (OkHttp)
- [x] Implementato servizio di sincronizzazione (`SyncService`)
- [x] Definita politica di invio batch (non realtime)

---

## TODO successivi

### Backend e privacy
- [ ] Creazione backend REST (Node.js / Express)
- [ ] Endpoint `POST /network_requests/batch`
- [ ] Test end-to-end invio dati Android → backend
- [ ] Implementazione anonimizzazione dati (IP, dominio, user UUID)
- [ ] Applicazione anonimizzazione prima dell’invio remoto

### Analisi e valutazione
- [ ] Analisi dei dati persistiti (pattern di utilizzo)
- [ ] Query di aggregazione (per app, dominio, protocollo)
- [ ] Studio delle performance del database locale
