# Roadmap

## Phase 0 - Fundament

- [x] Repository initialisieren
- [x] AI-Handoff und Engineering Constitution
- [x] Vision, Architektur und Security Model
- [ ] Android-Grundprojekt
- [ ] CI-Build fuer Android

## Phase 1 - Lokale Android-Basis

- [ ] native Compose-Oberflaeche
- [ ] lokaler App-Lock
- [ ] Android-Keystore-Identitaet
- [ ] Device-ID/Fingerprint
- [ ] Berechtigungszentrale
- [ ] sauberer Hintergrund-Lifecycle

## Phase 2 - Pairing und Presence

- [ ] Server-Grundprojekt
- [ ] Control-Web-Grundprojekt
- [ ] kurzlebige Pairing-Tickets
- [ ] QR-Code und Zahlencode
- [ ] Public-Key-Registrierung
- [ ] Online/Offline/Last-Seen
- [ ] Geraet widerrufen

## Phase 3 - Sichere Basisfunktionen

- [ ] Systeminformationen
- [ ] Akku/Speicher/OS
- [ ] explizit freigegebene Dateien
- [ ] Fotos/Videos ueber Android APIs
- [ ] Transfer-Limits und Backpressure
- [ ] Audit-Ereignisse

## Phase 4 - Bildschirm

- [ ] MediaProjection-Flow
- [ ] sichtbare Session-Anzeige
- [ ] Video-Encoding
- [ ] WebRTC-Transport
- [ ] Session-Ende/Widerruf

## Phase 5 - Fernsteuerung

- [ ] Plattformgrenzen dokumentieren
- [ ] lokale explizite Aktivierung
- [ ] Input-Protokoll
- [ ] sichtbarer Stop-Mechanismus
- [ ] Rate Limits und Session-Timeout
- [ ] physische Geraetetests

## Phase 6 - Windows

- [ ] nativer Windows-Agent
- [ ] gemeinsame Protokollkompatibilitaet
- [ ] Bildschirm und Input
- [ ] Dateien und Systeminformationen
- [ ] Installer und Updates

## Release-Gate

Vor jeder produktiven Remote-Control-Version muessen Threat Model, Pairing-Tests, Widerruf, Session-Timeout, physischer Geraetetest und sichtbarer lokaler Stop erfolgreich sein.
