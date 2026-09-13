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

Der sechsstellige Anzeigecode ist nur ein Komfort-Lookup und kein alleiniger Sicherheitsanker. Der kryptografische Geraetenachweis, kurze TTLs, Versuchszaehler und serverseitige Rate Limits bleiben erforderlich.

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

Eine Capability ist nur effektiv, wenn Serverfreigabe, lokale Geraetefreigabe, vorhandene OS-Berechtigung und Autorisierung der konkreten Remote-Session gleichzeitig vorliegen.

## Hintergrundverbindung

Eine dauerhafte Agent-Verbindung ist **kein** stiller Standardzustand. Der Besitzer aktiviert sie explizit in der sichtbaren Android-Oberflaeche.

Der aktuelle Android-MVP verwendet dafuer einen Foreground Service mit folgenden Eigenschaften:

- dauerhafte sichtbare Benachrichtigung
- lokale Beenden-Aktion direkt in der Benachrichtigung
- kein Boot-Receiver und kein heimlicher Autostart nach Geraeteneustart
- exponentieller, begrenzter Reconnect mit Jitter statt aggressivem Polling
- Device-Token bleibt im Keystore-geschuetzten lokalen Store
- Widerruf beendet die Agent-Verbindung und macht das gespeicherte Token unbrauchbar
- der Service erweitert keine Capability und kann keine lokale Freigabe ersetzen

Auf Android 13+ wird die Benachrichtigungsberechtigung vor der Aktivierung angefragt. Die App aktiviert den Hintergrundmodus nicht, wenn der Nutzer diese sichtbare Benachrichtigung ablehnt.

Der MVP deklariert den Android-Foreground-Service-Typ `specialUse`, weil die explizit vom Besitzer aktivierte Companion-Verbindung keinem engeren Standardtyp sauber entspricht. Vor einer Store-/Produktionsfreigabe muss diese Einordnung gegen die jeweils aktuelle Android-/Store-Policy geprueft und auf physischen Zielgeraeten getestet werden. Die App darf einen abgelehnten oder unzulaessigen FGS-Start nicht umgehen.

## Lokale Sichtbarkeit

Bildschirmfreigabe und Fernsteuerung muessen dem lokalen Nutzer sichtbar sein. Android-Systemindikatoren, Foreground-Service-Notifications und MediaProjection-Dialoge werden nicht verborgen oder umgangen.

## Lokaler App-Lock

Die Geraeteverwaltung liegt hinter einer lokalen PIN bzw. Passphrase. Sie schuetzt den Zugriff auf
die Oberflaeche und damit auf lokale Freigaben und das gespeicherte Geraete-Token, wenn jemand das
entsperrte Telefon in die Hand bekommt.

Umsetzung:

- Mindestens 6 Ziffern bzw. 8 Zeichen fuer eine Passphrase.
- Verifier: PBKDF2-HMAC-SHA256, 210.000 Iterationen, 16 Byte Zufallssalt, 32 Byte Ausgabe.
- Der Verifier wird zusaetzlich mit dem AES-256-GCM-Keystore-Schluessel des `SecretStore` versiegelt.
- Die Eingabe selbst wird nie gespeichert, nie geloggt und im Speicher ueberschrieben.
- Vergleich ohne fruehen Abbruch auf dem gemeinsamen Praefix.
- Fehlversuche werden persistent gezaehlt: ab dem fuenften Fehlversuch 30 s, 60 s, 2 min, 5 min,
  danach maximal 15 min. Der Zaehler wird vor der Antwort geschrieben und ueberlebt App- und
  Geraeteneustart.
- Auto-Lock nach einem waehlbaren Leerlauffenster (Sofort, 30 s, 1 min, 5 min, Nie). `Nie` ist nie
  Voreinstellung. Die Einstellung liegt im versiegelten Store, damit sie nicht unbemerkt
  aufgeweicht werden kann.
- Eine entsperrte Sitzung ist prozesslokal und wird nach einem Neustart nie wiederhergestellt.
- Deaktivieren des Schutzes verlangt die korrekte Eingabe und ist waehrend einer Wartezeit gesperrt,
  damit es keinen Umweg am Rate Limit vorbei gibt.
- Erneute Authentisierung fuer sicherheitsrelevante Aktionen: lokale Kopplung entfernen und eine
  Capability freigeben. Das Entziehen einer Capability ist nie gesperrt.

Grenzen, ausdruecklich benannt:

- Die Wartezeit haengt an der Systemzeit. Ein Zuruecksetzen der Uhr verkuerzt sie nicht, weil Start
  und Dauer statt eines absoluten Ablaufzeitpunkts gespeichert werden. Ein Vorstellen der Uhr kann
  sie dagegen verkuerzen; ein vertrauenswuerdiger Zeitgeber steht lokal nicht zur Verfuegung.
- Der Fehlversuchszaehler liegt im privaten App-Speicher. Er widersteht einem App-Level-Angreifer,
  nicht jemandem mit Root-Rechten auf dem Geraet.
- Der Lock ist eine App-Schicht. Gegen einen Angreifer, der das Geraet vollstaendig kontrolliert
  oder die App manipuliert, schuetzt er nicht.

## Biometrie

Biometrie ist eine Komfortschicht vor einem bereits per PIN/Passphrase eingerichteten App-Lock und
ausdruecklich **kein** zweiter kryptografischer Faktor: der gespeicherte Verifier ist ein
PBKDF2-Hash, es gibt also kein Geheimnis, das ein biometrisch gebundener Schluessel freigeben
koennte.

- Nur `BIOMETRIC_STRONG` wird akzeptiert. Bietet ein Geraet nur einen schwachen Sensor, meldet sich
  die Funktion als nicht verfuegbar statt still herabzustufen.
- PIN/Passphrase bleibt primaeres Geheimnis und einziger Wiederherstellungsweg.
- Eine laufende Wartezeit gilt auch fuer die biometrische Entsperrung.
- Abbruch oder Fehlschlag entsperren nichts.

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
- kurzzeitiger physischer Zugriff auf das entsperrte Telefon
- Erraten der lokalen PIN
- missbrauchte oder vom OS beendete Hintergrunddienste
- unkontrollierte Reconnect-Schleifen bei instabilen Netzen

Ein kompromittiertes/rooted Endgeraet kann nicht vollstaendig abgesichert werden. Die App soll diesen Zustand, soweit verlaesslich erkennbar, als erhoehtes Risiko behandeln, aber keine falsche Sicherheitsgarantie geben.
