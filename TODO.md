# TODO - Tesi:

## Fase 1 - Setup progetto
- [x] Creazione fork del repo
- [x] Clonato repo in Android Studio
- [x] Creato branch `tesi`
- [x] Identificare punto di cattura pacchetti 
  - cartella principale: app/src/main/java/com/emanuelef.remote_capture/
  1) Vpn Service
  - file chiave: CaptureService.java
  2) Gestione pacchetti/ connessioni
  - ConnectionsRegister: tiene traccia delle connessioni attive e terminate
  - HTTPReassembly: ricostruisce i flussi http da pacchetti
  - CaptureHelper: utility che legge pacchetti e invia a dispatcher
  3) Altro
  - Geolocation: gestione info su posizione
  - AppsLoader/ AppsResolver: mappano pacchetti delle app android
  - Utils: funzioni di supporto (byte parsing, timestamp, conversioni,...)
  
- [ ] Aggiungere logging minimo
- [ ] ...


