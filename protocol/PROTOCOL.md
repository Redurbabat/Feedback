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

### 4.3 `feedback-pairing-v1` (lokaler Nachweis, Bestand)

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
  "serverTime": "2026-09-13T11:23:20.000Z"
}
```

Nach `claim` ist die Pairing-Session `consumed`. Geht die Antwort verloren, muss das Geraet
neu koppeln; ein zweiter `claim` wird mit `PAIRING_ALREADY_USED` abgelehnt.

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
| `POST` | `/devices/{id}/revoke` | Geraet widerrufen |
| `GET` | `/devices/{id}/audit` | Audit-Ereignisse des Geraets |
| `GET` | `/events/ws` | Presence-/Pairing-Ereignisse fuer das Control Center |

`approve`/`reject` verlangen denselben Proof wie `lookup` (`ticket` **oder** `displayCode`).
Eine `pairingId` allein ist keine Autorisierung.

Alle zustandsaendernden Anfragen verlangen den Header `X-Feedback-CSRF` mit dem Wert aus
`GET /auth/session` und einen gueltigen `Origin`/`Sec-Fetch-Site`-Kontext.

## 7. WebSocket-Envelope

```json
{
  "version": 1,
  "type": "system.info.request",
  "messageId": "<uuid v4>",
  "sessionId": "<uuid|null>",
  "timestamp": "2026-09-13T11:22:33.000Z",
  "payload": {}
}
```

Regeln:

- `version` ≠ 1 ⇒ `UNSUPPORTED`.
- Unbekannter `type` ⇒ `UNSUPPORTED`.
- Fehlende oder ungueltige Felder ⇒ `INVALID_MESSAGE`.
- `messageId` ist eine UUID v4 und pro Verbindung eindeutig; Wiederholung ⇒ `INVALID_MESSAGE`.
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

In v1 ist ausschliesslich `system.info` **implementiert**. Alle anderen Capabilities sind
deklariert, deny-by-default und liefern `UNSUPPORTED`, solange kein Feature dahintersteht.

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

## 9. Remote-Sessions

Jede privilegierte Anfrage laeuft in einer Remote-Session:

- `id`, `ownerId`, `deviceId`
- `requestedCapabilities`, `approvedCapabilities`
- `createdAt`, `expiresAt` (Standard `REMOTE_SESSION_TTL_MS = 60000` fuer `system.info`)
- `revokedAt`

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

Rate Limits (Token Bucket, pro IP und pro Prinzipal):

| Route | Limit |
| --- | --- |
| `POST /auth/login` | 10 / 15 min |
| `POST /pairing/start` | 10 / 10 min |
| `POST /pairing/lookup` | 10 / 10 min |
| `POST /pairing/{id}/status` | 120 / 10 min |
| `POST /pairing/{id}/claim` | 10 / 10 min |
| sonstige `/api/v1` | 600 / 10 min |

## 12. Logging

Erlaubt: `deviceId`, `sessionId`, `requestId`, `pairingId`, Ereignistyp, Fehlercode, Zeitstempel.

Verboten: PIN, Pairing-Ticket, `deviceSecret`, `deviceToken`, Session-Cookie,
`Authorization`-Header, private Schluessel, Dateiinhalte, Bildschirmframes, Passwoerter.

## 13. Noch nicht Teil von v1

`files.read`, `media.*`, `screen.view`, `screen.control` und `clipboard.*` sind reserviert,
aber nicht implementiert. Remote-Input (`screen.control`) wird erst nach aktualisiertem
Threat Model aktiviert und transportiert niemals Shell-Kommandos oder beliebige Codeausfuehrung.
