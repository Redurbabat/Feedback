# Android Release und APK-Signatur

Stand: 2026-09-13

## Warum das sicherheitsrelevant ist

Die Geraeteidentitaet von Feedback ist ein EC-P-256-Schluesselpaar im Android
Keystore. Dieser Schluessel gehoert der installierten App und ueberlebt Updates -
aber **keine Deinstallation**.

Android erlaubt ein Update nur, wenn die neue APK mit **demselben Schluessel**
signiert ist wie die installierte. Wechselt die Signatur, bleibt nur
Deinstallieren und neu installieren. Damit ist der Keystore-Schluessel weg:

```text
Signaturwechsel  ->  Deinstallation noetig  ->  Keystore-Schluessel geloescht
                 ->  Geraeteidentitaet weg  ->  Kopplung ungueltig  ->  neues Pairing
```

Eine stabile Signatur ist deshalb kein Komfortthema, sondern Teil des
Sicherheits- und Betriebsmodells.

## Bedienung

### Einmalig: Signaturschluessel erzeugen

Auf einem vertrauenswuerdigen Rechner, **nicht** im Repository:

```bash
keytool -genkeypair \
  -keystore feedback-signing.p12 -storetype PKCS12 \
  -alias feedback \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Feedback, OU=App, O=Redurbabat, C=CH"
```

`keytool` fragt nach Store- und Key-Passwort. Beide sicher aufbewahren:
Ohne sie ist kein Update der installierten App mehr moeglich.

### Einmalig: Secrets hinterlegen

Repository → Settings → Secrets and variables → Actions:

| Secret | Inhalt |
| --- | --- |
| `FEEDBACK_KEYSTORE_B64` | `base64 -w0 feedback-signing.p12` |
| `FEEDBACK_STORE_PASSWORD` | Store-Passwort |
| `FEEDBACK_KEY_PASSWORD` | Key-Passwort |
| `FEEDBACK_KEY_ALIAS` | `feedback` |

Die Keystore-Datei selbst wird **nie** committet. `.gitignore` schliesst
`*.p12`, `*.jks` und `*.keystore` bereits aus.

### Laufend: Release erzeugen

`.github/workflows/android-release.yml` laeuft bei jeder Android-Aenderung auf
`main` und bei manuellem Start. Ergebnis ist ein Release mit festem Tag `apk`,
aus dem die Datei `Feedback.apk` direkt auf dem Handy geladen werden kann.

## Architektur und Schutz

- Signaturmaterial liegt ausschliesslich im GitHub Secret Store, nie im Repository
  (`FEEDBACK_CONSTITUTION.md` Punkt 6).
- Der Keystore wird im Runner nur fuer den Build geschrieben und danach in einem
  `if: always()`-Schritt wieder geloescht.
- Es gibt **keinen** Passwort-Ersatz aus oeffentlichen Repository-Daten. Fehlt ein
  Secret, wird nicht heimlich schwaecher signiert: der Build laeuft mit der
  Standard-Debug-Signatur weiter, und der Releasetext benennt die Folge ausdruecklich.
- Der Signatur-Fingerabdruck wird im Log ausgegeben, damit ein unbeabsichtigter
  Schluesselwechsel auffaellt, statt erst beim fehlgeschlagenen Update des Nutzers.
- Die APK enthaelt keine Geheimnisse. Die Geraeteidentitaet entsteht erst beim
  ersten Start auf dem Geraet und ist pro Installation verschieden.

## Ehrliche Grenzen / offen

- ~~Veroeffentlicht wird die Debug-APK.~~ **Behoben am 13.09.2026.** Veroeffentlicht wird jetzt
  der Release-Build. Der Grund war kein Schoenheitsfehler: eine debuggable APK laesst jeden mit
  ADB-Zugang ueber `adb shell run-as` das private Datenverzeichnis lesen - ohne Root und ohne die
  App-Sperre zu kennen, also `deviceToken` und den versiegelten App-Lock-Zustand. Beide
  Android-Workflows pruefen die gebaute APK jetzt mit `aapt2 dump badging` und brechen ab, wenn
  das Flag doch gesetzt ist.
- Der Release-Build ist **nicht minifiziert** (`isMinifyEnabled = false`). Das ist Absicht,
  solange keine ProGuard-Regeln gegen Reflexion in Compose und OkHttp erprobt sind: eine
  Minifizierung, die etwas wegoptimiert, faellt erst auf dem Geraet auf.
- Ohne hinterlegte Secrets ist die Signatur zwischen Builds **nicht stabil**.
  Das ist dokumentiert, aber nicht geloest - es braucht die einmalige Einrichtung oben.
- Es gibt noch keine Versionspflege im Releasetext: `versionCode`/`versionName`
  muessen vor einem Release von Hand erhoeht werden.
- Kein Play-Store-Weg, keine automatische Update-Pruefung in der App.
- Ein physischer Installations- und Updatetest auf einem echten Geraet mit
  hinterlegtem Schluessel steht aus.

## Abnahme

- Noch nicht abgenommen. Der Workflow ist erstellt, aber in diesem Repository
  noch nicht gelaufen: er loest erst bei einem Push auf `main` aus.
- Die Signaturlogik ist gegen das Vorbild in `Redurbabat/instagram-monitor`
  geprueft; der dortige Passwort-Fallback aus der oeffentlichen Repository-ID
  wurde bewusst **nicht** uebernommen.
- Offen: erster echter Lauf, Fingerabdruckvergleich ueber zwei Builds,
  Update-Test ueber eine installierte Vorversion.
