# ADR-001: Stack des Control Servers

Status: angenommen · Datum: 2026-09-13

## Kontext

Der Control Server vermittelt Pairing, Presence, Capabilities und Inhalte zwischen Android-Agent
und Browser. Er braucht HTTP und WebSocket in einem Prozess, strikte Eingabevalidierung,
Kryptografie (ECDSA-Verifikation, scrypt) und eine Ablage fuer Benutzer, Geraete, Sitzungen und
Audit-Eintraege.

## Entscheidung

**Node.js 22, TypeScript (strict), Fastify 5, zod, Drizzle ORM.**
Als Ablage **SQLite** ueber eine Repository-Schicht, mit **PostgreSQL als Produktionsziel**.

## Begruendung

- **Node** hat HTTP und WebSocket im selben Prozess und `node:crypto` deckt alles ab, was hier
  gebraucht wird: ECDSA-Verifikation, scrypt, `randomBytes`, `timingSafeEqual`. Keine externe
  Krypto-Bibliothek - das ist eine Abhaengigkeit weniger an der empfindlichsten Stelle.
- **TypeScript strict**, weil der Server drei Implementierungen gegeneinander haelt. Der
  Typechecker hat beim Umbau auf `messageId`-Korrelation jeden veralteten Aufruf gefunden, statt
  ihn in einen Laufzeitfehler laufen zu lassen.
- **zod** an jeder Eingabe, weil das Protokoll deny-by-default verlangt. Schemata sind `.strict()`:
  ein unbekanntes Feld ist ein Fehler, keine Kuriositaet.
- **Fastify** wegen Body-Limits, Hooks und `@fastify/websocket` im selben Routing.
- **Drizzle** statt eines schwereren ORM, weil das Datenmodell klein ist und SQL lesbar bleiben soll.

## Alternativen

- **Go oder Rust**: besser fuer Durchsatz und Speicher, aber das Control Center ist ohnehin
  TypeScript. Eine Sprache fuer beide Seiten hielt die geteilten Begriffe - Capability-Namen,
  Fehlercodes - naeher beieinander.
- **Direkt PostgreSQL**: richtig fuer Produktion, aber es haette den ersten lauffaehigen Stand um
  eine Infrastrukturabhaengigkeit verzoegert.
- **Kein ORM**: waere gegangen. Drizzle ist die kleinere Haelfte der Entscheidung; die
  Repository-Schicht darueber ist die groessere.

## Konsequenzen

- Der Wechsel auf PostgreSQL beruehrt nur `src/db/repositories/`. Die Schnittstelle existiert
  bereits, die Implementierung dahinter ist SQLite.
- **Presence und Rate Limits liegen im Prozessspeicher.** Der Betrieb ist damit Single-Instance.
  Ein zweiter Prozess hinter einem Load Balancer wuerde Geraete nicht sehen und jedes Rate Limit
  verdoppeln. Das steht in `BETRIEB.md` und im Threat Model 4.14.
- Ein Multi-Node-Betrieb braucht eine geteilte Presence-/Limit-Schicht hinter derselben
  Schnittstelle - nicht nur eine andere Datenbank.
