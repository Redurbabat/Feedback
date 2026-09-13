# /security-review

Sicherheitsdurchsicht gegen `FEEDBACK_CONSTITUTION.md` und `docs/security/`.

## Umfang

- `android/app/src/main/java/com/redurbabat/feedback/security/`
- `android/app/src/main/java/com/redurbabat/feedback/pairing/`
- `android/app/src/main/AndroidManifest.xml`
- `server/src/auth/`, `server/src/crypto/`, `server/src/http/`
- `control-web/src/api/`, `control-web/src/auth/`
- `.github/workflows/`, Lockfiles, `.env.example`

## Harte Regeln

- Private Geraeteschluessel verlassen den Android Keystore nie.
- Keine Secrets, Keystores, Tokens oder Passwoerter im Repository.
- Tickets, `deviceSecret` und `deviceToken` werden serverseitig nur gehasht gespeichert.
- Kopplung erteilt Basisvertrauen, nie Vollzugriff. Capabilities sind deny-by-default.
- Android-Berechtigungsdialoge und Foreground-Service-Regeln werden nie umgangen.
- Keine Erhebung von IMEI, Seriennummer, MAC, Telefonnummer, Standort oder Werbe-ID.

## Pruefpunkte

1. Liegen Geheimnisse im Repo oder in Logs, Fehlermeldungen oder Audit-Eintraegen?
2. Werden Geheimnisse in konstanter Zeit verglichen?
3. Sind Signaturpruefung, Ableitungspruefung, Zeitfenster und Nonce-Replay-Schutz vollstaendig?
4. Greifen Rate Limits und der Versuchszaehler pro Pairing-Session?
5. Ist die Mandantentrennung dicht: liefert Fremdzugriff `NOT_FOUND` statt `FORBIDDEN`?
6. Gibt es einen vollstaendigen Widerrufspfad inklusive Tokenloeschung und Verbindungsabbau?
7. Liegen Auth-Tokens im `localStorage`? (Muss nein sein.)
8. Sind CSRF-Token, `SameSite` und Origin-Pruefung fuer alle schreibenden Requests aktiv?
9. Erzeugt eine neue Capability versteckten Hintergrundbetrieb ohne sichtbare Sitzung?
10. Ist jede neue Android-Berechtigung im Manifest wirklich noetig und dokumentiert?

## Ausgabe

Nur konkrete Befunde mit Datei, Zeile und Belegzitat, plus Schweregrad und
Vertrauensgrad. Read-only, ausser der Nutzer bittet ausdruecklich um Korrekturen.
