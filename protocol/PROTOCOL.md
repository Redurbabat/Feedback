# Feedback Device Protocol

Status: **v1** (implementiert)

Diese Datei ist die gemeinsame Wahrheit fuer `android/`, `server/` und `control-web/`.
Abweichungen in einer Implementierung sind Fehler in der Implementierung, nicht im Dokument.

## 1. Grundregeln

- Alle Nachrichten und Requests sind versioniert (`version: 1`).
- Deny-by-default: unbekannte Versionen, Nachrichtentypen und Capabilities werden abgelehnt.
- Jede privilegierte Anfrage ist an eine gueltige Remote-Session gebunden.
- Groessen- und Ratenlimits werden serverseitig erzwungen.
- Sicherheitskritische Operationen (Pairing-Start, Pairing-Claim) sind signiert und replay-geschuetzt.
- Der Server speichert niemals private Geraeteschluessel.
- Internet-first: jede Strecke ist eine ausgehende Verbindung zum Control Server. Kein
  Nachrichtentyp und kein Endpunkt setzt voraus, dass Geraet und Control Center im selben
  Netzwerk sind.
- Tokens und Tickets werden serverseitig ausschliesslich als SHA-256-Hash gespeichert.

## 2. Kodierungen

| Name | Bedeutung |
| --- | --- |
| `base64` | Standard-Base64 mit Padding (RFC 4648 §4) |
| `base64url` | URL-sicheres Base64 **ohne** Padding (RFC 4648 §5) |
| `hex` | Kleinbuchstaben-Hex |
| Zeitstempel im Envelope | ISO-8601 UTC, z. B. `2026-09-13T11:22:33.000Z` |
| Zeitstempel in Signatur-Payloads | Epoch-Millisekunden als Dezimalzahl ohne Vorzeichen |

Der Public Key ist immer der **X.509/SubjectPublicKeyInfo (SPKI)** DER-Bytestring einer
EC-P-256-Schluessels, kodiert als `base64`.

## 3. Geraeteidentitaet

- Schluesselpaar: EC `secp256r1` (P-256), erzeugt im Android Keystore.
- Signaturalgorithmus: `SHA256withECDSA` (ASN.1/DER-Signatur).
- `fingerprintHex = hex(sha256(spkiDer))`
- `fingerprint = fingerprintHex` in 4er-Gruppen mit `:` getrennt
  (Beispiel: `a1b2:c3d4:...`, 64 Hex-Zeichen ⇒ 16 Gruppen).
- `deviceId = "fb-" + fingerprintHex[0..23]` (24 Hex-Zeichen).

Server-Pflichtpruefungen bei jeder Registrierung:

1. `deviceId` ist korrekt aus dem Public Key abgeleitet.
2. `fingerprint` ist korrekt aus dem Public Key abgeleitet.
3. Der Public Key ist ein gueltiger EC-P-256-SPKI.
4. Die Signatur verifiziert gegen genau diesen Public Key.

Geraetename, Modell, OS-Version und App-Version sind **Metadaten** und niemals Identitaetsnachweis.

## 4. Kanonische Signatur-Payloads

Ein kanonischer Payload ist UTF-8, zeilenweise mit `\n` verbunden, **ohne** abschliessenden Zeilenumbruch.
Ein Feld darf kein `\n` enthalten; der Server lehnt solche Felder ab (`INVALID_MESSAGE`).

### 4.1 `feedback-pairing-start-v1`

```text
feedback-pairing-start-v1
<deviceId>
<publicKeyBase64>
<fingerprint>
<deviceName>
<platform>
<osVersion>
<sdkInt>
<appVersion>
<nonceBase64Url>
<issuedAtEpochMillis>
```

### 4.2 `feedback-pairing-claim-v1`

```text
feedback-pairing-claim-v1
<pairingId>
<deviceId>
<deviceSecretBase64Url>
<issuedAtEpochMillis>
```

### 4.3 `feedback-server-identity-v1`

```text
feedback-server-identity-v1
<deviceId>
<nonceBase64Url>
<serverPublicKeyBase64>
<issuedAtEpochMillis>
```

Die einzige Nutzlast, die der **Server** signiert. `deviceId` bindet den Nachweis an das fragende
Geraet, `nonce` an genau diese eine Frage, und der Schluessel steht mit drin, damit eine Signatur
nie als Aussage ueber einen anderen Schluessel gelesen werden kann.

### 4.4 `feedback-pairing-v1` (lokaler Nachweis, Bestand)

Der bereits vorhandene lokale Nachweis bleibt als Offline-/Diagnosepfad erhalten:

```text
feedback-pairing-v1
<deviceId>
<publicKeyBase64>
<nonceBase64Url>
<issuedAtEpochMillis>
<expiresAtEpochMillis>
<sixDigitCode>
```

Er ist **kein** online nutzbares Ticket. Online-Pairing laeuft ausschliesslich ueber 4.1/4.2.

## 5. Pairing

### 5.1 Ablauf

```text
Android                     Server                      Control Web (angemeldet)
   |                           |                                |
   |-- POST pairing/start ---->|  Signatur + Ableitung pruefen   |
   |<-- ticket, secret, code --|                                |
   |   QR + 6-stelliger Code   |                                |
   |                           |<--- POST pairing/lookup -------|  ticket ODER displayCode
   |                           |---- Pending-Metadaten -------->|
   |                           |<--- POST pairing/{id}/approve -|  ticket ODER displayCode
   |-- POST pairing/{id}/status|                                |
   |<-- approved --------------|                                |
   |-- POST pairing/{id}/claim |  einmalig, signiert             |
   |<-- deviceToken -----------|                                |
```

### 5.2 Zwei getrennte Geheimnisse

| Geheimnis | Entropie | Haelt | Zweck |
| --- | --- | --- | --- |
| `ticket` | 32 Bytes | Geraet zeigt es im QR-Code | Der Browser identifiziert und bestaetigt genau diese Kopplung |
| `deviceSecret` | 32 Bytes | nur das Geraet | Das Geraet fragt Status ab und holt den `deviceToken` |
| `displayCode` | 6 Ziffern (~20 Bit) | Anzeige auf dem Geraet | reiner Komfort-Lookup, **nie** alleinige Sicherheit |

Der sechsstellige Code ist ausdruecklich **kein** Sicherheitsanker. Er ist durch
Versuchszaehler pro Pairing-Session, Rate Limits und die kurze TTL abgesichert.

### 5.3 Lebenszyklus

- TTL: **5 Minuten** ab `pairing/start`.
- Status: `pending` → (`approved` | `rejected` | `expired`) → `consumed`.
- `approve`/`reject` sind nur im Status `pending` erlaubt und nur vor Ablauf.
- `claim` ist nur im Status `approved` erlaubt und **genau einmal** ausfuehrbar.
- Fehlversuche beim Lookup (`ticket`/`displayCode`) werden pro Pairing-Session gezaehlt.
  Ab `PAIRING_MAX_LOOKUP_ATTEMPTS = 5` wird die Session serverseitig ungueltig.
- `nonce` aus 4.1 wird gespeichert und darf innerhalb des Replay-Fensters nicht erneut auftreten.
- `issuedAt` muss innerhalb von `CLOCK_SKEW_MS = 120000` um die Serverzeit liegen.

### 5.4 Erneutes Pairing

Wird ein bereits bekannter `deviceId` erneut gekoppelt, so wird der bestehende Datensatz
aktualisiert (Metadaten, `revokedAt = null`) und **alle** alten Geraete-Tokens werden ungueltig.
Der Public Key eines bestehenden Geraets darf sich dabei nicht aendern; sonst `INVALID_MESSAGE`.

## 6. REST-API

Basis: `/api/v1`. Antwortformat bei Fehlern immer:

```json
{ "error": { "code": "RATE_LIMITED", "message": "human readable" } }
```

`message` ist fuer Menschen und darf keine Geheimnisse enthalten.

### 6.1 Geraet (unauthentifiziert bzw. mit Geraete-Token)

