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

## Noch offen

- Agent-WebSocket und echte Heartbeats
- Control-Center-Event-WebSocket
- Remote-Session-Orchestrierung
- Live-`system.info` Request/Response ueber den Agent
- Control-Web-Anbindung
- spaetere WebRTC-Signalisierung

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
