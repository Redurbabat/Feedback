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

Feedback ist internet-first (`FEEDBACK_CONSTITUTION.md` Punkt 13). Geraet und Control Center
sprechen nie direkt miteinander, sondern jeweils ausgehend mit dem Control Server:

```text
Android-Agent  --HTTPS/WSS-->  Control Server  <--HTTPS/WSS--  Control Center
```

Beide Seiten verbinden sich nach aussen. Es gibt keine eingehende Verbindung, keine Portfreigabe
und keine Netzwerk-Discovery. Das Geraet ist damit ueber Mobilfunk, im fremden WLAN und hinter NAT
genauso erreichbar wie zu Hause - die Frage "sind beide im selben Netz" stellt sich nicht.

Phase 1 nutzt HTTPS/WSS fuer Pairing, Presence und Control-Metadaten. Dateiinhalte laufen ueber
dieselbe Strecke: das Geraet streamt Chunks ueber seine WebSocket-Verbindung, der Server reicht sie
an die HTTP-Antwort des Control Centers weiter und speichert sie nicht.

Fuer Bildschirm und Medien ist spaeter WebRTC vorgesehen. Dabei gilt Punkt 13 unveraendert: eine
direkte Peer-Strecke ist eine Latenz-Optimierung, nicht die Voraussetzung. Ein STUN/TURN-Pfad
gehoert deshalb zur Mindestausstattung dieses Milestones und nicht in eine spaetere Ausbaustufe -
zwischen zwei Mobilfunknetzen oder hinter symmetrischem NAT scheitert eine reine Peer-Verbindung
regelmaessig. Was ein Relay sieht und was er nicht speichert, wird vor der Einfuehrung im Threat
Model behandelt.

## Datenprinzip

- Control-Plane und Content-Plane getrennt halten.
- Kein pauschaler Dateisystemzugriff, wenn Android eine engere API anbietet.
- Inhalte nach Moeglichkeit direkt zwischen autorisierten Geraeten uebertragen.
- Server speichert keine Nutzinhalte, solange dies nicht explizit als eigenes Feature entworfen wurde.
