# Android-Agent

Stand: 2026-09-13

## Bedienung

Der Agent ist der Teil der App, der mit dem Server spricht. Der Besitzer sieht von ihm nur den
Verbindungszustand auf dem Startbildschirm und - falls die Hintergrundverbindung eingeschaltet ist -
die dauerhafte Benachrichtigung.

Es gibt keine versteckte Betriebsart. Laeuft der Agent, ist das sichtbar.

## Architektur und Schutz

```text
FeedbackController (StateFlow)
   |
   +-- ServerPairingCoordinator ---> ApiClient (OkHttp, HTTPS)
   |
   +-- DeviceAgentClient ----------> WebSocket (WSS)
            |
            +-- SystemInfoProvider
            +-- FilesRequestHandler --> AndroidFileReader --> SAF / Photo Picker
```

- **Zustaende**: `UNPAIRED`, `PAIRING`, `PAIRED_OFFLINE`, `CONNECTING`, `ONLINE`,
  `SESSION_ACTIVE`, `REVOKED`, `ERROR`. Die Uebergangslogik ist frei von Android-Abhaengigkeiten
  und unit-getestet.
- **Compose beobachtet ausschliesslich `StateFlow`.** Keine Krypto und kein Netzwerk in einem
  Composable, keine globalen mutablen Variablen.
- **Jede privilegierte Anfrage wird einzeln geprueft**, nicht einmal pro Sitzung. Die vier
  Faktoren - serverseitig gewaehrt, lokal gewaehrt, OS-Berechtigung vorhanden, Session autorisiert -
  werden bei jeder Nachricht neu ausgewertet. Ein Entzug wirkt sofort und bricht laufende
  Uebertragungen ab.
- **`relatesTo` statt `sessionId`** korreliert Antworten, weil eine Files-Sitzung mehrere Anfragen
  gleichzeitig traegt.
- **Geheimnisse** liegen ausschliesslich im Keystore-versiegelten `SecretStore`. Der `deviceToken`
  erscheint nie im UI-Zustand; das Pairing-Ticket schon, weil es angezeigt werden soll und mit der
  Sitzung stirbt.
- **Die Oberflaeche ist fail-closed**: eine eingerichtete App-Sperre gewinnt immer gegen jeden
  anderen Bildschirm.

## Ehrliche Grenzen / offen

- **Der Agent ist hier nicht baubar.** Google Maven und Maven Central sind in der
  Entwicklungsumgebung gesperrt, es gibt kein Android SDK und kein `kotlinc`. Jede Aussage ueber
  Kompilierbarkeit stammt aus GitHub Actions, nicht von einem lokalen Build.
- **Keine Instrumentierungstests.** Alles, was `android.*` beruehrt - Keystore, SAF, Photo Picker,
  Foreground Service - ist ungetestet ausser durch Lesen.
- Kein QR-Scanner (siehe `PAIRING.md`).
- Keine Token-Rotation.

## Abnahme

Android-CI-Lauf 48 auf `claude/festive-cori-f6yy5f`: Kompilierung, Lint, Unit-Tests und
APK-Bau erfolgreich.

**Nicht abgenommen**: jedes Verhalten auf echter Hardware. Der vollstaendige Plan steht in
`docs/testing/ANDROID_DEVICE_MATRIX.md` und ist durchgehend als **NOT TESTED ON PHYSICAL DEVICE**
markiert.