| Methode | Pfad | Auth | Zweck |
| --- | --- | --- | --- |
| `POST` | `/pairing/start` | keine (signiert) | Pairing eroeffnen |
| `POST` | `/pairing/{pairingId}/status` | `deviceSecret` im Body | Status abfragen |
| `POST` | `/pairing/{pairingId}/claim` | `deviceSecret` + Signatur | Geraete-Token einmalig abholen |
| `POST` | `/agent/server-identity` | keine | Der Server weist nach, welcher Server er ist |
| `GET` | `/agent/me` | `Bearer <deviceToken>` | Registrierung und Capability-Stand pruefen |
| `GET` | `/agent/ws` | `Bearer <deviceToken>` | Presence-/Protokoll-WebSocket |

#### `POST /pairing/start`

```json
{
  "version": 1,
  "device": {
    "deviceId": "fb-a1b2c3d4e5f60718293a4b5c",
    "publicKey": "<base64 SPKI>",
    "fingerprint": "a1b2:c3d4:...",
    "deviceName": "Galaxy S24",
    "platform": "android",
    "osVersion": "16",
    "sdkInt": 36,
    "appVersion": "0.2.0"
  },
  "nonce": "<base64url, 18 Bytes>",
  "issuedAt": 1757760000000,
  "signature": "<base64url ECDSA>"
}
```

Antwort `201`:

```json
{
  "pairingId": "<uuid>",
  "ticket": "<base64url, 32 Bytes>",
  "deviceSecret": "<base64url, 32 Bytes>",
  "displayCode": "493821",
  "qrPayload": "feedback://pair?v=1&ticket=<ticket>",
  "expiresAt": "2026-09-13T11:27:33.000Z",
  "pollIntervalMs": 2000
}
```

`ticket` und `deviceSecret` werden **nur hier** ausgeliefert und serverseitig nur gehasht gespeichert.

#### `POST /pairing/{pairingId}/status`

Request `{ "deviceSecret": "<base64url>" }`
Antwort `{ "status": "pending" | "approved" | "rejected" | "expired" | "consumed", "expiresAt": "..." }`

#### `POST /pairing/{pairingId}/claim`

```json
{
  "deviceSecret": "<base64url>",
  "issuedAt": 1757760100000,
  "signature": "<base64url ueber feedback-pairing-claim-v1>"
}
```

Antwort `200`:

```json
{
  "deviceToken": "<base64url, 32 Bytes>",
  "device": { "id": "<uuid>", "deviceId": "fb-...", "name": "Galaxy S24", "pairedAt": "..." },
  "capabilities": { "granted": [], "requested": [] },
  "serverPublicKey": "<base64, SPKI DER, EC P-256>",
  "serverTime": "2026-09-13T11:23:20.000Z"
}
```

`serverPublicKey` ist **Trust on first use**: dies ist der eine Moment, in dem das Geraet erfaehrt,
zu welchem Server es gehoert. Es speichert den Schluessel neben dem Geraete-Token und laesst sich
ihn danach vor jedem Verbindungsaufbau nachweisen (6.1, `POST /agent/server-identity`). Ein
Geraet, dessen Registrierung diesen Schluessel nicht enthaelt, koppelt neu statt ungeprueft zu
verbinden.

Der Schluessel ist **kein** Geheimnis - er ist oeffentlich und wird von jedem beantworteten
Nachweis mitgeliefert. Was er leistet, ist etwas anderes: die Server-Identitaet haengt damit nicht
mehr an der Adresse. Wer die Adresse erbt, erbt den Schluessel nicht.

Nach `claim` ist die Pairing-Session `consumed`. Geht die Antwort verloren, muss das Geraet
neu koppeln; ein zweiter `claim` wird mit `PAIRING_ALREADY_USED` abgelehnt.

#### `POST /agent/server-identity`

```json
{ "deviceId": "fb-...", "nonce": "<base64url, >= 16 Zeichen>" }
```

Antwort `200`:

```json
{
  "version": 1,
  "serverPublicKey": "<base64, SPKI DER, EC P-256>",
  "issuedAt": 1700000000000,
  "signature": "<base64url, ECDSA/SHA-256, DER>"
}
```

**Bewusst ohne Authentisierung.** Es ist die Anfrage, die das Geraet als **erste** stellt - vor
dem WebSocket, vor `/agent/me`, vor allem, was den `deviceToken` mitschickt. Ueberall sonst weist
sich das Geraet aus und der Server nicht: der Token steht als `Bearer`-Header schon im
Upgrade-Request. Waere hier ein Token noetig, muesste das Geraet ihn abgeben, bevor es weiss, wem.

Beide Richtungen tragen deshalb kein Geheimnis: das Geraet nennt nur seine ohnehin oeffentliche
`deviceId`, der Server seinen ohnehin oeffentlichen Schluessel.

Regeln:

- Die Signatur geht ueber `feedback-server-identity-v1` (4.3) mit genau diesen Werten.
- Die Antwort ist fuer ein bekanntes und ein unbekanntes Geraet **identisch**. Der Endpunkt sagt,
  wer der Server ist, nicht wen er kennt - ein Unterschied waere ein Orakel dafuer, ob ein
  bestimmtes Geraet hier gekoppelt ist.
- Das Geraet vergleicht den gelieferten Schluessel **zuerst** mit dem gespeicherten und prueft die
  Signatur dann gegen den **gespeicherten**, nie gegen den gelieferten. Gegen den gelieferten zu
  pruefen wuerde immer gelingen: ein Hochstapler signiert seinen eigenen Schluessel einwandfrei.
- Stimmt etwas nicht, sendet das Geraet nichts weiter und versucht es nicht erneut. Ein
  wiederholter Versuch hilft nicht - unter der Adresse ist jemand anderes.

`protocol/fixtures/server-identity-v1.json` enthaelt einen echten Nachweis und zwei Ablehnungen,
erzeugt vom Server und gelesen von beiden Seiten.

### 6.2 Control Center (Cookie-Session)

| Methode | Pfad | Zweck |
| --- | --- | --- |
| `POST` | `/auth/login` | Anmeldung, setzt HttpOnly-Session-Cookie |
| `POST` | `/auth/logout` | Abmeldung |
| `GET` | `/auth/session` | aktuelle Session + CSRF-Token |
| `POST` | `/pairing/lookup` | Pending-Pairing per `ticket` oder `displayCode` aufloesen |
| `POST` | `/pairing/{pairingId}/approve` | Kopplung bestaetigen (Proof erforderlich) |
| `POST` | `/pairing/{pairingId}/reject` | Kopplung ablehnen (Proof erforderlich) |
| `GET` | `/devices` | Geraeteliste mit Presence |
| `GET` | `/devices/{id}` | Geraetedetails |
| `PUT` | `/devices/{id}/capabilities` | serverseitig freigegebene Capabilities setzen |
| `POST` | `/devices/{id}/system-info` | Live-Abfrage `system.info` ueber die Agent-Session |
| `POST` | `/devices/{id}/files/session` | `files.read`-Remote-Session eroeffnen |
| `GET` | `/devices/{id}/files/shares` | freigegebene Bereiche auflisten |
| `GET` | `/devices/{id}/files/entries` | Eintraege eines Bereichs auflisten |
| `GET` | `/devices/{id}/files/content` | Datei streamen (Download) |
| `DELETE` | `/devices/{id}/files/session` | `files.read`-Session vorzeitig beenden |
| `POST` | `/devices/{id}/screen/session` | `screen.view`-Sitzung eroeffnen, fragt am Geraet nach |
| `GET` | `/devices/{id}/screen/stream` | Bildstrom als `text/event-stream` (SSE) |
| `POST` | `/devices/{id}/screen/keyframe` | Keyframe anfordern |
| `DELETE` | `/devices/{id}/screen/session` | Bildstrom beenden |
| `POST` | `/devices/{id}/revoke` | Geraet widerrufen |
| `GET` | `/devices/{id}/audit` | Audit-Ereignisse des Geraets |
| `GET` | `/events/ws` | Presence-/Pairing-Ereignisse fuer das Control Center |

