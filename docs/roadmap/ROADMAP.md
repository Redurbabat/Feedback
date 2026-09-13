# Roadmap

## Phase 0 - Fundament

- [x] Repository initialisieren
- [x] AI-Handoff und Engineering Constitution
- [x] Vision, Architektur und Security Model
- [x] Android-Grundprojekt
- [x] CI-Build fuer Android
- [x] CI fuer Server und Control Web

## Phase 1 - Lokale Android-Basis

- [x] native Compose-Oberflaeche
- [x] lokaler App-Lock mit PBKDF2-Verifier, persistentem Rate Limit, Auto-Lock und Re-Auth
- [x] optionale biometrische Entsperrung als reine Komfortschicht
- [ ] physischer Test des App-Locks auf echten Geraeten (siehe `docs/testing/ANDROID_DEVICE_MATRIX.md`)
- [x] Android-Keystore-Identitaet
- [x] Device-ID/Fingerprint
- [x] Berechtigungszentrale fuer implementierte Capabilities (`system.info`)
- [x] explizit aktivierbare sichtbare Hintergrundverbindung als Foreground Service
- [ ] physischer Langzeit-/Akku-Test des Hintergrund-Lifecycles auf Android 8 bis 16

## Phase 2 - Pairing und Presence

- [x] Server-Grundprojekt
- [x] Control-Web-Grundprojekt
- [x] kurzlebige Pairing-Tickets und getrenntes Device-Secret
- [x] sechsstelliger Zahlencode mit Limits und kurzer TTL
- [x] QR-Code des Pairing-Tickets in Android rendern
- [ ] QR-Code scannen (Kamera, erst mit dokumentiertem Bedarf)
- [x] Public-Key-Registrierung und signierter Claim
- [x] verschluesseltes lokales Device-Token
- [x] authentifizierter Agent-WebSocket mit Heartbeat
- [x] Online/Offline/Last-Seen
- [x] Geraet widerrufen und aktive Verbindung beenden

## Phase 3 - Sichere Basisfunktionen

- [x] Live-Systeminformationen ueber kurzlebige Remote-Session
- [x] Akku/Speicher/OS/Netzwerktyp
- [x] getrennte Server- und lokale Freigabe fuer `system.info`
- [~] explizit freigegebene Dateien: Protokoll und Android-Seite implementiert und unit-getestet,
      aber noch **ohne Freigabe-Oberflaeche**, ohne Server- und ohne Control-Web-Seite
- [ ] Fotos/Videos ueber Android APIs
- [~] Transfer-Limits und Backpressure: geraeteseitig implementiert und getestet, serverseitig offen
- [x] Audit-Ereignisse fuer Pairing, Freigaben, Systeminfo und Widerruf

## Phase 4 - Bildschirm

- [x] Protokoll-Abschnitt 8.5, Limits, Threat Model 4.15-4.18 und ADR-004
- [x] MediaProjection-Flow mit Android-Systemdialog pro erforderlicher Sitzung
- [x] sichtbare Session-Anzeige und lokaler Stop
- [x] Video-Encoding (MediaCodec H.264, Groesse und Rate gedeckelt)
- [x] Transport ueber die Agent-Verbindung und SSE (**kein** WebRTC/TURN, ADR-004)
- [x] Session-Ende/Widerruf (Ablauf, Entzug, Verbindungsverlust, Widerruf)
- [ ] Test auf echter Hardware - ohne den gilt keiner der Punkte als abgenommen

## Phase 5 - Fernsteuerung

- [x] Plattformgrenzen dokumentieren (ADR-005, Threat Model Abschnitt 7) - mit dem Ergebnis,
      dass Remote-Input auf Android ohne AccessibilityService nicht geht und die Empfehlung
      lautet, `screen.control` erst zu bauen, wenn ein Bedarf benannt ist, den `screen.view`
      nicht deckt
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

## Offene Arbeit

`docs/roadmap/OPEN_WORK.md` benennt im Detail, was noch fehlt, welche technischen Schulden offen
sind und was nur auf echter Hardware pruefbar ist. Ein `[~]` oben heisst: begonnen, aber nach der
Definition of Done **nicht** fertig.

## Testabdeckung

Automatisiert: Unit-Tests fuer Protokoll, Pairing-Payloads, Kryptografie-Hilfen, App-Lock-Policy,
Lockout-Arithmetik, Auto-Lock, Re-Auth-Fenster, Countdown-Formatierung, QR-Payload und
-Kodierung sowie die komplette `files.read`-Geraeteseite (Eintragsregeln, opake IDs, Cursor,
Chunking, Sendefenster, Sequenzregeln, Limits und Fehlerpfade). CI baut zusaetzlich Lint und die
Debug-APK.

Nicht automatisiert: alles, was echte Hardware braucht. Der Plan dafuer steht in
`docs/testing/ANDROID_DEVICE_MATRIX.md` und ist vollstaendig als
**NOT TESTED ON PHYSICAL DEVICE** markiert.

## Aktueller MVP-Schnitt

Der aktuelle MVP koppelt einen Android-Agenten kryptografisch an das Control Center, haelt
Presence ueber HTTPS/WSS, kann `system.info` nur bei lokaler **und** serverseitiger Freigabe live
abfragen, protokolliert sicherheitsrelevante Aktionen und kann ein Geraet widerrufen.

Die Hintergrundverbindung ist ausdruecklich opt-in und verwendet einen sichtbaren Android
Foreground Service mit dauerhafter Benachrichtigung und Beenden-Aktion. Sie startet nicht heimlich
ueber einen Boot-Receiver. Fuer eine produktive Freigabe bleiben physische Tests auf mehreren
Android-Versionen sowie die Distributions-/Store-Policy-Pruefung des `specialUse`-FGS-Typs offen.

## Release-Gate

Vor jeder produktiven Remote-Control-Version muessen Threat Model, Pairing-Tests, Widerruf,
Session-Timeout, physischer Geraetetest und sichtbarer lokaler Stop erfolgreich sein. Bildschirm-
oder Input-Funktionen duerfen erst dann als fertig markiert werden, wenn die erforderlichen Android-
Systemfreigaben und sichtbaren Sitzungsindikatoren real auf physischen Geraeten getestet wurden.
