# Feedback Device Protocol

Status: Draft v0

## Ziele

Das Protokoll verbindet Control Center, Server und Device Agents, ohne Plattform-spezifische Sicherheitsentscheidungen zu umgehen.

## Grundregeln

- versionierte Nachrichten
- deny-by-default
- unbekannte Nachrichtentypen ablehnen
- unbekannte Capabilities nicht implizit akzeptieren
- jede privilegierte Anfrage an eine gueltige Session binden
- Nachrichtenlimits und Groessenlimits erzwingen
- Replay-Schutz fuer sicherheitskritische Operationen

## Envelope

Konzeptionelles Format:

```json
{
  "version": 1,
  "type": "system.info.request",
  "messageId": "uuid",
  "sessionId": "uuid",
  "timestamp": "ISO-8601",
  "payload": {}
}
```

## Initiale Capabilities

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

Capabilities werden einzeln ausgewertet. Es gibt keine implizite Hierarchie, ausser sie wird spaeter explizit dokumentiert.

## Fehlerklassen

- `UNAUTHORIZED`
- `FORBIDDEN`
- `SESSION_EXPIRED`
- `DEVICE_REVOKED`
- `CAPABILITY_DENIED`
- `PERMISSION_REQUIRED`
- `UNSUPPORTED`
- `INVALID_MESSAGE`
- `RATE_LIMITED`

## Pairing

Pairing-Nachrichten werden getrennt von normalen Remote-Sessions behandelt. Pairing-Tickets sind kurzlebig, einmalig und nach Erfolg oder Widerruf ungueltig.

### Pairing Proof v1

Der Android-Agent kann bereits lokal einen signierten Pairing-Nachweis erzeugen. Der kanonische UTF-8-Payload besteht exakt aus diesen, durch `\n` getrennten Zeilen:

```text
feedback-pairing-v1
<deviceId>
<publicKeyBase64>
<nonceBase64Url>
<issuedAtEpochMillis>
<expiresAtEpochMillis>
<sixDigitCode>
```

Regeln:

- `publicKeyBase64` ist der X.509-kodierte EC-Public-Key.
- Signaturalgorithmus: ECDSA P-256 mit SHA-256 (`SHA256withECDSA`).
- Payload und Signatur werden fuer den Transport Base64URL ohne Padding kodiert.
- Nonce: 18 zufaellige Bytes.
- lokale Ticket-Laufzeit: maximal 5 Minuten.
- Zahlencode: sechs Stellen inklusive fuehrender Nullen.
- `deviceId` wird aus SHA-256 des Public Keys abgeleitet (`fb-` plus die ersten 24 Hex-Zeichen).

Der Server muss spaeter vor einer Registrierung mindestens Signatur, Ablaufzeit, Nonce-Wiederverwendung und die Ableitung der Device-ID pruefen. Der sechsstellige Code braucht strikte Rate Limits und darf nur einmal verwendet werden. Bis die Serverregistrierung implementiert ist, ist der lokal erzeugte Code nur ein vorbereiteter Pairing-Nachweis und noch kein online nutzbares Ticket.

## Remote Input

`screen.control` wird erst in einer spaeteren Protokollversion aktiviert. Input-Events benoetigen eine aktive, lokal sichtbare Control-Session und duerfen keine Shell-Kommandos oder beliebige Codeausfuehrung transportieren.