`approve`/`reject` verlangen denselben Proof wie `lookup` (`ticket` **oder** `displayCode`).
Eine `pairingId` allein ist keine Autorisierung.

Der Bildstrom ist ein `GET` mit `text/event-stream`, weil ein Browser dafuer keinen zweiten
WebSocket mit eigenem Authentisierungspfad braucht: die Cookie-Session und die Origin-Pruefung
gelten unveraendert, und die Gegenrichtung (Stop, Keyframe) laeuft ueber normale, CSRF-geschuetzte
Aufrufe.

Alle zustandsaendernden Anfragen verlangen den Header `X-Feedback-CSRF` mit dem Wert aus
`GET /auth/session` und einen gueltigen `Origin`/`Sec-Fetch-Site`-Kontext.

### 6.3 Oeffentliche Einrichtungsseiten (kein Protokollbestandteil)

Zwei GET-Routen liegen ausserhalb von `/api/v1` und ausserhalb dieses Protokolls. Sie stehen hier,
damit spaeter niemand aus ihnen ein Protokollelement macht.

| Methode | Pfad | Auth | Zweck |
| --- | --- | --- | --- |
| `GET` | `/pair`, `/pair/` | keine | HTML-Seite fuer den Fall, dass auf dem Geraet keine App auf den Link reagiert |
| `GET` | `/.well-known/assetlinks.json` | keine | Digital-Asset-Links-Datei, nur bei konfiguriertem Signatur-Fingerabdruck; sonst `404` |

Beide werden von Hand oder vom Betriebssystem geoeffnet, nicht von einem Client, der dieses
Protokoll spricht. Beide sind **rate-limitiert**: sie werden oeffentlich beworben - gedruckt,
weitergeleitet, gescannt - und brauchen deshalb dieselbe Obergrenze wie die API. Sie nehmen dafuer
das `apiDefault`-Budget aus Abschnitt 11 (600 je 10 Minuten), zaehlen es aber in einem **eigenen
Schluessel-Scope** (`ip-setup`): im Mobilfunknetz kommen Besitzer und Fremde regelmaessig von
derselben IP, und wer die Einrichtungsseite flutet, soll damit nicht das API-Budget dieser IP
aufbrauchen. Einen Prinzipal gibt es hier nicht, gezaehlt wird allein die IP.

Beide antworten anonym: keine Sitzung, kein Cookie, und - der Teil, der zaehlt - **keine Aussage
ueber den Kopplungszustand** dieser Installation. Weder wie viele Geraete registriert sind, noch
ob gerade eine Kopplung offen ist, noch wem sie gehoert. Die Seite nennt allein die Origin, zu der
sie gehoert; `assetlinks.json` nennt Paketnamen und genau einen
Fingerabdruck und wird als `application/json` ausgeliefert, weil dieser Medientyp von aussen
vorgeschrieben ist.

Ausdruecklich **nicht**:

- **keine neue Wire-Nachricht.** Abschnitt 7.1 bleibt unveraendert.
- **keine neue Capability.** Abschnitt 8.1 bleibt unveraendert.
- **kein Schritt im Pairing-Ablauf aus Abschnitt 5.** Ein Einrichtungslink traegt kein Ticket, kein
  Geheimnis und keinen Parameter - er zeigt auf eine Origin und sonst nichts. Der kryptografische
  Ablauf, die Bestaetigung im Control Center und die Reihenfolge der Schritte bleiben, wie sie
  sind.

Ein Link ist damit auch kein Vertrauensanker. Was die App mit einer Origin aus einem Link tun darf,
entscheidet sie allein: sie akzeptiert sie nur, wenn sie zeichengleich ist mit der eingebauten oder
der bereits registrierten Origin, und in ihr Adressfeld schreibt sie sie nur, solange das Geraet
nicht gekoppelt ist (`docs/security/THREAT_MODEL.md` 4.20). Der Server kann daran nichts erlauben
und nichts verbieten.

## 7. WebSocket-Envelope

```json
{
  "version": 1,
  "type": "system.info.request",
  "messageId": "<uuid v4>",
  "sessionId": "<uuid|null>",
  "relatesTo": "<uuid v4|absent>",
  "timestamp": "2026-09-13T11:22:33.000Z",
  "payload": {}
}
```

`relatesTo` ist die Korrelation auf Nachrichtenebene: **jede Antwort** traegt den `messageId`
ihrer Anfrage darin. Anfragen lassen das Feld weg.

Es steht bewusst im Envelope und nicht im Payload: es beschreibt die Nachricht, nicht ihren
Inhalt, genau wie `messageId`. Und es ist bewusst nicht `sessionId`: eine Remote-Session ist der
Autorisierungsrahmen und traegt mehrere gleichzeitige Anfragen, taugt also nicht als
Korrelationsschluessel. Wer nach `sessionId` korreliert, verwechselt die zweite Anfragen einer
Files-Session mit der ersten.

Ein `error` nennt die Bezugsnachricht zusaetzlich in seinem Payload (`relatesTo`), damit ein
Fehler auch dann zuzuordnen ist, wenn er keine Antwort auf eine gueltige Anfrage ist.

Regeln:

- `version` ≠ 1 ⇒ `UNSUPPORTED`.
- Unbekannter `type` ⇒ `UNSUPPORTED`.
- Fehlende oder ungueltige Felder ⇒ `INVALID_MESSAGE`.
- `messageId` ist eine UUID v4 und pro Verbindung eindeutig; Wiederholung ⇒ `INVALID_MESSAGE`.
- `relatesTo` ist, wenn vorhanden, eine UUID v4. Eine Antwort ohne `relatesTo` wird verworfen:
  sie laesst sich keiner offenen Anfrage zuordnen.
- `timestamp` ausserhalb `CLOCK_SKEW_MS` ⇒ `INVALID_MESSAGE`.
- Maximale Rahmengroesse: `MAX_FRAME_BYTES = 65536`.
- Privilegierte Anfragen ohne `sessionId` ⇒ `SESSION_EXPIRED`.

### 7.1 Nachrichtentypen v1

| Typ | Richtung | Payload |
| --- | --- | --- |
| `agent.hello` | Geraet → Server | `{ appVersion, osVersion, sdkInt, deviceName, grantedCapabilities: string[] }` |
| `agent.hello.ack` | Server → Geraet | `{ serverTime, heartbeatIntervalMs, serverGrantedCapabilities: string[] }` |
| `agent.heartbeat` | Geraet → Server | `{ }` |
| `agent.heartbeat.ack` | Server → Geraet | `{ serverTime }` |
| `capability.state` | Geraet → Server | `{ grantedCapabilities: string[] }` |
| `capability.update` | Server → Geraet | `{ serverGrantedCapabilities: string[] }` |
| `system.info.request` | Server → Geraet | `{ }` (benoetigt `sessionId` und `system.info`) |
| `system.info.response` | Geraet → Server | siehe 8.2 |
| `files.shares.request` | Server → Geraet | `{ }` (benoetigt `sessionId` und `files.read`) |
| `files.shares.response` | Geraet → Server | siehe 8.3.4 |
| `files.list.request` | Server → Geraet | siehe 8.3.4 |
| `files.list.response` | Geraet → Server | siehe 8.3.4 |
| `files.metadata.request` | Server → Geraet | siehe 8.3.4 |
| `files.metadata.response` | Geraet → Server | siehe 8.3.4 |
| `files.download.start` | Server → Geraet | siehe 8.3.6 |
| `files.download.chunk` | Geraet → Server | siehe 8.3.6 |
| `files.download.ack` | Server → Geraet | siehe 8.3.6 |
| `files.download.complete` | Geraet → Server | siehe 8.3.6 |
| `files.download.cancel` | beide | siehe 8.3.7 |
| `screen.start` | Server → Geraet | siehe 8.5.4 |
| `screen.consent` | Geraet → Server | siehe 8.5.4 |
| `screen.started` | Geraet → Server | siehe 8.5.5 |
| `screen.frame` | Geraet → Server | siehe 8.5.6 |
| `screen.frame.ack` | Server → Geraet | siehe 8.5.7 |
| `screen.keyframe.request` | Server → Geraet | siehe 8.5.7 |
| `screen.stop` | beide | siehe 8.5.8 |
| `device.revoked` | Server → Geraet | `{ reason: "revoked_by_owner" }` |
| `error` | beide | `{ code, message, relatesTo?: messageId }` |

