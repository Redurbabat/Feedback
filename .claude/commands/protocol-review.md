# /protocol-review

Prueft die Vertragstreue der drei Implementierungen gegen `protocol/PROTOCOL.md`.

## Umfang

- `protocol/PROTOCOL.md` (die Wahrheit)
- `android/app/src/main/java/com/redurbabat/feedback/protocol/`
- `android/app/src/main/java/com/redurbabat/feedback/pairing/`
- `server/src/crypto/`, `server/src/http/`, `server/src/ws/`
- `control-web/src/api/`
- `tools/validators/check-protocol-constants.mjs`

## Harte Regeln

- `protocol/PROTOCOL.md` ist die Quelle. Weicht Code ab, ist der Code falsch,
  bis eine bewusste Protokollaenderung dokumentiert und versioniert wurde.
- Kanonische Signatur-Payloads muessen zwischen Android und Server **zeichengleich** sein.
  Ein zusaetzliches Leerzeichen oder eine vertauschte Zeile bricht das Pairing stumm.
- Unbekannte Version, unbekannter Nachrichtentyp und unbekannte Capability werden
  abgelehnt, nie stillschweigend akzeptiert.

## Pruefpunkte

1. Stimmen die kanonischen Payloads (Abschnitt 4) Zeile fuer Zeile ueberein?
2. Stimmen Endpunktpfade, HTTP-Methoden und Feldnamen (Abschnitt 6) ueberein?
3. Stimmen Envelope-Felder und Nachrichtentypen (Abschnitt 7) ueberein?
4. Sind alle acht Capability-Namen (Abschnitt 8) in allen drei Implementierungen identisch?
5. Sind alle Fehlercodes (Abschnitt 10) vollstaendig und identisch benannt?
6. Stimmen die Limit-Konstanten (Abschnitt 11) numerisch ueberein?
7. Wird `effective = server AND device AND os AND session` auf dem Geraet erneut geprueft?
8. Laeuft `node tools/validators/check-protocol-constants.mjs` fehlerfrei?

## Ausgabe

Nur belegte Befunde mit Datei und Zeile. Jede Abweichung mit der Seite benennen,
die falsch liegt. Read-only, ausser der Nutzer bittet ausdruecklich um Korrekturen.
