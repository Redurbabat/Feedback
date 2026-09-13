# Offene Arbeit

Stand: 2026-09-13, Branch `claude/festive-cori-f6yy5f`.

Diese Datei benennt genau, was **nicht** fertig ist. Sie ist die ehrliche Gegenseite zur
`ROADMAP.md`: dort steht, was abgehakt ist, hier steht, was noch fehlt und warum.

Regeln aus `FEEDBACK_CONSTITUTION.md` Punkt 11 gelten: "implementiert" heisst echter Code,
"getestet" heisst Test wirklich ausgefuehrt, "CI gruen" heisst Workflow wirklich erfolgreich.

## 1. Abgeschlossen und CI-verifiziert

| Bereich | Stand |
| --- | --- |
| App-Lock (Setup, Unlock, Rate Limit, Auto-Lock, Re-Auth, Deaktivieren) | fertig, CI gruen |
| Biometrische Entsperrung (optional, Android 9+) | fertig, CI gruen |
| QR-Code des Pairing-Tickets auf Android rendern | fertig, CI gruen |
| `files.read` im Protokoll definiert (Abschnitt 8.3) | fertig |
| Android `files.read`: Modell, SAF-Grants, Streaming-Reader, Request-Handler, Agent-Anbindung | implementiert, Unit-Tests gruen |

Kein Punkt dieser Tabelle wurde auf echter Hardware getestet. Siehe Abschnitt 5.

## 2. Milestone 3 (Files) - noch offen

Der Android-Unterbau steht, aber die Funktion ist **noch nicht benutzbar**. Es fehlen drei Teile:

### 2.1 Android: Freigabe-Oberflaeche fehlt

`files.read` ist auf dem Geraet vollstaendig implementiert, aber **nicht erreichbar**: es gibt
keinen Bildschirm, auf dem der Besitzer einen Ordner oder eine Datei auswaehlt. Ohne diesen
Picker ist `FileShareStore` leer, jede Auflistung liefert nichts, und die Capability laeuft ins
Leere.

Konkret fehlt:

- `ACTION_OPEN_DOCUMENT_TREE` und `ACTION_OPEN_DOCUMENT` ueber `rememberLauncherForActivityResult`
- eine Karte in der Geraeteverwaltung, die freigegebene Bereiche zeigt und einzeln entziehen laesst
- der lokale `files.read`-Schalter neben dem bestehenden `system.info`-Schalter, inklusive
  Re-Authentisierung beim Freigeben (`SensitiveAction`), analog zu `GRANT_SYSTEM_INFO`
- Anzeige, wenn Android einen Grant hinter dem Ruecken der App entzogen hat

### 2.2 Server: gar nicht begonnen

Auf der Serverseite existiert für `files.read` bisher **nichts**. Benoetigt werden:

- laengerlebige `files.read`-Remote-Sessions (`FILES_SESSION_TTL_MS = 300000`) neben den
  bestehenden 60-Sekunden-Sessions fuer `system.info`
- Korrelation ueber `messageId` statt ueber `sessionId`: eine Files-Session traegt viele Anfragen,
  die bestehende `AgentConnectionRegistry.requestDevice` korreliert aber genau eine
- Streaming-Bruecke von `files.download.chunk` auf eine HTTP-Antwort, ohne Inhalte auf Platte zu
  schreiben, mit Gegendruck (`files.download.ack`) und Pruefung von Sequenz, `totalBytes` und
  `sha256`
- die fuenf REST-Endpunkte aus Protokoll-Abschnitt 6.2
- `FILE_MAX_DOWNLOAD_BYTES` serverseitig konfigurierbar
- Rate Limits fuer `files/session` und `files/content`
- Audit-Ereignisse `file.transfer.started`, `file.transfer.completed`, `file.transfer.cancelled`
- Serverkonstanten in `server/src/constants.ts` an Abschnitt 11 angleichen und `files.read` in
  `IMPLEMENTED_CAPABILITIES_V1` aufnehmen

Solange das fehlt, weicht der Server vom Protokoll ab: `protocol/PROTOCOL.md` fuehrt `files.read`
bereits als implementiert, `server/src/constants.ts` nicht. Das ist eine bewusste, hier
dokumentierte Zwischenstufe, keine stille Abweichung.

### 2.3 Control Web: gar nicht begonnen

- Geraete-Tab "Dateien" mit freigegebenen Bereichen
- Liste mit Name, Typ, Groesse, Datum, Navigation in Unterordner, Paging ueber `nextCursor`
- Download mit Fortschritt und Abbrechen
- ausdruecklich **keine** Aktionen zum Loeschen, Umbenennen oder Ausfuehren

### 2.4 Definition of Done fuer Files