`agent.heartbeat` wird alle `heartbeatIntervalMs` (Standard 30000) gesendet. Bleiben
`3` Heartbeats aus, gilt das Geraet als offline.

## 8. Capabilities

### 8.1 Liste v1

```text
system.info
files.read
media.photos.read
media.videos.read
screen.view
screen.control
clipboard.read
clipboard.write
```

Keine implizite Hierarchie: `screen.control` impliziert nicht `files.read`,
`files.read` impliziert nicht `media.photos.read`.

In v1 sind `system.info`, `files.read`, `media.photos.read` und `media.videos.read`
**implementiert**. Alle anderen Capabilities sind
deklariert, deny-by-default und liefern `UNSUPPORTED`, solange kein Feature dahintersteht.

`screen.view` ist in Abschnitt 8.5 vollstaendig festgelegt, aber noch **nicht** auf allen drei
Seiten umgesetzt. Bis das der Fall ist, bleibt es deny-by-default und antwortet `UNSUPPORTED` -
eine Spezifikation ist keine Implementierung, und dieser Abschnitt beschreibt den Stand des
Codes, nicht den der Absicht.

Effektive Berechtigung:

```text
effective(cap) =
      serverGranted(cap)
  AND deviceGranted(cap)
  AND osPermissionAvailable(cap)
  AND sessionAuthorized(cap)
```

Alle vier Faktoren werden **auf dem Geraet** erneut geprueft. Der Server kann eine lokal
nicht freigegebene Capability nicht erzwingen.

### 8.2 `system.info`

Erlaubte Felder:

```json
{
  "manufacturer": "Samsung",
  "model": "SM-S921B",
  "osVersion": "16",
  "sdkInt": 36,
  "appVersion": "0.2.0",
  "batteryPercent": 81,
  "charging": false,
  "storageTotalBytes": 256000000000,
  "storageFreeBytes": 91000000000,
  "networkType": "wifi",
  "deviceTime": "2026-09-13T11:22:33.000Z",
  "lastAgentActivity": "2026-09-13T11:22:30.000Z"
}
```

Verboten und nicht erhoben: IMEI, Seriennummer, MAC-Adresse, Telefonnummer, Konten,
Standort, Werbe-IDs oder sonstige eindeutige Hardwarekennungen.

`networkType` ist auf `wifi | cellular | ethernet | other | none` beschraenkt.

### 8.3 `files.read`

`files.read` ist **ausschliesslich lesend**. Es gibt in v1 kein `delete`, `rename`, `write`,
`upload` oder `execute`, und es ist auch kein Nachrichtentyp dafuer reserviert.

#### 8.3.1 Freigegebene Bereiche (Shares)

Das Geraet exportiert **nie** sein Dateisystem. Der Besitzer waehlt lokal einzelne Dateien oder
Verzeichnisse aus; jede Auswahl wird zu einem *Share*. Nur Inhalte innerhalb eines Shares sind
ueber das Protokoll sichtbar.

```json
{
  "shareId": "<opaque, base64url>",
  "displayName": "Documents",
  "kind": "tree",
  "capability": "files.read",
  "addedAt": "2026-09-13T11:22:33.000Z"
}
```

`kind` ist `tree` (Verzeichnis), `file` (einzelne Datei) oder `collection` (mehrere einzeln
ausgewaehlte Elemente, siehe Abschnitt 8.4).

`capability` nennt die **eine** Capability, die diesen Bereich regiert: `files.read`,
`media.photos.read` oder `media.videos.read`. Es gibt keine Hierarchie und keine Mehrfachzuordnung.
Ein Bereich, dessen Capability in der Remote-Session nicht autorisiert oder lokal nicht freigegeben
ist, erscheint nicht in `files.shares.response` und ist auch nicht ueber seine `shareId`
erreichbar - eine bekannte `shareId` allein oeffnet nichts.

#### 8.3.2 Opake IDs

Weder `shareId` noch `fileId` duerfen einen echten Pfad, eine Content-URI oder einen
Dateisystem-Identifikator enthalten oder rekonstruierbar machen. Der Server und das Control
Center kennen ausschliesslich diese undurchsichtigen Werte.

`fileId` ist **sitzungsgebunden**: die Zuordnung `fileId` → lokale Ressource lebt nur innerhalb
der Remote-Session, die sie ausgegeben hat. Eine `fileId` aus einer beendeten oder abgelaufenen
Session wird mit `NOT_FOUND` beantwortet, nie mit dem Inhalt. Damit kann eine einmal gesehene ID
spaeter nicht erneut eingeloest werden.

Der Server darf `fileId`-Werte nicht dauerhaft speichern und nicht geraete- oder
sitzungsuebergreifend wiederverwenden.

#### 8.3.3 `FileEntry`

```json
{
  "id": "<opaque fileId>",
  "name": "Rechnung.pdf",
  "mimeType": "application/pdf",
  "size": 182734,
  "modifiedAt": "2026-09-13T09:12:00.000Z",
  "kind": "file"
}
```

- `kind` ist `file` oder `directory`.
- `size` ist bei `directory` immer `null`.
- `name` ist der Anzeigename **ohne** Pfadanteil. Enthaelt er `/`, `\` oder `..`, lehnt der
  Empfaenger den Eintrag ab (`INVALID_MESSAGE`): ein Pfadanteil im Namen ist der klassische Weg
  zu einem Traversal auf der Gegenseite.
- `modifiedAt` darf `null` sein, wenn Android keinen Wert liefert.

#### 8.3.4 Nachrichtentypen

| Typ | Richtung | Payload |
| --- | --- | --- |
| `files.shares.request` | Server → Geraet | `{ }` |
| `files.shares.response` | Geraet → Server | `{ shares: Share[] }` |
| `files.list.request` | Server → Geraet | `{ shareId, directoryId?, cursor?, limit? }` |
| `files.list.response` | Geraet → Server | `{ shareId, entries: FileEntry[], nextCursor?: string }` |
| `files.metadata.request` | Server → Geraet | `{ shareId, fileId }` |
| `files.metadata.response` | Geraet → Server | `{ entry: FileEntry }` |
| `files.download.start` | Server → Geraet | `{ transferId, shareId, fileId }` |
| `files.download.chunk` | Geraet → Server | `{ transferId, sequence, data, last }` |
| `files.download.ack` | Server → Geraet | `{ transferId, sequence }` |
| `files.download.complete` | Geraet → Server | `{ transferId, totalBytes, sha256 }` |
| `files.download.cancel` | beide | `{ transferId, reason }` |

Alle `files.*`-Typen verlangen eine gueltige `sessionId` **und** effektive `files.read`.

Korrelation: Anfrage/Antwort werden ueber `messageId` korreliert - die Antwort traegt den
`messageId` der Anfrage in `relatesTo`. Transfers werden zusaetzlich ueber `transferId`
korreliert. `sessionId` ist der Autorisierungsrahmen, nicht der Korrelationsschluessel:
eine Files-Session traegt viele Anfragen.

#### 8.3.5 Auflisten

- `directoryId` fehlt ⇒ Wurzel des Shares. Bei `kind = "file"` enthaelt die Wurzel genau einen
  Eintrag.
- `limit` ist optional und wird auf `FILE_MAX_LIST_ENTRIES` gedeckelt.
- `nextCursor` fehlt ⇒ letzte Seite. Ein Cursor ist opak und sitzungsgebunden.
- Das Geraet listet **nie** oberhalb der Share-Wurzel. Ein `directoryId`, das nicht zu `shareId`
  gehoert, wird mit `NOT_FOUND` beantwortet - nicht mit `FORBIDDEN`, damit die Antwort nicht
  verraet, ob die Ressource existiert.

#### 8.3.6 Download

```text
Server                                  Geraet
  |-- files.download.start ------------->|  prueft Capability, Share, Groesse
  |<-- files.download.chunk (seq 0) -----|
  |-- files.download.ack (seq 0) ------->|
  |<-- files.download.chunk (seq 1) -----|
  |              ...                     |
  |<-- files.download.chunk (last=true) -|
  |<-- files.download.complete ----------|
