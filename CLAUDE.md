# CLAUDE.md

## Projekt

Feedback ist eine native Device-Management-App fuer eigene bzw. autorisierte Geraete. Android ist die erste Zielplattform; Control Center und Server werden getrennt entwickelt.

## Pflichtlektüre

Vor Umsetzung: `AI_START_HERE.md`, `FEEDBACK_CONSTITUTION.md`, `AGENTS.md`, `docs/security/SECURITY_MODEL.md`, `docs/architecture/ARCHITECTURE.md`, `docs/roadmap/ROADMAP.md`.

## Harte Regeln

- Keine versteckte Fernsteuerung.
- Keine Umgehung von Android-Berechtigungen oder Foreground-Service-Regeln.
- Keine Kamera-/Mikrofonaktivierung ohne die vom OS verlangte sichtbare Zustimmung.
- Keine Secrets im Code.
- Keine privaten Schluessel ausserhalb des sicheren nativen Speichers.
- Keine neue Capability ohne Dokumentation und Widerrufspfad.
- Keine Remote-Aktion, wenn Device-Trust oder Session-Authorisierung unklar ist.

## Android

- Kotlin
- Jetpack Compose
- native Android APIs
- Android Keystore fuer Geraeteidentitaet
- Foreground Service nur wenn erforderlich
- WorkManager/Push fuer passende Hintergrundarbeit statt Dauer-Polling

## Architektur

Bevorzugte Modulgrenzen:

- `ui`
- `agent`
- `pairing`
- `security`
- `network`
- `device`
- `files`
- `media`
- `screen`
- `permissions`

## Qualitaetsgate

Vor Abschluss einer Aufgabe:

1. Build ausfuehren.
2. Relevante Tests ausfuehren.
3. Neue Berechtigungen auf Notwendigkeit pruefen.
4. Fehler- und Widerrufspfad pruefen.
5. Dokumentation aktualisieren, wenn Protokoll oder Sicherheitsmodell betroffen ist.
