# Security Model

## Schutzobjekte

Feedback schuetzt insbesondere:

- Geraeteidentitaeten
- Pairing-Tickets
- Session-Tokens
- freigegebene Dateien und Medien
- Bildschirmstreams
- Remote-Input-Sessions
- Audit-Metadaten

## Vertrauensmodell

Ein installiertes Geraet ist nicht automatisch vertrauenswuerdig. Vertrauen entsteht erst nach expliziter Kopplung und wird an einen kryptografischen Geraeteschluessel gebunden.

Der Server darf einen Geraetenamen, OS-String oder Modellnamen niemals als Identitaetsnachweis behandeln.

## Geraeteschluessel

- pro Installation ein eigenes asymmetrisches Schluesselpaar
- Erzeugung im Android Keystore
- privater Schluessel nicht exportierbar, sofern das Geraet dies unterstuetzt
- Server speichert nur Public Key und Fingerprint
- Rotation und Widerruf werden als eigene Protokolloperationen vorgesehen

## Pairing

Pairing nutzt einen kurzlebigen, einmalig verwendbaren Code bzw. QR-Payload. Ein Pairing muss auf der kontrollierenden Seite sichtbar bestaetigt werden. Nach erfolgreicher Kopplung wird das Ticket ungueltig.

Pairing erteilt nur Basisvertrauen. Hochprivilegierte Capabilities werden separat freigegeben.

## Sessions

Jede Remote-Sitzung besitzt:

- initiierendes Geraet
- Zielgeraet
- erlaubte Capabilities
- Ablaufzeit
- Nonce/Session-ID
- Widerrufszustand

Abgelaufene oder widerrufene Sessions werden lokal und serverseitig abgelehnt.

## Capabilities

Capabilities sind deny-by-default. Beispiele:

- `system.info`
- `files.read`
- `media.photos.read`
- `media.videos.read`
- `screen.view`
- `screen.control`
- `clipboard.read`
- `clipboard.write`

`screen.control` impliziert nicht automatisch `files.read` oder andere Rechte.

## Lokale Sichtbarkeit

Bildschirmfreigabe und Fernsteuerung muessen dem lokalen Nutzer sichtbar sein. Android-Systemindikatoren, Foreground-Service-Notifications und MediaProjection-Dialoge werden nicht verborgen oder umgangen.

## PIN/Biometrie

Eine lokale App-Sperre darf als zusaetzliche Schutzschicht dienen. Biometrie ist Komfort, nicht alleinige Geraeteidentitaet. Sicherheitskritische Aenderungen koennen erneute lokale Authentisierung verlangen.

## Logging

Logs duerfen enthalten:

- Session-ID
- Zeitpunkt
- Device-ID/Fingerprint
- Capability
- Erfolg/Fehlercode

Logs duerfen nicht enthalten:

- PINs
- Passwoerter
- private Schluessel
- Session-Secrets
- Dateiinhalte
- Bildschirmframes

## Bedrohungen

Mindestens zu testen und zu behandeln:

- erratene Pairing-Codes
- Replay alter Pairing-Nachrichten
- gestohlene Session-Tokens
- widerrufenes Geraet
- manipulierte Capability-Anfragen
- veralteter Client mit unbekannten Protokollfeldern
- MITM auf Signaling/Control-Plane
- kompromittierter Server
- gerootetes bzw. kompromittiertes Endgeraet

Ein kompromittiertes/rooted Endgeraet kann nicht vollstaendig abgesichert werden. Die App soll diesen Zustand, soweit verlaesslich erkennbar, als erhoehtes Risiko behandeln, aber keine falsche Sicherheitsgarantie geben.