```

- `data` ist `base64` (mit Padding) von hoechstens `FILE_CHUNK_BYTES` Rohbytes.
- `sequence` beginnt bei `0` und steigt luecklos um `1`. Eine Luecke, eine Wiederholung oder eine
  Sequenz nach `last` ⇒ `INVALID_MESSAGE` und Abbruch des Transfers.
- Backpressure: das Geraet haelt hoechstens `FILE_TRANSFER_WINDOW` unbestaetigte Chunks
  gleichzeitig offen und wartet danach auf `files.download.ack`.
- `sha256` in `files.download.complete` ist `hex` ueber den **gesamten** Klartextinhalt. Der
  Empfaenger prueft ihn und verwirft den Transfer bei Abweichung.
- `totalBytes` muss zur Summe der empfangenen Chunks passen.
- Ueberschreitet die Datei `FILE_MAX_DOWNLOAD_BYTES`, antwortet das Geraet auf
  `files.download.start` mit `UNSUPPORTED` und beginnt keinen Transfer.
- Das Geraet liest **streamend**. Eine vollstaendige Datei wird nie in den Speicher geladen.

#### 8.3.7 Abbruch und Zeitueberschreitung

`files.download.cancel` darf von beiden Seiten gesendet werden und beendet den Transfer sofort.
`reason` ist einer von `client_cancelled`, `session_expired`, `capability_revoked`,
`device_revoked`, `too_large`, `read_error`, `timeout`.

Ein Transfer endet ausserdem bei:

- Ablauf oder Widerruf der Remote-Session
- Entzug von `files.read` server- oder geraeteseitig
- Widerruf des Geraets
- Verlust der Agent-Verbindung
- `FILE_TRANSFER_IDLE_TIMEOUT_MS` ohne Fortschritt

Mehr als `FILE_MAX_CONCURRENT_TRANSFERS` gleichzeitige Transfers pro Geraet ⇒ `RATE_LIMITED`.

#### 8.3.8 Inhalte werden nicht gespeichert

Der Server reicht Chunks durch und schreibt Dateiinhalte **nicht** auf Platte, weder als Cache
noch als Zwischenablage. Ein abgebrochener Transfer hinterlaesst keine Teildatei. Dateiinhalte,
Dateinamen und `fileId`-Werte erscheinen niemals im Log (Abschnitt 12).

### 8.4 `media.photos.read` und `media.videos.read`

Medien nutzen **dieselben** Nachrichtentypen und dieselbe Uebertragungsstrecke wie `files.read`
(Abschnitt 8.3). Unterschiedlich ist nur, woher ein Bereich stammt und welche Capability ihn regiert.

Das ist Absicht: Chunking, Gegendruck, Sequenzpruefung und `sha256` sind die Stellen, an denen ein
Fehler am teuersten ist. Eine zweite, parallele Implementierung davon waere eine zweite
Gelegenheit, sie falsch zu bekommen.

#### 8.4.1 Herkunft

Ein Medienbereich entsteht ausschliesslich ueber Androids **Photo Picker**
(`ACTION_PICK_IMAGES` / `PickVisualMedia`). Der Besitzer waehlt dort einzelne Bilder oder Videos aus.

Feedback fordert dafuer **keine** Laufzeitberechtigung an - weder `READ_MEDIA_IMAGES` noch
`READ_MEDIA_VIDEO` noch `READ_EXTERNAL_STORAGE`. Der Photo Picker ist genau dafuer gebaut: er
gibt Zugriff auf das Ausgewaehlte und auf nichts sonst. Eine App, die stattdessen die
Medienberechtigung anfordert, bekommt Zugriff auf die gesamte Mediathek; das waere das Gegenteil
dessen, was hier gemeint ist.

#### 8.4.2 Trennung der beiden Capabilities

`media.photos.read` und `media.videos.read` sind getrennt und implizieren einander nicht. Der
Picker wird deshalb pro Capability getrennt geoeffnet: eine Bildauswahl erzeugt einen Bereich mit
`capability = "media.photos.read"`, eine Videoauswahl einen mit `capability = "media.videos.read"`.
Ein Bereich mischt niemals beides.

Das Geraet prueft zusaetzlich den MIME-Typ jedes Elements: ein Video in einem Fotobereich wird
nicht ausgeliefert, auch wenn der Picker es geliefert haette.

#### 8.4.3 Sammlung (`kind = "collection"`)

Eine Auswahl von zwoelf Bildern ist **ein** Bereich mit zwoelf Eintraegen, nicht zwoelf Bereiche.
Sonst waere `MAX_SHARES` nach einer einzigen Auswahl erschoepft und die Liste unbenutzbar.

Fuer eine Sammlung gilt:

- `files.list.request` ohne `directoryId` liefert die Elemente der Sammlung.
- Ein `directoryId` innerhalb einer Sammlung gibt es nicht; eine Anfrage mit `directoryId`
  wird mit `NOT_FOUND` beantwortet.
- Alle Eintraege haben `kind = "file"`. Sammlungen enthalten keine Ordner.
- `files.metadata.request` und `files.download.start` verhalten sich unveraendert.

#### 8.4.4 Was nicht uebertragen wird

Medienelemente tragen haeufig Aufnahmeort, Geraetemodell und Zeitstempel in ihren Metadaten.
Das Protokoll uebertraegt davon **nichts**: ein `FileEntry` enthaelt Name, MIME-Typ, Groesse,
Aenderungszeit und sonst nichts. Insbesondere werden keine EXIF-Daten, keine Koordinaten und
keine Vorschaubilder als eigene Felder uebertragen.

Der Dateiinhalt selbst wird unveraendert uebertragen. Enthaelt eine Bilddatei EXIF-Koordinaten,
sind sie im heruntergeladenen Inhalt enthalten - so wie sie es waeren, wenn der Besitzer die
Datei selbst kopiert haette. Das Protokoll entfernt sie nicht und gibt auch nicht vor, es zu tun.

### 8.5 `screen.view`

`screen.view` ist **ausschliesslich betrachtend**. Es gibt in v1 keinen Rueckkanal fuer Eingaben:
kein Tippen, kein Wischen, kein Tastendruck, kein Zwischenablagezugriff. Das waere
`screen.control`, und das ist nicht implementiert (Abschnitt 13). Es existiert dafuer auch kein
reservierter Nachrichtentyp.

`screen.view` uebertraegt **kein Audio**. Weder Mikrofon noch die von Android angebotene
Wiedergabeaufnahme (`AudioPlaybackCaptureConfiguration`) werden verwendet. Der Encoder hat keine
Audiospur, und das Protokoll hat kein Feld dafuer.

#### 8.5.1 Warum der Strom ueber den Server laeuft

Der Bildstrom geht denselben Weg wie alles andere: ueber die bestehende, authentifizierte
WebSocket-Verbindung des Agenten zum Control Server und von dort zum Control Center. Es gibt in v1
**kein WebRTC, kein STUN und kein TURN**.

Das ist eine bewusste Entscheidung und keine Sparmassnahme (ADR-004):

- **Internet-first ohne Sonderfall.** Der Agent ist bereits verbunden, sonst gaebe es nichts zu
  zeigen. Ein Strom ueber dieselbe Verbindung funktioniert ueberall dort, wo die App ueberhaupt
  funktioniert - hinter CGNAT, im Mobilfunk, hinter einer Firewall, die nur 443 durchlaesst. Eine
  Peer-Verbindung braucht dagegen NAT-Traversal, und wenn die scheitert, braucht sie ein Relay.
  Konstitution Punkt 13 nennt die Peer-Verbindung ausdruecklich eine Latenzoptimierung; eine
  Optimierung darf nicht die Voraussetzung dafuer sein, dass ein Feature ueberhaupt geht.
- **WebRTC waere hier nicht Ende-zu-Ende-sicher.** Das ist der Punkt, der leicht falsch erzaehlt
  wird. DTLS-SRTP verschluesselt zwischen den Peers, und ein *fremdes* TURN-Relay sieht dabei nur
  verschluesselte Pakete. Die DTLS-Fingerprints werden aber im SDP ausgetauscht, und der
  Signalisierungsweg waere **unser eigener Server**. Wer die Signalisierung kontrolliert, kann
  Fingerprints tauschen und sitzt in der Mitte. Ende-zu-Ende gegen den eigenen Server waere WebRTC
  nur mit einer Fingerprint-Bindung an den Geraeteschluessel - und mit einem Schluessel auf
  Browserseite, den das Geraet vorher kennt. Beides gibt es nicht. WebRTC wuerde hier also
  Sicherheit gegen einen Dritten kaufen, den es in diesem Aufbau gar nicht gibt, und gegen den
  Server nichts aendern.
- **Dieselbe Vertrauensstufe wie Dateien.** `files.download.chunk` laeuft heute im Klartext durch
  den Server; TLS endet dort. Bildframes tun dasselbe. Der Server ist im Threat Model als *halb
  vertrauenswuerdig* eingestuft, und das gilt unveraendert.
- **Eine Uebertragungsstrecke statt zwei.** Chunking, Gegendruck und Sequenzpruefung sind die
  Stellen, an denen ein Fehler am teuersten ist. Dieselbe Ueberlegung wie bei Medien in 8.4.

Was daraus folgt, steht ohne Beschoenigung in `docs/security/THREAT_MODEL.md` 4.15: **der
Serverbetreiber kann den Bildschirm mitsehen.** Der Server speichert keinen Frame (8.5.10), aber
er sieht sie. Wer das nicht akzeptieren will, betreibt den Server selbst - das ist der
Widerrufspfad, den es hier gibt.

#### 8.5.2 Zwei Einwilligungen, und die zweite gehoert dem System

Ein Bildschirmstrom beginnt **nie** dadurch, dass der Server ihn anfordert. Es braucht zwei
getrennte Zustimmungen auf dem Geraet, in dieser Reihenfolge:

1. **Feedbacks eigene Rueckfrage.** Das Geraet zeigt, wer fragt und was verlangt wird, und wartet
   auf eine ausdrueckliche Bestaetigung. Sie ist nicht vorausgewaehlt und laeuft nach
   `SCREEN_CONSENT_TIMEOUT_MS` ohne Antwort ab (`consent_timeout`). Diese Rueckfrage existiert,
   weil der Systemdialog nur *dass* aufgenommen wird zeigt, nicht *fuer wen*.
2. **Der MediaProjection-Systemdialog von Android.** Er wird ueber
   `MediaProjectionManager.createScreenCaptureIntent()` ausgeloest, gehoert dem System, und die App
   kann ihn weder unterdruecken noch vorbeantworten noch sein Aussehen aendern. Er ist die
   eigentliche Einwilligung.

Schritt 1 ersetzt Schritt 2 nicht und darf ihn auch nicht plausibler machen. Lehnt der Besitzer in
einem der beiden Schritte ab, antwortet das Geraet mit `PERMISSION_REQUIRED` und startet keine
Aufnahme.

Die Zustimmung gilt **pro Sitzung**. Sie wird nicht gespeichert, nicht wiederverwendet und nicht
verlaengert. Ab Android 14 verlangt das System das ohnehin; das Protokoll verlangt es auf jeder
Version.

Waehrend der gesamten Aufnahme laeuft ein Vordergrunddienst mit
`foregroundServiceType="mediaProjection"` und einer laufenden Benachrichtigung, die den Empfaenger
nennt und einen **Stop**-Knopf traegt. Der Stop wirkt sofort und lokal, ohne Rueckfrage beim
Server.

Ehrlich dazu, weil die Gegenmassnahme sonst besser klingt, als sie ist: **unumgehbar ist nicht
unsere Benachrichtigung, sondern die des Systems.** Eine App-Benachrichtigung laesst sich ueber die
Kanaleinstellungen stummschalten, und manche Hersteller-ROMs gehen weiter. Worauf man sich stuetzen
kann, ist Androids eigene Aufnahme-Anzeige (Statusleistensymbol bzw. Datenschutzindikator), die
keine App entfernen kann. Unsere Benachrichtigung fuegt die Information hinzu, *wer* zusieht, und
den lokalen Stop. Siehe THREAT_MODEL 4.16.

#### 8.5.3 Was im Bild landet - und was nicht

MediaProjection nimmt die **gesamte Anzeige** auf: jede App im Vordergrund, eingeblendete
Benachrichtigungen, Tastatureingaben, alles. Das ist der Grund, warum `screen.view` eine andere
Groessenordnung ist als `files.read`, wo der Besitzer einen Bereich auswaehlt.

Zwei Einschraenkungen kommen vom System und gelten unabhaengig von uns:

- Fenster mit `FLAG_SECURE` (Banking, Passwortmanager, DRM-Inhalte) erscheinen schwarz.
- Der Aufnahmeumfang ist die Anzeige; andere virtuelle Displays werden nicht mit aufgenommen.

Eine Einschraenkung kommt von uns: **Feedbacks eigene sensible Oberflaechen sind `FLAG_SECURE`**,
insbesondere die Eingabe der App-Sperre. Sonst waere die Bildschirmuebertragung ein Weg, die
Geheimzahl abzulesen, die sie schuetzen soll.

#### 8.5.4 Nachrichtentypen

| Typ | Richtung | Payload |
| --- | --- | --- |
| `screen.start` | Server → Geraet | `{ streamId, maxWidth, maxHeight, maxFps, maxBitrateKbps }` |
| `screen.consent` | Geraet → Server | `{ streamId, state }` |
| `screen.started` | Geraet → Server | `{ streamId, width, height, codec, fps, config }` |
| `screen.frame` | Geraet → Server | `{ streamId, sequence, chunkIndex, chunkCount, keyFrame, timestampUs, data }` |
| `screen.frame.ack` | Server → Geraet | `{ streamId, sequence }` |
| `screen.keyframe.request` | Server → Geraet | `{ streamId }` |
| `screen.stop` | beide | `{ streamId, reason }` |

Alle `screen.*`-Typen verlangen eine gueltige `sessionId` **und** effektive `screen.view`.
Korrelation laeuft wie in 8.3.4 ueber `messageId`/`relatesTo`, der Strom zusaetzlich ueber
`streamId`.

`state` in `screen.consent` ist `pending`, `granted` oder `declined`. `pending` wird genau einmal
gesendet, sobald die Rueckfrage sichtbar ist - damit das Control Center "wartet auf Zustimmung am
Geraet" anzeigen kann, statt einen haengenden Aufruf zu zeigen.

#### 8.5.5 Start

```text
Server                                  Geraet
  |-- screen.start -------------------->|  prueft Capability und Session
  |<-- screen.consent (pending) --------|  eigene Rueckfrage sichtbar
  |                                     |  Besitzer bestaetigt
  |                                     |  Android-Systemdialog
  |<-- screen.consent (granted) --------|  Vordergrunddienst laeuft
  |<-- screen.started ------------------|  Encoder konfiguriert
  |<-- screen.frame (seq 0, keyFrame) --|
  |-- screen.frame.ack (seq 0) -------->|
  |              ...                    |
