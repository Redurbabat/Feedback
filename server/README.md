# Feedback Server

Der Feedback-Server ist die Control Plane fuer authentisierte Control-Center-Sitzungen und gekoppelte Device Agents.

## Aktuell implementiert

- Node.js 22 + TypeScript + Fastify
- SQLite/Drizzle-Datenmodell und Migration
- Passwort-Authentisierung mit HttpOnly-Session-Cookies und CSRF-Schutz
- kurzlebiges, signiertes Pairing mit Ticket, Device-Secret und sechsstelliger Anzeige
- Public-Key-basierte Geraeteregistrierung
- Device-Token als gehashter Bearer-Token
- `GET /api/v1/agent/me` fuer Agent-Registrierungs- und Capability-Status
- `GET /api/v1/devices` und `GET /api/v1/devices/:id`
- konservativer Online/Offline-Status ueber `lastSeenAt`
- serverseitige Capability-Freigabe fuer aktuell implementierte Capabilities
- Geraetewiderruf inklusive Token-, Remote-Session- und Capability-Invalidierung
- Audit-Metadaten
- Rate Limits und strukturierte, secret-redacted Logs
- oeffentliche Einrichtungsseite `GET /pair` (ohne Sitzung, ohne Aussage ueber den Kopplungszustand)
- `GET /.well-known/assetlinks.json` mit genau einem Signatur-Fingerabdruck, nur wenn
  `FEEDBACK_ANDROID_CERT_SHA256` gesetzt ist

## Welche Adresse `/pair` nennt

Die Einrichtungsseite bittet den Besucher, den Link an einer Adresse zu pruefen ("Passt die
Adresse oben nicht zu Ihrem Server, gehoert der Link nicht zu Ihnen"). Diese Adresse muss deshalb
zwei Bedingungen erfuellen: sie muss **dieser** Server sein, und sie muss eine sein, die die App
ueberhaupt annehmen kann.

`FEEDBACK_ALLOWED_ORIGINS` erfuellt beides nicht. Die Variable nennt die Origin des **Control
Centers**, die laut `docs/deployment/BETRIEB.md` ausdruecklich auf einem anderen Host liegen darf
als die API, und sie erlaubt `http` - mit den Werten aus `.env.example` waere die genannte Adresse
also `http://localhost:5173` gewesen, ein Schema, das die App nie akzeptiert.

Darum entscheidet die Seite so:

1. Ist `FEEDBACK_PUBLIC_ORIGIN` gesetzt, nennt sie diesen Wert. Er wird nach derselben Regel
   geprueft, die die App auf jede Serveradresse anwendet (`ServerEndpoint.parse`): `https`,
   registrierbarer Hostname, kein Pfad, keine Zugangsdaten. Ein Wert, der das nicht erfuellt,
   bricht den Start ab.
2. Sonst, wenn dieser Server das Control Center selbst ausliefert (`FEEDBACK_STATIC_DIR`) und
   genau eine erlaubte Origin als Adresse taugt: diese. In diesem Betrieb laedt der Browser das
   Control Center von genau dieser Origin, sie muss also erlaubt sein - sonst scheiterte die
   eigene Anmeldung an der Origin-Pruefung. Damit ist sie zwangslaeufig dieser Server.
3. Sonst nennt die Seite **keine** Adresse und sagt in einem Satz, warum. Eine geratene Adresse
   waere genau die Angabe, auf die sich der Besucher hier nicht verlassen duerfte.

Der `Host`-Header der Anfrage kommt in keinem Fall vor: er wird von dem gewaehlt, der die Anfrage
schickt.

## Noch offen

- Agent-WebSocket und echte Heartbeats
- Control-Center-Event-WebSocket
- Remote-Session-Orchestrierung
- Live-`system.info` Request/Response ueber den Agent
- Control-Web-Anbindung
- Bildschirmstrom als SSE-Bruecke (kein WebRTC, ADR-004)

## Entwicklung

```bash
npm ci
npm run typecheck
npm test
npm run build
```

Migration:

```bash
npm run migrate
```

Lokalen Benutzer anlegen:

```bash
npm run user:create -- --email <adresse>
```

Echte Secrets gehoeren ausschliesslich in lokale Umgebungsvariablen. `server/.env.example` enthaelt nur Beispielnamen und keine produktiven Werte.

Der Server speichert keine privaten Geraeteschluessel und persistiert standardmaessig keine Datei-, Foto-, Video- oder Bildschirm-Inhalte.
