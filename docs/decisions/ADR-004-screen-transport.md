# ADR-004: Transport der Bildschirmuebertragung

Status: angenommen · Datum: 2026-09-13

## Kontext

`screen.view` soll den Bildschirm eines gekoppelten Geraets im Control Center sichtbar machen.
Die Roadmap (Phase 4) nannte dafuer WebRTC. Konstitution Punkt 13 verlangt gleichzeitig
Internet-first: kein Feature darf voraussetzen, dass Control Center und Zielgeraet im selben
Netzwerk sind, und die Peer-Verbindung ist ausdruecklich als *Latenzoptimierung* eingestuft.

Zu entscheiden war, ueber welche Strecke kodierte Frames laufen - und mit welcher
Sicherheitszusage.

## Entscheidung

**Die Frames laufen in v1 ueber die bestehende Agent-WebSocket-Verbindung zum Control Server und
von dort per Server-Sent Events an den Browser. Kein WebRTC, kein STUN, kein TURN.**

Kodierung: H.264 ueber `MediaCodec`, gedeckelt auf `SCREEN_MAX_DIMENSION`, `SCREEN_MAX_FPS` und
`SCREEN_MAX_BITRATE_KBPS`. Zerlegung in `SCREEN_CHUNK_BYTES`-Stuecke wie bei Dateien, Gegendruck
ueber ein Fenster von `SCREEN_FRAME_WINDOW` unbestaetigten Frames. Dekodierung im Browser ueber
die WebCodecs-`VideoDecoder`-API auf ein Canvas.

## Begruendung

### WebRTC waere hier nicht Ende-zu-Ende-sicher

Das ist der ausschlaggebende Punkt, und er wird leicht falsch erzaehlt. DTLS-SRTP verschluesselt
zwischen den Peers; ein *fremdes* TURN-Relay sieht dabei nur verschluesselte Pakete. Die
DTLS-Fingerprints werden aber im SDP ausgetauscht - und der Signalisierungsweg waere unser eigener
Control Server. Wer die Signalisierung stellt, kann Fingerprints tauschen und sitzt in der Mitte.

Ende-zu-Ende gegen den eigenen Server waere WebRTC nur mit einer Bindung des DTLS-Fingerprints an
den Geraeteschluessel im Keystore - und mit einem Schluessel auf Browserseite, den das Geraet aus
dem Pairing kennt. Beides gibt es nicht. WebRTC wuerde also Sicherheit gegen einen Dritten kaufen,
den es in diesem Aufbau gar nicht gibt, und gegen den Betreiber nichts aendern.

Die Entscheidung fuer den Serverpfad kostet an dieser Stelle folglich **nichts**, was WebRTC
tatsaechlich geliefert haette. Dass der Serverbetreiber mitsehen kann, steht als 4.15 im Threat
Model - nicht als Fussnote, sondern als Bedrohung mit Impact hoch.

### Internet-first ohne Sonderfall

Der Agent ist bereits verbunden, sonst gaebe es nichts zu zeigen. Dieselbe Verbindung zu benutzen
heisst: der Strom funktioniert ueberall dort, wo die App funktioniert - hinter CGNAT, im
Mobilfunk, hinter einer Firewall, die nur 443 durchlaesst. Eine Peer-Verbindung braucht dagegen
NAT-Traversal, und wenn die scheitert, braucht sie ein Relay, also genau die Infrastruktur, die
WebRTC vermeiden sollte. Eine Optimierung darf nicht die Voraussetzung dafuer sein, dass ein
Feature ueberhaupt geht.

### Eine Uebertragungsstrecke statt zwei

Chunking, Gegendruck und Sequenzpruefung sind die Stellen, an denen ein Fehler am teuersten ist.
Dieselbe Ueberlegung wie bei Medien (Protokoll 8.4): eine zweite, parallele Implementierung waere
eine zweite Gelegenheit, sie falsch zu bekommen.

### Kein libwebrtc auf Android

`org.webrtc:google-webrtc` ist archiviert; die gepflegten Forks sind gross und aendern die
Angriffsflaeche der App erheblich. `MediaCodec` ist eine Plattform-API, die ohnehin im Spiel waere.

### SSE statt eines zweiten WebSockets zum Browser

Der Browser braucht nur die Abwaertsrichtung. Ein `GET` mit `text/event-stream` erbt die
Cookie-Sitzung und die Origin-Pruefung, die `GET /devices/{id}/files/content` heute schon benutzt.
Die Gegenrichtung - Stop und Keyframe - sind normale, CSRF-geschuetzte Aufrufe. Ein zweiter
WebSocket haette einen zweiten Authentisierungspfad bedeutet, und der waere die interessantere
Angriffsflaeche gewesen als die ersparten Base64-Bytes.

## Alternativen

| Alternative | Warum nicht |
| --- | --- |
| WebRTC mit eigenem TURN | Gegen den Serverbetreiber kein Gewinn (siehe oben), dafuer NAT-Traversal, Relay-Betrieb, Bandbreitenkosten und eine grosse native Abhaengigkeit. |
| WebRTC mit fremdem TURN | Zusaetzlich ein weiterer Betreiber im Weg, den man ebenfalls nicht kontrolliert. |
| MJPEG / Einzelbilder als PNG | Ein Vielfaches an Bandbreite bei schlechterem Bild; kein Keyframe-Konzept, also auch keine sinnvolle Verlustbehandlung. |
| Binaerer WebSocket zum Browser | Spart Base64-Aufschlag, kostet einen zweiten Authentisierungspfad. Als spaetere Optimierung offen. |
| Auf `screen.view` verzichten | Die Funktion ist Teil des Auftrags; Verzicht waere keine Entscheidung, sondern ein Ausweichen. |

## Konsequenzen

- Der Serverbetreiber kann den Bildschirm mitsehen. Der Widerrufspfad ist organisatorisch:
  den Server selbst betreiben. Steht als 4.15 im Threat Model und als offener Punkt 6.
- Die Bandbreite laeuft ueber den Server. Bei `SCREEN_MAX_BITRATE_KBPS = 2500` plus Base64-Aufschlag
  sind das rund 3,3 Mbit/s pro laufendem Strom, und `SCREEN_MAX_CONCURRENT_STREAMS = 1` begrenzt
  das pro Geraet.
- Die Latenz ist hoeher als bei einer Peer-Verbindung. Fuer Zusehen ist das tragbar. Fuer
  `screen.control` waere es das womoeglich nicht - dann ist diese Entscheidung neu zu pruefen,
  nicht fortzuschreiben.
- WebCodecs schliesst aeltere Browser aus. Das Control Center sagt das, statt ein leeres Bild zu
  zeigen.
- Wird spaeter echte Ende-zu-Ende-Verschluesselung gebaut, gehoert sie **auf** diese Strecke
  (verschluesselte Frames durch denselben Kanal) und nicht in einen WebRTC-Umbau. Der Transport
  ist nicht das Problem, das fehlende Schluesselmaterial ist es.