```

- `codec` ist ein Codec-String im Stil von `avc1.42E01E`. v1 verlangt H.264; das Control Center
  lehnt einen unbekannten Codec ab, statt zu raten.
- `config` ist die base64-kodierte Encoder-Konfiguration (SPS/PPS in Annex-B). Das Geraet wiederholt
  sie zusaetzlich **vor jedem Keyframe**, damit ein Betrachter, der spaeter einsteigt oder einen
  Frame verloren hat, wieder aufsetzen kann.
- `width`, `height` und `fps` sind das, was der Encoder tatsaechlich liefert, nicht das Gewuenschte.
  Sie sind auf `SCREEN_MAX_DIMENSION`, `SCREEN_MAX_FPS` und `SCREEN_MAX_BITRATE_KBPS` gedeckelt;
  das Geraet skaliert selbst herunter und liefert nie mehr als die Grenzen.
- Der erste Frame nach `screen.started` ist immer ein Keyframe.

#### 8.5.6 Frames

Ein kodierter Frame ist oft groesser als `MAX_FRAME_BYTES` - ein Keyframe fast immer. Er wird
deshalb in `chunkCount` Teile zu hoechstens `SCREEN_CHUNK_BYTES` Rohbytes zerlegt, die
`chunkIndex` von `0` bis `chunkCount - 1` durchnummeriert.

- `sequence` zaehlt **Frames**, beginnt bei `0` und steigt streng monoton. Es steigt auch dann um
  mehr als `1`, wenn das Geraet Frames verworfen hat - eine Luecke ist hier eine Information, kein
  Fehler.
- `timestampUs` ist die Praesentationszeit des Encoders in Mikrosekunden, monoton, ohne Bezug zur
  Uhrzeit des Geraets.
- `data` ist `base64` (mit Padding).
- Ein Frame ueber `SCREEN_MAX_FRAME_BYTES` wird verworfen, statt beliebig viel Speicher fuer die
  Wiederzusammensetzung zu binden. Reicht dafuer schon `chunkCount`, wird er verworfen, bevor ein
  einziges Byte gehalten wird.
- Ein Chunk ueber `SCREEN_CHUNK_BYTES` oder ein `chunkIndex` ausserhalb von
  `0 .. chunkCount - 1` ⇒ `INVALID_MESSAGE` und beendet den Strom. Das ist kein Verlust, sondern
  eine Gegenseite, die sich selbst widerspricht.

#### 8.5.7 Gegendruck und Verlust - anders als bei Dateien

Bei einem Download ist eine Luecke fatal und fuehrt zum Abbruch (8.3.6). Bei einem Live-Bild ist
das falsch: eine Datei muss vollstaendig sein, ein Bild muss **aktuell** sein.

- Das Geraet haelt hoechstens `SCREEN_FRAME_WINDOW` unbestaetigte Frames offen. Ist das Fenster
  voll, **verwirft** es neue Frames, statt sie zu puffern. Ein Puffer wuerde die Verzoegerung
  wachsen lassen, bis das Bild nicht mehr zeigt, was gerade passiert.
- Ein unvollstaendig gebliebener Frame (ein Chunk fehlt, wenn der naechste Frame beginnt) wird
  verworfen.
- Der Server bestaetigt **jede** Sequenz, mit der er fertig ist - auch eine verworfene. Sonst
  bliebe ihr Platz im Fenster des Geraets fuer immer belegt.
- **Wer einen Frame verwirft, fordert einen Keyframe an.** Ohne den vorherigen Frame ist ein
  Delta-Frame wertlos, und ein Betrachter, der Artefakte zeigt, ist schlimmer als einer, der kurz
  wartet. Der Server sendet dafuer `screen.keyframe.request`; das Geraet fordert intern einen
  Sync-Frame vom Encoder an.
- Ein wiederholter oder rueckwaerts laufender `sequence`-Wert ist dagegen ein Protokollfehler
  (`INVALID_MESSAGE`) und beendet den Strom: das ist kein Verlust, sondern eine kaputte Gegenseite.

Mehr als `SCREEN_MAX_CONCURRENT_STREAMS` gleichzeitige Stroeme pro Geraet ⇒ `RATE_LIMITED`.

#### 8.5.8 Ende

`screen.stop` darf von beiden Seiten gesendet werden und beendet den Strom sofort. `reason` ist
einer von `owner_stopped`, `client_cancelled`, `session_expired`, `capability_revoked`,
`device_revoked`, `consent_declined`, `consent_timeout`, `projection_stopped`, `encoder_error`,
`timeout`, `connection_lost`.

Ein Strom endet ausserdem bei:

- Ablauf oder Widerruf der Remote-Session (`SCREEN_SESSION_TTL_MS`, ohne Verlaengerung)
- Entzug von `screen.view` server- oder geraeteseitig
- Widerruf des Geraets
- Verlust der Agent-Verbindung (8.5.9)
- `SCREEN_STREAM_IDLE_TIMEOUT_MS` ohne Fortschritt
- `MediaProjection.Callback#onStop` - das System hat die Aufnahme beendet

