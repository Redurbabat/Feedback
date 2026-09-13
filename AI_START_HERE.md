# AI_START_HERE - Feedback Handoff

Diese Datei ist der Einstieg fuer jede KI, die an Feedback arbeitet.

Lies vor Code-Aenderungen in dieser Reihenfolge:

1. `FEEDBACK_CONSTITUTION.md`
2. `AGENTS.md`
3. `CLAUDE.md`
4. `docs/vision/VISION.md`
5. `docs/security/SECURITY_MODEL.md`
6. `docs/architecture/ARCHITECTURE.md`
7. `docs/roadmap/ROADMAP.md`
8. die fuer den Auftrag relevanten Moduldateien

## Wahrheit und Arbeitsweise

- `main` ist die aktuelle Wahrheit.
- Arbeite additiv und modular.
- Keine bestehenden Funktionen entfernen oder schwach absichern, nur um schneller fertig zu werden.
- Nach jeder groesseren Phase Build und Tests ausfuehren.
- Keine Secrets, Tokens, Passwoerter, privaten Schluessel oder Keystores committen.
- Neue Abhaengigkeiten nur mit klarer Begruendung.

## Sicherheitsgrenzen

Feedback ist ein Consent-first-System fuer eigene bzw. autorisierte Geraete.

- Kein versteckter Remote-Zugriff.
- Keine Tarnung als andere System-App.
- Keine Umgehung von Android-/Windows-Sicherheitsdialogen.
- Keine heimliche Kamera-, Mikrofon- oder Bildschirmaktivierung.
- Aktive Remote-Sitzungen muessen fuer den lokalen Nutzer sichtbar und beendbar sein.
- Kopplung und Capability-Freigabe sind getrennte Schritte.
- Private Geraeteschluessel verlassen das Geraet nie.
- Jede hochprivilegierte Funktion braucht Auditierbarkeit und Widerruf.

## Zielarchitektur

- `android/`: Kotlin + Jetpack Compose, native Android APIs
- `server/`: Auth, Pairing, Presence, Signaling, Session Policies
- `control-web/`: Browser-Control-Center
- `protocol/`: gemeinsame Nachrichten, Capabilities und Versionsregeln
- `docs/`: Architektur-, Sicherheits- und Produktentscheidungen

## Reihenfolge

1. Repo-/Dokumentationsgrundlage
2. Android-Grundprojekt
3. Geraeteidentitaet + Android Keystore
4. Pairing
5. Hintergrund-Presence
6. Control Center
7. Systeminformationen
8. Dateien/Medien mit expliziten Rechten
9. Bildschirmfreigabe
10. Fernsteuerung mit sichtbarer Session und OS-Zustimmung

## Kurzprompt fuer neue Sitzungen

```text
Du arbeitest an Feedback. Lies zuerst AI_START_HERE.md, FEEDBACK_CONSTITUTION.md, AGENTS.md, CLAUDE.md, docs/vision/VISION.md, docs/security/SECURITY_MODEL.md, docs/architecture/ARCHITECTURE.md und docs/roadmap/ROADMAP.md. main ist die Wahrheit. Arbeite modular, teste nach Aenderungen und schwache keine Sicherheitsgrenzen ab.
```