Aus dem Auftrag, noch offen: Benutzer waehlt lokal einen Bereich; Server- **und** lokale Freigabe
erforderlich; keine nicht freigegebenen Bereiche sichtbar; Listing; Download; grosse Datei wird
gestreamt; Cancellation; Limits; Widerruf stoppt Zugriff. Von diesen Punkten ist bisher nur die
Geraeteseite implementiert und unit-getestet.

## 3. Spaetere Milestones - nicht begonnen

| Milestone | Stand |
| --- | --- |
| 4 Media (`media.photos.read`, `media.videos.read`, Photo Picker) | nicht begonnen |
| 5 Screen View (`screen.view`, MediaProjection, MediaCodec, WebRTC) | nicht begonnen |
| 6 Remote Control (`screen.control`, AccessibilityService, Input-Protokoll) | nicht begonnen |
| 7 Hardening (Threat Model, Fuzzing, Dependency Audit, Batterie-Review) | nicht begonnen |
| 8 Windows-Agent | nicht begonnen |

Die Reihenfolge bleibt zwingend: Files → Media → Screen View → Remote Control. Bildschirm- und
Input-Funktionen werden nicht begonnen, bevor Files stabil ist.

## 4. Bekannte technische Schulden

- **QR-Scanner fehlt.** Android rendert den QR-Code, kann aber keinen scannen. Ein Scanner braucht
  die Kamera-Berechtigung und wird erst mit dokumentiertem Bedarf gebaut.
- **`tools/validators/check-protocol-constants.mjs` existiert nicht**, obwohl
  `.claude/commands/protocol-review.md` ihn als Pruefpunkt auffuehrt. Die Limit-Konstanten werden
  derzeit nur von Hand zwischen `protocol/PROTOCOL.md`, `ProtocolConstants.kt` und
  `server/src/constants.ts` abgeglichen. Ein Validator waere wertvoll, gerade jetzt, wo mit
  `files.read` neun neue Konstanten dazugekommen sind.
- **Paging-Reihenfolge ist die des Providers.** `FileListCursor` ist ein Offset in die Zeilenfolge,
  die der Android-Dokumentenanbieter liefert. Die ist in der Praxis stabil, aber nicht garantiert.
  Aendert sich ein Ordner waehrend des Blaetterns, kann ein Eintrag doppelt oder gar nicht
  erscheinen. Eine stabile Sortierung wuerde bedeuten, jeden Ordner vollstaendig zu lesen, bevor
  die erste Seite beantwortet wird.
- **Kein Integrationstest ueber die drei Implementierungen hinweg.** Android, Server und Control
  Web werden je fuer sich getestet; dass die kanonischen Payloads und Feldnamen wirklich
  zusammenpassen, prueft bisher nur ein Mensch.

## 5. Was Hardware braucht

Nichts davon kann in CI verifiziert werden. Der vollstaendige Plan steht in
`docs/testing/ANDROID_DEVICE_MATRIX.md` und ist durchgehend als
**NOT TESTED ON PHYSICAL DEVICE** markiert.

Besonders relevant und ungetestet:

- Android Keystore auf echter Hardware (Geraeteidentitaet, `SecretStore`, App-Lock-Verifier)
- App-Lock: Wartezeit ueber App- und Geraeteneustart, Verhalten bei verstellter Systemzeit
- Auto-Lock im Zusammenspiel mit Recents, Bildschirm aus und Split Screen
- BiometricPrompt auf Geraeten mit und ohne `BIOMETRIC_STRONG`
- Scanbarkeit des QR-Codes auf realen Displays
- Storage Access Framework: Verhalten verschiedener Dokumentenanbieter, entzogene Grants,
  entfernte SD-Karten, sehr grosse Ordner
- Foreground Service unter Doze, nach Entfernen aus den Recents, bei Netzwechsel
- Akkuverbrauch im Leerlauf mit aktiver Hintergrundverbindung
- Store-/Policy-Pruefung des Foreground-Service-Typs `specialUse`

## 6. Sicherheitsgrenzen, die offen bleiben

Diese sind bewusst so und in `docs/security/SECURITY_MODEL.md` ausfuehrlich benannt:

- Eine vorgestellte Systemuhr kann eine App-Lock-Wartezeit verkuerzen. Ein vertrauenswuerdiger
  lokaler Zeitgeber steht nicht zur Verfuegung.
- Der Fehlversuchszaehler liegt im privaten App-Speicher und schuetzt nicht gegen Root.
- Biometrie ist ein Komfort-Gate, kein zweiter kryptografischer Faktor.
- Ein vollstaendig kompromittiertes Geraet kann die App-Schicht in jedem Fall umgehen.

## 7. Naechster konkreter Schritt

Die Android-Freigabe-Oberflaeche aus Abschnitt 2.1. Sie ist der kleinste Schritt, der `files.read`
von "implementiert" zu "auf dem Geraet benutzbar" bringt, und sie ist Voraussetzung dafuer, die
Server- und Control-Web-Seite ueberhaupt gegen echte Daten testen zu koennen.
