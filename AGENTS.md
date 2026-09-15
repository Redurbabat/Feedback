# AGENTS.md

## Zweck

Diese Datei beschreibt die Arbeitsregeln fuer Coding-Agenten im Feedback-Repository.

## Vor jeder Aenderung

1. `AI_START_HERE.md` lesen.
2. `FEEDBACK_CONSTITUTION.md` lesen.
3. `CLAUDE.md` lesen.
4. Relevante Architektur- und Sicherheitsdokumente lesen.
5. Bestehende Implementierung vor dem Schreiben neuer Module durchsuchen.

## Arbeitsprinzipien

- Kleine, nachvollziehbare Aenderungen.
- Vorhandene Module erweitern statt Duplikate anzulegen.
- Keine Sicherheitslogik in UI-Komponenten verstecken.
- Keine Netzwerkprotokolle ad hoc erfinden; `protocol/` ist die gemeinsame Quelle.
- Keine Secrets oder personenbezogenen Testdaten einchecken.
- Plattformberechtigungen niemals automatisiert umgehen.
- Alte Android-Versionen nur unterstuetzen, wenn die Sicherheitsgarantien erhalten bleiben.

## Tests

Mindestens pruefen:

- Build des geaenderten Moduls
- Unit-Tests fuer Parser, Capabilities, Pairing und Session Policies
- Fehlerpfade wie abgelaufene Codes, widerrufene Geraete und fehlende Berechtigungen
- UI-Zustaende fuer offline, denied, revoked und unsupported

### Wer eine Protokollkonstante ergaenzt, aendert damit auch Android

`ProtocolError` und `Capability` werden auf der Android-Seite **gezaehlt**
(`CapabilityTest.errorCodesAreCompleteAndDenyUnknownCodes`,
`allEightCapabilitiesAreDeclared`). Das ist Absicht: eine Ergaenzung soll auffallen. Wer also
einen Fehlercode oder eine Capability hinzufuegt, aendert drei Stellen - `PROTOCOL.md`,
`server/src/`, `android/.../protocol/` - und **die Zaehlung im Android-Test**. Der Validator
`tools/validators/check-protocol-constants.mjs` prueft die Listen, nicht die Zaehlung; die faellt
erst im Android-Build auf, also spaeter als noetig.

## Abschlussbericht

Nach einer Umsetzung nennen:

- geaenderte Dateien
- Build-/Testergebnis
- neue Berechtigungen oder Capabilities
- bekannte Einschraenkungen
- offene Sicherheitsfragen