In jedem dieser Faelle stoppt das Geraet **die Aufnahme zuerst** und beendet dann den
Vordergrunddienst. Die Reihenfolge ist wichtig: zuerst aufhoeren zu filmen, dann aufraeumen.

#### 8.5.9 Verbindungsverlust

Faellt die Agent-Verbindung waehrend einer laufenden Aufnahme aus, **stoppt das Geraet die
MediaProjection sofort** und beendet den Vordergrunddienst. Es haelt sie nicht "warm", wartet
keinen Reconnect ab und nimmt nicht weiter auf, um bei Rueckkehr der Verbindung sofort liefern zu
koennen.

Eine laufende Aufnahme ohne empfangsbereite Gegenseite hat keinen Zweck und genau einen Effekt: das
Geraet filmt sich selbst, waehrend niemand mehr zusieht und der Besitzer annimmt, es sei vorbei.

Nach einem Reconnect gibt es deshalb keine Fortsetzung. Ein neuer Strom braucht eine neue Sitzung
und beide Zustimmungen aus 8.5.2 erneut.

#### 8.5.10 Nichts wird gespeichert

Der Server reicht Frames durch und schreibt sie **nicht** auf Platte - kein Cache, keine Aufnahme,
kein Standbild. Ein abgebrochener Strom hinterlaesst nichts. Das Control Center dekodiert in den
Arbeitsspeicher; es gibt keine Aufnahmefunktion und keinen Schnappschuss-Endpunkt.

Bildschirmframes erscheinen niemals im Log (Abschnitt 12). Protokolliert werden ausschliesslich
`streamId`, Start, Ende und `reason`.

## 9. Remote-Sessions

Jede privilegierte Anfrage laeuft in einer Remote-Session:

- `id`, `ownerId`, `deviceId`
- `requestedCapabilities`, `approvedCapabilities`
- `createdAt`, `expiresAt`
- `revokedAt`

TTL nach Capability:

| Capability | TTL | Begruendung |
| --- | --- | --- |
| `system.info` | `REMOTE_SESSION_TTL_MS = 60000` | eine einzelne Abfrage |
| `files.read` | `FILES_SESSION_TTL_MS = 300000` | Blaettern und Download brauchen mehrere Anfragen |
| `screen.view` | `SCREEN_SESSION_TTL_MS = 600000` | ein Blick auf den Bildschirm dauert laenger als eine Abfrage |

Eine `files.read`-Session verlaengert sich **nicht** unbegrenzt durch Aktivitaet: `expiresAt`
steht beim Anlegen fest. Laeuft sie waehrend eines Transfers ab, wird der Transfer beendet.

Fuer `screen.view` gilt dasselbe, und dort ist der feste Ablauf ausdruecklich eine
Sicherheitsmassnahme statt einer Unbequemlichkeit: eine Sitzung, die sich durch Aktivitaet
verlaengert, laeuft genau so lange, wie jemand zusieht - also potenziell unbegrenzt. Ein neuer
Blick braucht eine neue Sitzung und beide Zustimmungen aus 8.5.2 erneut.

