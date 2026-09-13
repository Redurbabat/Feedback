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
- `network`: API und WebSocket (kein WebRTC, siehe ADR-004)
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

Der Bildschirmstrom nimmt denselben Weg: das Geraet kodiert mit `MediaCodec`, schickt die Frames
in Stuecken ueber seine WebSocket-Verbindung, und der Server reicht sie als Server-Sent Events an
das Control Center weiter, ohne sie zu speichern.

**WebRTC, STUN und TURN sind kein Bestandteil von v1.** Die frueher hier vorgesehene Peer-Strecke
haette gegen den eigentlichen Gegner nichts gebracht: DTLS-SRTP schuetzt gegen ein fremdes Relay,
aber die Fingerprints laufen ueber die Signalisierung - und die waere unser eigener Server. Die
vollstaendige Begruendung steht in ADR-004, die Folge als Bedrohung 4.15 im Threat Model: der
Serverbetreiber kann mitsehen, und der Widerrufspfad dagegen ist, den Server selbst zu betreiben.

Punkt 13 gilt unveraendert und spricht hier sogar fuer den Serverpfad: eine direkte Peer-Strecke
ist eine Latenz-Optimierung, nicht die Voraussetzung, und ein Strom ueber die ohnehin bestehende
Agent-Verbindung funktioniert ueberall dort, wo die App funktioniert.

## Datenprinzip

- Control-Plane und Content-Plane getrennt halten.
- Kein pauschaler Dateisystemzugriff, wenn Android eine engere API anbietet.
- Inhalte nach Moeglichkeit direkt zwischen autorisierten Geraeten uebertragen.
- Server speichert keine Nutzinhalte, solange dies nicht explizit als eigenes Feature entworfen wurde.
