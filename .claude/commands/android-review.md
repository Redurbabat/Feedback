# /android-review

Durchsicht der Android-App auf Baubarkeit, Plattformregeln und Zustandsarchitektur.

## Umfang

- `android/app/src/main/java/com/redurbabat/feedback/`
- `android/app/src/test/java/com/redurbabat/feedback/`
- `android/app/build.gradle.kts`, `android/gradle/libs.versions.toml`
- `android/app/src/main/AndroidManifest.xml`
- `.github/workflows/android-build.yml`

## Harte Regeln

- `minSdk 24`: kein `java.time`, kein `java.util.Base64`, kein `java.util.stream`
  im `main`-Sourceset ohne Guard.
- Reine Logik bleibt frei von `android.*`, damit sie in JVM-Unit-Tests laeuft.
- Compose beobachtet ausschliesslich `StateFlow` aus einem `ViewModel`.
  Keine Krypto und kein Netzwerk im Composable, keine globalen mutablen Variablen.
- Foreground Service nur mit sichtbarer Notification und lokalem Stop.
  Kein dauerhafter WakeLock, kein `START_STICKY`-Dauerbetrieb ohne Aufgabe,
  keine Ausnahme von der Akku-Optimierung ohne dokumentierten Grund.
- Ein lokaler Build ist in dieser Umgebung nicht moeglich (Google Maven gesperrt).
  Verifikation laeuft ueber GitHub Actions.

## Pruefpunkte

1. Fehlende oder falsche Importe, nicht existierende Symbole, Typfehler?
2. Nicht erschoepfende `when`-Ausdruecke ueber `enum`/`sealed`?
3. Passt jede `package`-Deklaration zum Dateipfad?
4. Werden nur Bibliotheken benutzt, die im Versionskatalog stehen?
5. Verstoesse gegen `minSdk 24` ohne `Build.VERSION`-Guard oder `@RequiresApi`?
6. Sind alle deklarierten Services und Berechtigungen im Manifest eingetragen und noetig?
7. Ueberlebt die Registrierung einen App-Neustart, und gibt es dafuer einen Test?
8. Deckt die Testsuite auch Fehlerpfade ab: abgelaufen, abgelehnt, widerrufen, offline?

## Ausgabe

Befunde mit Datei und Zeile, nach Schweregrad sortiert. Alles, was den Build
brechen wuerde, zuerst.