Abgelaufene oder widerrufene Sessions werden auf beiden Seiten abgelehnt
(`SESSION_EXPIRED`). Das Geraet fuehrt keine Anfrage ohne gueltige `sessionId` aus.

## 10. Fehlercodes

| Code | Bedeutung | HTTP |
| --- | --- | --- |
| `UNAUTHORIZED` | keine gueltige Anmeldung/Token | 401 |
| `FORBIDDEN` | authentifiziert, aber nicht berechtigt | 403 |
| `SESSION_EXPIRED` | Remote-Session abgelaufen/ungueltig | 409 |
| `DEVICE_REVOKED` | Geraet wurde widerrufen | 403 |
| `CAPABILITY_DENIED` | Capability nicht freigegeben | 403 |
| `PERMISSION_REQUIRED` | Android-Berechtigung fehlt lokal | 403 |
| `UNSUPPORTED` | Version, Typ oder Capability nicht unterstuetzt | 400 |
| `INVALID_MESSAGE` | Schema-, Signatur- oder Ableitungsfehler | 400 |
| `RATE_LIMITED` | Rate Limit erreicht | 429 |
| `PAIRING_EXPIRED` | Pairing-Ticket abgelaufen | 410 |
| `PAIRING_ALREADY_USED` | Pairing bereits verbraucht/entschieden | 409 |
| `NOT_FOUND` | Ressource existiert nicht oder ist nicht sichtbar | 404 |
| `INTERNAL` | unerwarteter Serverfehler | 500 |

Bei fehlgeschlagenem Lookup wird bewusst nicht zwischen "unbekannt" und "abgelaufen"
unterschieden, solange das die Brute-Force-Analyse erleichtern wuerde.

## 11. Limits

| Konstante | Wert |
| --- | --- |
| `PAIRING_TTL_MS` | 300000 |
| `PAIRING_MAX_LOOKUP_ATTEMPTS` | 5 |
| `CLOCK_SKEW_MS` | 120000 |
| `NONCE_RETENTION_MS` | 900000 |
| `REMOTE_SESSION_TTL_MS` | 60000 |
| `HEARTBEAT_INTERVAL_MS` | 30000 |
| `HEARTBEAT_MISS_LIMIT` | 3 |
| `MAX_FRAME_BYTES` | 65536 |
| `MAX_JSON_BODY_BYTES` | 16384 |
| `DEVICE_NAME_MAX` | 64 |
| `PUBLIC_KEY_MAX_BASE64` | 512 |
| `FILES_SESSION_TTL_MS` | 300000 |
| `FILE_CHUNK_BYTES` | 32768 |
| `FILE_TRANSFER_WINDOW` | 4 |
| `FILE_MAX_CONCURRENT_TRANSFERS` | 2 |
| `FILE_TRANSFER_IDLE_TIMEOUT_MS` | 30000 |
| `FILE_MAX_DOWNLOAD_BYTES` | 268435456 |
| `FILE_MAX_LIST_ENTRIES` | 200 |
| `FILE_NAME_MAX` | 255 |
| `SCREEN_SESSION_TTL_MS` | 600000 |
| `SCREEN_CONSENT_TIMEOUT_MS` | 60000 |
| `SCREEN_CHUNK_BYTES` | 32768 |
| `SCREEN_FRAME_WINDOW` | 3 |
| `SCREEN_MAX_FRAME_BYTES` | 1048576 |
| `SCREEN_MAX_CONCURRENT_STREAMS` | 1 |
| `SCREEN_STREAM_IDLE_TIMEOUT_MS` | 15000 |
| `SCREEN_MAX_DIMENSION` | 1280 |
| `SCREEN_MAX_FPS` | 15 |
| `SCREEN_MAX_BITRATE_KBPS` | 2500 |

`FILE_CHUNK_BYTES` ist so gewaehlt, dass ein Chunk base64-kodiert samt Envelope sicher unter
`MAX_FRAME_BYTES` bleibt: 32768 Rohbytes ergeben 43692 base64-Zeichen, der Rest ist Envelope.

`FILE_MAX_DOWNLOAD_BYTES` ist serverseitig konfigurierbar. Das Geraet erzwingt zusaetzlich sein
eigenes Limit; der kleinere Wert gewinnt.

`SCREEN_FRAME_WINDOW` ist bewusst kleiner als `FILE_TRANSFER_WINDOW`: bei einer Datei kostet ein
grosses Fenster Speicher, bei einem Live-Bild kostet es Verzoegerung. Drei unbestaetigte Frames
sind bei 15 fps rund 200 ms - genug, um eine Schwankung zu ueberbruecken, zu wenig, um unbemerkt
einen Rueckstand aufzubauen.

Rate Limits (Token Bucket, pro IP und pro Prinzipal):

| Route | Limit |
| --- | --- |
| `POST /auth/login` | 10 / 15 min |
| `POST /pairing/start` | 10 / 10 min |
| `POST /pairing/lookup` | 10 / 10 min |
| `POST /pairing/{id}/status` | 120 / 10 min |
| `POST /pairing/{id}/claim` | 10 / 10 min |
| `POST /devices/{id}/files/session` | 30 / 10 min |
| `GET /devices/{id}/files/content` | 60 / 10 min |
| `POST /devices/{id}/screen/session` | 20 / 10 min |
| `POST /devices/{id}/screen/keyframe` | 60 / 10 min |
| sonstige `/api/v1` | 600 / 10 min |
| `GET /pair`, `GET /pair/`, `GET /.well-known/assetlinks.json` | 600 / 10 min |

Die letzte Zeile liegt ausserhalb von `/api/v1` und ausserhalb dieses Protokolls (Abschnitt 6.3).
Sie nimmt dasselbe `apiDefault`-Budget, zaehlt es aber in einem eigenen Schluessel-Scope
(`ip-setup`) statt im Scope der API-Routen, damit eine Flut gegen die oeffentliche
Einrichtungsseite nicht das API-Budget derselben IP verbraucht. Die drei Pfade teilen sich dabei
einen Eimer je IP. Einen Prinzipal gibt es auf diesen Routen nicht; gezaehlt wird allein die IP.

## 12. Logging

Erlaubt: `deviceId`, `sessionId`, `requestId`, `pairingId`, Ereignistyp, Fehlercode, Zeitstempel.

Verboten: PIN, Pairing-Ticket, `deviceSecret`, `deviceToken`, Session-Cookie,
`Authorization`-Header, private Schluessel, Dateiinhalte, Dateinamen, `fileId`-Werte,
Share-Anzeigenamen, Bildschirmframes, Passwoerter.

## 13. Noch nicht Teil von v1

`screen.control` und `clipboard.*` sind reserviert, aber nicht implementiert. Remote-Input
(`screen.control`) wird erst nach aktualisiertem Threat Model aktiviert und transportiert niemals
Shell-Kommandos oder beliebige Codeausfuehrung.

`screen.view` ist festgelegt (Abschnitt 8.5), aber noch nicht auf allen drei Seiten umgesetzt und
bis dahin deny-by-default. Es ist ausdruecklich **nur betrachtend**: kein
Eingabekanal, kein Audio, keine Aufnahmefunktion. Ein Eingabekanal ist kein kleiner Zusatz zu
einem Bildstrom, sondern die Grenze zwischen Zusehen und Fernsteuern.

WebRTC, STUN und TURN sind kein Bestandteil von v1. Die Begruendung steht in 8.5.1 und
ADR-004; sie ist nicht "spaeter", sondern "in diesem Aufbau kein Gewinn".

`files.read` ist implementiert, aber bewusst **nur lesend**. Schreibende Dateioperationen
(`delete`, `rename`, `write`, `upload`, `execute`) sind kein Bestandteil von v1 und haben keinen
reservierten Nachrichtentyp: sie brauchen ein eigenes Threat Model und einen eigenen
Freigabepfad.
