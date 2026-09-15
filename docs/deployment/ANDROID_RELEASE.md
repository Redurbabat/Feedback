# Android Release und APK-Signatur

Stand: 2026-09-15

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

## Der feste Schluessel ist Voraussetzung, nicht Empfehlung

Bis hierher war ein fester Signaturschluessel eine dringende Empfehlung. Mit den
Einrichtungslinks (`BETRIEB.md` 3.6) ist er **Voraussetzung**: ohne ihn gibt es weder eine
gueltige `assetlinks.json` noch eine nachpruefbare Herkunft der APK.

- **Keine gueltige `assetlinks.json`.** Die Datei nennt genau einen Fingerabdruck. Wechselt die
  Signatur zwischen Builds, ist diese Angabe mit dem naechsten Build falsch - und niemand bekommt
  davon eine Meldung. Es faellt nichts aus, keine Anfrage schlaegt fehl; der Link wird nur nicht
  mehr verifiziert und oeffnet wieder den Browser.
- **Keine nachpruefbare Herkunft.** Eine APK, die nicht manipuliert, sondern nur mit einer anderen
  `FEEDBACK_SERVER_URL` gebaut wurde, ist von aussen nicht von der echten zu unterscheiden
  (Threat Model 4.10 und 4.20). Das einzige Merkmal, das die beiden trennt, ist die Signatur - und
  sie trennt nur dann etwas, wenn sie ueber Builds hinweg dieselbe bleibt.

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

### Einmalig: Serveradresse hinterlegen

Repository → Settings → Secrets and variables → Actions → **Variables**:

| Variable | Inhalt |
| --- | --- |
| `FEEDBACK_SERVER_URL` | `https://<deine-domain>` - genau die Origin, kein Pfad, kein Port |

Eine Variable und kein Secret: der Hostname steht im Manifest jeder veroeffentlichten APK und in
der `assetlinks.json` des Servers, er ist also ohnehin oeffentlich. Er gehoert trotzdem nicht ins
Repository, weil er zur Installation des Besitzers gehoert und nicht zum Quelltext. Ohne die
Variable baut die App ohne festen Anker - die Adresse wird wie bisher eingetippt, und jeder
Einrichtungslink wird abgelehnt. Die Gegenseite dieser Einstellung ist
`FEEDBACK_ANDROID_CERT_SHA256` in der Server-`.env`; beides zusammen beschreibt `BETRIEB.md` 3.6.

### Beim Schluesselwechsel: Fingerabdruck ersetzen, nie ergaenzen

Ein Schluesselwechsel bedeutet ohnehin Deinstallation und neue Kopplung. Dazu kommt ein zweiter
Schritt: `FEEDBACK_ANDROID_CERT_SHA256` auf dem Server bekommt den **neuen** Fingerabdruck an die
Stelle des alten.

Das Dateiformat von `assetlinks.json` erlaubt eine Liste, und darin liegt die Falle. Ein
angehaengter alter Fingerabdruck laesst einem Schluessel, der verloren, abgeflossen oder schlicht
vergessen ist, eine **verifizierte Beanspruchung dieser Domain**: jede App, die mit ihm signiert
ist, gilt Android dann als die App dieses Servers. Die Konfiguration nimmt deshalb genau einen
Wert. Ersetzen ist der einzige Weg, und das ist Absicht.

### Laufend: Release erzeugen

`.github/workflows/android-release.yml` laeuft bei einem Push auf `main`, der etwas unter
`android/**` oder an der Workflow-Datei selbst aendert, und bei manuellem Start (Actions →
**Android Release APK** → *Run workflow*). Eine geaenderte Repository-Variable loest **keinen**
Lauf aus. Ergebnis ist ein Release mit festem Tag `apk`, aus dem die Datei `Feedback.apk` direkt
auf dem Handy geladen werden kann.

