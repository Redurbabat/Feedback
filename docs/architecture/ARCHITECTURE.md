# Architektur

## Komponenten

```text
                 Control Center
                      |
                 HTTPS / WSS
                      |
                Control Server
                 /           \
          Pairing/Auth      Signaling
                |              |
                +------Session-Policy------+ 
                                       |
                                  Device Agent
                                       |
                         +-------------+-------------+
                         |             |             |
                      Dateien       Bildschirm     System
```

## Android Agent

Die Android-App ist nativ und besteht aus klar getrennten Modulen:

- `ui`: Compose-Oberflaeche
- `security`: Keystore, PIN/Session Lock, lokale Schluessel
- `pairing`: Einladung/Antwort, Device Registration
- `agent`: Lifecycle und Hintergrundzustand
- `network`: API, WebSocket und spaeter WebRTC
- `device`: Akku, Speicher, OS-/Geraeteinformationen
- `files`: explizit freigegebener Dateizugriff
- `media`: Fotos/Videos ueber passende Android APIs
- `screen`: MediaProjection und Streaming
- `permissions`: zentrale Berechtigungslogik

## Server

Der Server verwaltet keine privaten Geraeteschluessel. Er ist fuer folgende Aufgaben zustaendig:

- Konto-/Session-Authentisierung des Control Centers
- kurzlebige Pairing-Tickets
- Geraeteregistrierung mit Public Key
- Presence/Last-Seen
- Session-Authorisierung
- Signaling fuer Peer-Verbindungen
- Audit-Metadaten ohne Inhaltsdaten

## Control Center

Das Control Center zeigt Geraete, Berechtigungen, Sessions und freigegebene Inhalte. Es darf keine native Berechtigungsentscheidung auf dem Endgeraet umgehen.

## Protokoll

`protocol/` definiert gemeinsam verwendete Nachrichtentypen, Versionen, Capabilities, Limits und Fehlercodes. Plattformimplementierungen muessen unbekannte oder nicht freigegebene Nachrichten ablehnen.

## Verbindung

Phase 1 nutzt HTTPS/WSS fuer Pairing, Presence und Control-Metadaten. Fuer Bildschirm/Medien ist spaeter WebRTC vorgesehen. TURN/STUN und Internet-Relay werden erst nach Threat Model und Auth-Design eingefuehrt.

## Datenprinzip

- Control-Plane und Content-Plane getrennt halten.
- Kein pauschaler Dateisystemzugriff, wenn Android eine engere API anbietet.
- Inhalte nach Moeglichkeit direkt zwischen autorisierten Geraeten uebertragen.
- Server speichert keine Nutzinhalte, solange dies nicht explizit als eigenes Feature entworfen wurde.
