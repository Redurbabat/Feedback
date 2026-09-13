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

## Remote Input

`screen.control` wird erst in einer spaeteren Protokollversion aktiviert. Input-Events benoetigen eine aktive, lokal sichtbare Control-Session und duerfen keine Shell-Kommandos oder beliebige Codeausfuehrung transportieren.
