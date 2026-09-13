# /release-review

Durchsicht vor einer veroeffentlichten APK bzw. einem Release.

## Umfang

- `.github/workflows/android-build.yml`, `.github/workflows/android-release.yml`
- `android/app/build.gradle.kts` (Signatur, `versionCode`, `versionName`)
- `docs/roadmap/ROADMAP.md`, `README.md`, `CHANGELOG.md`
- `docs/security/THREAT_MODEL.md`

## Harte Regeln

- Kein Signaturmaterial im Repository. Keystore und Passwoerter kommen
  ausschliesslich aus dem GitHub Secret Store.
- **Stabile Signatur ist sicherheitsrelevant, nicht nur Komfort:** wechselt der
  Signaturschluessel, verlangt Android eine Deinstallation. Eine Deinstallation
  loescht den Android-Keystore-Schluessel und damit Geraeteidentitaet und Kopplung.
- Kein Haekchen in der Roadmap fuer etwas, das nur geplant ist.
- Keine Fertigmeldung ohne ausgefuehrten Build und ausgefuehrte Tests.

## Pruefpunkte

1. Sind `versionCode` und `versionName` gegenueber dem letzten Release erhoeht?
2. Ist die Signatur ueber Builds stabil, oder wird der Nutzer zur Deinstallation gezwungen?
3. Sind alle CI-Jobs gruen: Android-Unit-Tests, Lint, APK, Server, Control Web, Validatoren?
4. Nennt der Releasetext ehrlich, was die App kann und was noch nicht?
5. Entspricht die Roadmap dem tatsaechlich implementierten Code?
6. Ist das Threat Model fuer jede neue Capability aktualisiert?
7. Enthaelt das Release-Artefakt keine Geheimnisse und keine Testdaten?

## Ausgabe

Eine klare Freigabeempfehlung mit Begruendung, plus Liste der Blocker.
