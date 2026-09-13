# ADR-003: Geraeteidentitaet

Status: angenommen · Datum: 2026-09-13

## Kontext

Ein Geraet muss ueber Neustarts, Netzwechsel und erneutes Pairing hinweg dasselbe Geraet bleiben,
und der Server darf sich dabei auf nichts verlassen, was das Geraet ueber sich behauptet.

## Entscheidung

**Ein EC-P-256-Schluesselpaar pro Installation, erzeugt im Android Keystore, nicht exportierbar.
Die Identitaet ist der oeffentliche Schluessel; alles andere wird daraus abgeleitet.**

```text
fingerprintHex = hex(sha256(spkiDer))
deviceId       = "fb-" + fingerprintHex[0..23]
```

## Begruendung

- **P-256 statt Ed25519**, weil der Android Keystore es auf allen Zielversionen ab API 23
  hardwaregestuetzt anbietet. Ed25519 waere die schoenere Kurve, ist aber nicht verlaesslich
  verfuegbar - und ein Softwarefallback waere genau das stille Downgrade, das die Konstitution
  Punkt 12 verbietet.
- **Abgeleitet statt vergeben**: eine serverseitig vergebene ID waere ein zweites Register, das
  auseinanderlaufen kann. So kann jede Seite die ID aus dem Schluessel nachrechnen, und der Server
  tut das bei jeder Registrierung.
- **Nicht exportierbar**: der private Schluessel verlaesst den Keystore nie, auch nicht in ein
  Backup. Der Preis steht unten.
- **Kein Hardwarekennzeichen**: keine IMEI, keine Seriennummer, keine MAC, keine Werbe-ID. Die
  Identitaet ist an die Installation gebunden, nicht an das Geraet - das ist enger und ehrlicher.

## Alternativen

- **Serverseitig vergebene ID plus Schluessel**: mehr Freiheit bei der Formatwahl, aber zwei
  Wahrheiten ueber dieselbe Sache.
- **Schluessel im App-Speicher statt im Keystore**: waere sicherbar und wiederherstellbar, aber
  auslesbar. Fuer eine Geraeteidentitaet der falsche Tausch.
- **Ableitung aus einem Hardwarekennzeichen**: stabil ueber Neuinstallation hinweg, und genau
  deshalb abgelehnt - es waere ein dauerhafter Tracker.

## Konsequenzen

- **Eine Deinstallation loescht die Identitaet.** Es gibt kein Backup und keine Wiederherstellung;
  das ist die Kehrseite von "nicht exportierbar". Deshalb ist eine stabile APK-Signatur
  sicherheitsrelevant und nicht nur bequem: ein Signaturwechsel erzwingt eine Deinstallation und
  kostet damit Identitaet und Kopplung (`ANDROID_RELEASE.md`).
- Zwei Installationen derselben App auf demselben Telefon waeren zwei Geraete. Das ist korrekt.
- **Es gibt keine Schluesselrotation.** Das Protokoll sieht sie als eigene Operation vor, aber sie
  ist nicht implementiert. Ein kompromittierter Schluessel bedeutet heute: widerrufen und neu
  koppeln.
- Ein Geraet mit schwachem oder fehlendem Hardware-Keystore bekommt dieselbe API mit schwaecheren
  Garantien. Die App kann das nicht verlaesslich unterscheiden und behauptet deshalb nichts
  darueber.
