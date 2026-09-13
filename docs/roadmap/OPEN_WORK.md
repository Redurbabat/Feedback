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
| Android `files.read`: Freigabe-Oberflaeche (Ordner-/Datei-Picker, Entziehen, Capability-Schalter) | fertig, CI gruen (Lauf 46) |
| `tools/validators/check-protocol-constants.mjs` | fertig, laeuft in CI |

Kein Punkt dieser Tabelle wurde auf echter Hardware getestet. Siehe Abschnitt 5.

## 2. Milestone 3 (Files) - noch offen

Der Android-Unterbau steht, aber die Funktion ist **noch nicht benutzbar**. Es fehlen drei Teile:

### 2.1 Android: Freigabe-Oberflaeche - erledigt

Alle vier Punkte sind umgesetzt und in CI-Lauf 46 verifiziert: beide Picker ueber
`rememberLauncherForActivityResult`, eine Karte mit den freigegebenen Bereichen und einzelnem
Entziehen, der `files.read`-Schalter mit Re-Authentisierung beim Freigeben
(`SensitiveAction.GRANT_FILES_READ`), und die Anzeige entzogener Grants ueber
`FileShareStore.inventory()`.

Zusaetzlich: das Entfernen der lokalen Kopplung hebt jetzt auch alle Dateifreigaben auf. Eine
spaetere erneute Kopplung startet damit ohne Freigaben, statt alte Auswahlen still wiederzubeleben.

Ungetestet auf echter Hardware - siehe Abschnitt 5 (Storage Access Framework).

### 2.2 Server: begonnen, Fundament steht

**Erledigt:**

- ~~Korrelation ueber `messageId` statt ueber `sessionId`.~~ `relatesTo` ist jetzt ein
  dokumentiertes Envelope-Feld (Protokoll Abschnitt 7), beide Seiten setzen und pruefen es, und
  `AgentConnectionRegistry` schluesselt offene Anfragen ueber `messageId`. Die Session bleibt der
  Autorisierungsrahmen: `rejectPendingForSession` laesst bei Widerruf jede darunter wartende
  Anfrage scheitern, statt sie haengen zu lassen.
- ~~Serverkonstanten an Abschnitt 11 angleichen.~~ Alle neun `files.read`-Limits stehen in
  `server/src/constants.ts`, und `tools/validators/check-protocol-constants.mjs` haelt sie dort.

**Noch offen:**

- laengerlebige `files.read`-Remote-Sessions (`FILES_SESSION_TTL_MS = 300000`) neben den
  bestehenden 60-Sekunden-Sessions fuer `system.info`
- Streaming-Bruecke von `files.download.chunk` auf eine HTTP-Antwort, ohne Inhalte auf Platte zu
  schreiben, mit Gegendruck (`files.download.ack`) und Pruefung von Sequenz, `totalBytes` und
  `sha256`
- die fuenf REST-Endpunkte aus Protokoll-Abschnitt 6.2
- `FILE_MAX_DOWNLOAD_BYTES` serverseitig konfigurierbar
- Rate Limits fuer `files/session` und `files/content`
- Audit-Ereignisse `file.transfer.started`, `file.transfer.completed`, `file.transfer.cancelled`
- `files.read` in `IMPLEMENTED_CAPABILITIES_V1` aufnehmen - **zuletzt**, erst wenn die Routen
  wirklich antworten

Solange das fehlt, weicht der Server vom Protokoll ab: `protocol/PROTOCOL.md` fuehrt `files.read`
bereits als implementiert, `server/src/constants.ts` nicht. Das ist eine bewusste Zwischenstufe -
seit dem Validator ist sie nicht mehr nur hier dokumentiert, sondern in `KNOWN_GAPS` deklariert
und faellt auf, sobald jemand sie stillschweigend aufloest.

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
| 5 Screen View (`screen.view`, MediaProjection, MediaCodec, WebRTC **plus STUN/TURN**) | nicht begonnen |
| 6 Remote Control (`screen.control`, AccessibilityService, Input-Protokoll) | nicht begonnen |
| 7 Hardening (Threat Model, Fuzzing, Dependency Audit, Batterie-Review) | nicht begonnen |
| 8 Windows-Agent | nicht begonnen |

Die Reihenfolge bleibt zwingend: Files → Media → Screen View → Remote Control. Bildschirm- und
Input-Funktionen werden nicht begonnen, bevor Files stabil ist.

Fuer Milestone 5 gilt ausdruecklich `FEEDBACK_CONSTITUTION.md` Punkt 13: ein STUN/TURN-Pfad ist
Teil des Milestones, nicht eine spaetere Ausbaustufe. Eine reine Peer-to-Peer-Bildschirmfreigabe,
die nur im selben Netz funktioniert, zaehlt nicht als implementiert.

## 4. Bekannte technische Schulden

- **QR-Scanner fehlt.** Android rendert den QR-Code, kann aber keinen scannen. Ein Scanner braucht
  die Kamera-Berechtigung und wird erst mit dokumentiertem Bedarf gebaut.
- ~~`tools/validators/check-protocol-constants.mjs` existiert nicht.~~ **Erledigt.** Der Validator
  vergleicht die 19 Limits aus Abschnitt 11, die acht Capability-Namen und die 13 Fehlercodes
  zwischen `protocol/PROTOCOL.md`, `ProtocolConstants.kt`, `Capability.kt`, `ProtocolError.kt`,
  `server/src/constants.ts` und `server/src/errors.ts` und laeuft als eigener CI-Job. Er hat beim
  ersten Lauf genau die erwartete Drift gefunden: dem Server fehlten acht der neun
  `files.read`-Limits. Die sind jetzt angeglichen. Bewusste Abweichungen muessen in `KNOWN_GAPS`
  deklariert werden - undeklarierte lassen den Job fehlschlagen.
- **Paging-Reihenfolge ist die des Providers.** `FileListCursor` ist ein Offset in die Zeilenfolge,
  die der Android-Dokumentenanbieter liefert. Die ist in der Praxis stabil, aber nicht garantiert.
  Aendert sich ein Ordner waehrend des Blaetterns, kann ein Eintrag doppelt oder gar nicht
  erscheinen. Eine stabile Sortierung wuerde bedeuten, jeden Ordner vollstaendig zu lesen, bevor
  die erste Seite beantwortet wird.
- **Der lokale Vorab-Test deckt nicht alles ab.** In dieser Umgebung ist kein Android-SDK
  verfuegbar (`dl.google.com` ist per Egress-Policy gesperrt), deshalb laufen die android-freien
  Tests lokal ueber einen eigenen Kotlin-Harness. Dessen Dateiliste wird von Hand gepflegt - ein
  vergessener Test faellt erst in CI auf, wie beim Umstellen von `files.read` auf implementiert
  geschehen. Die Android-CI bleibt die verbindliche Pruefung.
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

Die Serverseite aus Abschnitt 2.2. Das Geraet kann jetzt Bereiche freigeben und Anfragen
beantworten, aber niemand kann fragen: es gibt keine Files-Session, keine Streaming-Bruecke und
keinen der fuenf Endpunkte. Damit ist der Server das einzige Glied, das die Kette noch trennt.

Die Serverkonstanten aus Abschnitt 11 sind bereits angeglichen, `IMPLEMENTED_CAPABILITIES_V1`
bewusst noch nicht - `files.read` gehoert dort erst hinein, wenn die Routen wirklich antworten.
