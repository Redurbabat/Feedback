# Feedback Android

Native Android-App fuer Feedback.

## Technik

- Kotlin 2.3.21
- Jetpack Compose
- Material 3
- Android Gradle Plugin 9.4
- compileSdk/targetSdk 36
- minSdk 24 (Android 7.0)
- Java 17

## Aktueller Stand

Meilenstein A (Fundament und Kryptografie) ist umgesetzt:

- Geraeteidentitaet: EC secp256r1 im Android Keystore, `SHA256withECDSA`,
  Ableitung von `deviceId` und `fingerprint` exakt nach `protocol/PROTOCOL.md` Abschnitt 3.
- Eigene Base64-/Base64Url-Implementierung (RFC 4648), Hex, SHA-256 und ein
  laufzeitkonstanter Vergleich - ohne `android.util.Base64`, damit dieselbe Logik in
  JVM-Unit-Tests laeuft.
- Verschluesselte lokale Ablage (`SecretStore`, AES-256-GCM im Android Keystore) fuer die
  spaetere Serverregistrierung.
- Protokollschicht: Limits, Fehlercodes, Capability-Liste (deny-by-default),
  strikter WebSocket-Envelope-Parser und ISO-8601-Zeitstempel fuer API 24.
- Kanonische Pairing-Payloads aus `protocol/PROTOCOL.md` Abschnitt 4.

Noch nicht implementiert: Serverregistrierung, Hintergrund-Agent, Dateien,
Bildschirmfreigabe und Remote-Control. Alle zugehoerigen Capabilities sind deklariert,
aber deny-by-default und liefern `UNSUPPORTED`.

Das Manifest enthaelt weiterhin nur `INTERNET`. Neue Berechtigungen kommen erst mit dem
zugehoerigen, dokumentierten Feature.

## Modulgrenzen

| Paket | Inhalt |
| --- | --- |
| `security` | Keystore-Schluessel, Geraeteidentitaet, Krypto-Hilfsfunktionen, `SecretStore` |
| `protocol` | Konstanten, Fehlercodes, Capabilities, Envelope, ISO-8601 |
| `pairing` | kanonische Payloads, lokaler Pairing-Nachweis, Clock-Abstraktion |
| `ui` | Compose-Oberflaeche |

Reine Logik (Kodierungen, Ableitungen, kanonische Payloads, Envelope-Parsing,
Capability-Berechnung) ist frei von `android.*` und deshalb in JVM-Unit-Tests pruefbar.

## Lokaler Build

Voraussetzungen:

- JDK 17
- Android SDK Platform 36
- Gradle 9.6+

Aus `android/`:

```bash
gradle :app:testDebugUnitTest
gradle :app:lintDebug
gradle :app:assembleDebug
```

Der CI-Workflow fuehrt genau diese drei Schritte aus.

## Signatur und stabile Updates

Wechselt der Signaturschluessel zwischen zwei Builds, verlangt Android vor dem Update eine
Deinstallation. Eine Deinstallation loescht den Android-Keystore-Schluessel dieser App und
damit Geraeteidentitaet und Kopplung. Der Build liest deshalb einen festen Schluessel aus
Gradle-Properties oder Umgebungsvariablen:

| Variable | Bedeutung |
| --- | --- |
| `FEEDBACK_KEYSTORE_FILE` | Pfad zur Keystore-Datei |
| `FEEDBACK_STORE_PASSWORD` | Passwort des Keystores |
| `FEEDBACK_KEY_PASSWORD` | Passwort des Schluessels |
| `FEEDBACK_KEY_ALIAS` | Alias des Schluessels |

Regeln:

- Kein Signaturmaterial und kein Passwort im Repository (FEEDBACK_CONSTITUTION.md Punkt 6).
- Es gibt keinen Passwort-Ersatz aus oeffentlichen Repository-Daten.
- Fehlt eine der Angaben oder die Datei, wird gar keine feste Signaturkonfiguration erzeugt.
  Der Build laeuft dann mit der Standard-Debug-Signatur durch; das ist der CI-Normalfall.
- Die feste Konfiguration gilt fuer `debug` und `release`, weil das veroeffentlichte
  Artefakt derzeit die Debug-APK ist.

## Sicherheitsprinzip

Der private Geraeteschluessel wird im Android Keystore erzeugt, verlaesst ihn nicht und wird
weder exportiert noch protokolliert. Logs enthalten niemals Ticket, `deviceSecret`,
`deviceToken`, Cookies, `Authorization`-Header oder private Schluessel.