**Was der Releasetext nennt.** Er nennt die verwendete `FEEDBACK_SERVER_URL` - in beiden
Varianten, mit und ohne festen Signaturschluessel. Wer die APK installiert, kann damit nachlesen,
welchem Server sie ohne Rueckfrage vertraut; von aussen ist das einer APK nicht anzusehen (Threat
Model 4.20). Ist die Variable nicht gesetzt, sagt der Text ausdruecklich, dass dieser Build ohne
festen Anker gebaut wurde und die Adresse auf dem Geraet eingetippt wird. Ein Nachweis ist das
nicht: wer eine eigene APK baut, schreibt auch ihren Releasetext. Es macht den Anker **lesbar**,
nicht pruefbar - pruefbar wird er allein ueber die Signatur.

## Architektur und Schutz

- Signaturmaterial liegt ausschliesslich im GitHub Secret Store, nie im Repository
  (`FEEDBACK_CONSTITUTION.md` Punkt 6).
- Der Keystore wird im Runner nur fuer den Build geschrieben und danach in einem
  `if: always()`-Schritt wieder geloescht.
- Es gibt **keinen** Passwort-Ersatz aus oeffentlichen Repository-Daten. Fehlt ein
  Secret, wird nicht heimlich schwaecher signiert: der Build laeuft mit der
  Standard-Debug-Signatur weiter, und der Releasetext benennt die Folge ausdruecklich.
- Der Signatur-Fingerabdruck wird im Log ausgegeben, damit ein unbeabsichtigter
  Schluesselwechsel auffaellt, statt erst beim fehlgeschlagenen Update des Nutzers. Der Schritt
  ruft `keytool -list -v` auf und filtert die Ausgabe mit `grep -Ei "sha-?256"`; er laeuft unter
  `set -euo pipefail` und ohne `|| true`, faellt also aus, wenn keine Fingerabdruckzeile kommt.
  Ein Waechter, der schweigend gruen wird, waere hier die teuerste Variante - genau das war er,
  bis der `grep` beide Schreibweisen erfasste (`keytool` schreibt "SHA-256", gesucht wurde
  "SHA256") und das `|| true` dahinter verschwand. Ohne hinterlegten
  Schluessel wird der Schritt uebersprungen: dann gibt es keinen festen Fingerabdruck, und der
  Releasetext sagt genau das.
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
  Das ist dokumentiert, aber nicht geloest - es braucht die einmalige Einrichtung oben. Seit den
  Einrichtungslinks faellt damit zusaetzlich die Domainverifikation aus: `assetlinks.json` kann
  genau einen Fingerabdruck nennen, und der stimmt dann nur bis zum naechsten Build.
- Es gibt noch keine Versionspflege im Releasetext: `versionCode`/`versionName`
  muessen vor einem Release von Hand erhoeht werden.
- Kein Play-Store-Weg, keine automatische Update-Pruefung in der App.
- Ein physischer Installations- und Updatetest auf einem echten Geraet mit
  hinterlegtem Schluessel steht aus.

## Abnahme

- Noch nicht abgenommen. Der Workflow ist erstellt, aber in diesem Repository
  noch nicht gelaufen: er loest bei einem Push auf `main` unter `android/**` aus und muss sonst
  von Hand gestartet werden.
- Die Signaturlogik ist gegen das Vorbild in `Redurbabat/instagram-monitor`
  geprueft; der dortige Passwort-Fallback aus der oeffentlichen Repository-ID
  wurde bewusst **nicht** uebernommen.
- Offen: erster echter Lauf, Fingerabdruckvergleich ueber zwei Builds,
  Update-Test ueber eine installierte Vorversion.
- Ebenfalls offen: ob Android die Domain ueberhaupt verifiziert. Das braucht eine erreichbare
  Domain, eine mit festem Schluessel signierte APK und ein Geraet - bis dahin ist der
  Einrichtungslink eine Zusage, keine gepruefte Eigenschaft (`BETRIEB.md` 3.6).
