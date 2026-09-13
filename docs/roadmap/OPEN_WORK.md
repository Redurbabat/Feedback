# Offene Arbeit

Stand: 2026-09-13, Branch `claude/festive-cori-f6yy5f`.

Diese Datei benennt genau, was **nicht** fertig ist. Sie ist die ehrliche Gegenseite zur
`ROADMAP.md`: dort steht, was abgehakt ist, hier steht, was noch fehlt und warum.

Regeln aus `FEEDBACK_CONSTITUTION.md` Punkt 11 gelten: "implementiert" heisst echter Code,
"getestet" heisst Test wirklich ausgefuehrt, "CI gruen" heisst Workflow wirklich erfolgreich.

## 1. Abgeschlossen und CI-verifiziert

| Bereich | Stand |
| --- | --- |
| Dokumentation: Threat Model, drei ADRs, Pairing, Agent, Hintergrunddienst, Geraeteschluessel | vollstaendig |
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

### 2.2 Server: erledigt

Alle Punkte umgesetzt und lokal verifiziert (Typecheck, Lint, 103 Tests, Build):

- `files.read`-Remote-Sessions mit `FILES_SESSION_TTL_MS`, getrennt von den
  60-Sekunden-Sessions fuer `system.info`
- Korrelation ueber `messageId`/`relatesTo` statt ueber `sessionId`
- Streaming-Bruecke `files.download.chunk` auf die HTTP-Antwort ueber `FileTransferHub`:
  nichts auf Platte, Gegendruck per `files.download.ack`, Pruefung von Sequenz, Groesse,
  `totalBytes` und `sha256`
- die fuenf REST-Endpunkte aus Protokoll-Abschnitt 6.2
- `FILE_MAX_DOWNLOAD_BYTES` ueber `FEEDBACK_FILE_MAX_DOWNLOAD_BYTES` konfigurierbar (nur nach unten)
- Rate Limits fuer `files/session` und `files/content`
- Audit-Ereignisse `files.session.open`, `file.transfer.started`, `file.transfer.completed`,
  `file.transfer.cancelled`
- `files.read` steht jetzt in `IMPLEMENTED_CAPABILITIES_V1`; die dafuer deklarierte Abweichung
  im Validator ist entfallen, und `KNOWN_GAPS` ist wieder leer

Die Capability wird **pro Anfrage** neu geprueft, nicht pro Sitzung. Ein Entzug auf einer der
beiden Seiten stoppt eine laufende Uebertragung sofort, statt bis zum Ablauf der Sitzung
weiter Bytes zu liefern. Ebenso beenden Widerruf des Geraets, Verlust der Agent-Verbindung und
`FILE_TRANSFER_IDLE_TIMEOUT_MS` ohne Fortschritt jeden offenen Transfer.

Nicht gegen ein echtes Geraet getestet - nur gegen einen Fake-Agenten. Siehe Abschnitt 5.

### 2.3 Control Web: erledigt

- Bereich "Dateien" auf der Geraeteseite, mit den freigegebenen Bereichen
- Liste mit Name, Typ, Groesse und Datum, Navigation in Unterordner ueber eine Brotkrumenleiste,
  Paging ueber `nextCursor`
- Download mit Fortschrittsanzeige und Abbrechen (`AbortController`)
- keine Aktionen zum Loeschen, Umbenennen oder Ausfuehren - es gibt dafuer nicht einmal einen
  Protokollbefehl, und die Oberflaeche sagt das ausdruecklich
- die Berechtigungskarte bietet jetzt `files.read` an. Vorher liess sich die Capability
  serverseitig gar nicht freigeben, der Tab haette also nie funktionieren koennen.

Ordner werden vor Dateien und danach natuerlich sortiert: das Geraet antwortet in der Reihenfolge
seines Dokumentenanbieters, die weder sortiert noch garantiert stabil ist.

### 2.4 Definition of Done fuer Files

Benutzer waehlt lokal einen Bereich, Server- **und** lokale Freigabe erforderlich, keine nicht
freigegebenen Bereiche sichtbar, Listing, Download, Streaming grosser Dateien, Cancellation,
Limits, Widerruf stoppt den Zugriff: alles davon ist auf Geraet, Server und im Control Center
implementiert und getestet. Offen bleibt der Durchlauf, bei dem ein Mensch die Kette an echter
Hardware tatsaechlich benutzt.

## 3. Spaetere Milestones - nicht begonnen

| Milestone | Stand |
| --- | --- |
| 4 Media (`media.photos.read`, `media.videos.read`, Photo Picker) | implementiert auf allen drei Seiten, nicht auf Hardware getestet |
| 5 Screen View (`screen.view`, MediaProjection, MediaCodec, Strom ueber die Agent-Verbindung) | auf allen drei Seiten implementiert, nicht auf Hardware getestet - siehe Abschnitt 4 |
| 6 Remote Control (`screen.control`, AccessibilityService, Input-Protokoll) | nicht begonnen |
| 7 Hardening (Fuzzing, Dependency Audit, Batterie-Review) | Fuzzing und Audit erledigt, Batterie-Review offen (braucht Hardware) |
| 8 Windows-Agent | nicht begonnen |

Die Reihenfolge bleibt zwingend: Files → Media → Screen View → Remote Control. Bildschirm- und
Input-Funktionen werden nicht begonnen, bevor Files stabil ist.

Fuer Milestone 5 gilt `FEEDBACK_CONSTITUTION.md` Punkt 13 - und die Entscheidung ist gefallen:
es gibt **keine** Peer-Strecke. Der Bildstrom laeuft ueber die ohnehin bestehende
Agent-Verbindung und von dort als SSE ins Control Center (ADR-004). Damit funktioniert er
ueberall dort, wo die App funktioniert, und es gibt kein STUN/TURN zu betreiben. Was das kostet,
steht als Bedrohung 4.15 im Threat Model: der Serverbetreiber kann mitsehen.

### Milestone 5, Stand im Detail

| Teil | Stand |
| --- | --- |
| Protokoll 8.5, Limits, Threat Model 4.15-4.18, ADR-004 | fertig |
| Server: `ScreenStreamHub`, vier Endpunkte, SSE-Bruecke | implementiert, 36 Tests gruen |
| Android: zwei Zustimmungen, Vordergrunddienst, MediaCodec, Fenster und Chunking | implementiert, 40 Tests gruen (die Geraeteteile sind davon nicht abgedeckt) |
| Control Web: Bildschirm-Tab, SSE-Parser, WebCodecs-Dekoder | implementiert, 23 Tests gruen |
| Lauf gegen echte Hardware | **offen** |

Was an Milestone 5 grundsaetzlich nicht testbar war und nur in CI kompiliert wurde:
MediaProjection, MediaCodec, der Vordergrunddienst und die Einwilligungs-Activity. Ob die
Benachrichtigung wirklich nicht wischbar ist, ob der Stop-Knopf die Projektion sofort beendet und
ob der Encoder auf einem bestimmten Chipsatz die gewaehlte Aufloesung annimmt, weiss erst ein
Geraetetest.

### Milestone 7, Stand im Detail

**Fuzzing: erledigt.** `server/test/protocol/fuzz.test.ts` faehrt beide Hubs mit tausenden
zufaelligen Operationsfolgen und prueft nach jedem Schritt Invarianten: nie mehr Stroeme als
erlaubt, ein Betrachter wird genau einmal beendet, ausgelieferte Sequenzen steigen streng, kein
Delta-Frame erreicht einen Dekoder ohne Keyframe, und ein Transfer reicht nie mehr Bytes durch
als erlaubt. `control-web/src/screen.fuzz.test.ts` zerschneidet SSE-Stroeme an zufaelligen
Stellen und verlangt dasselbe Ergebnis.

Die Generatoren sind **absichtlich auf gueltigen Verkehr vorgespannt**, und das ist eine
Erkenntnis fuer sich: die erste Fassung war rein zufaellig, sah gruendlich aus und erreichte fast
nichts - jeder Strom starb am ersten fehlerhaften Frame, danach prueften alle Invarianten einen
leeren Hub. Gefunden hat das eine Abdeckungszusicherung am Ende jedes Laufs ("es wurde ueberhaupt
ein Frame ausgeliefert"), nicht ein Mensch beim Lesen.

Zwei Ergebnisse:

- Der Hub-Fuzzer faengt eine absichtlich eingebaute Regression (Delta-Frames ohne Keyframe
  ausliefern) - geprueft, nicht angenommen.
- Der Parser-Fuzzer hat einen **echten Fehler** gefunden: `atob` akzeptiert nicht-kanonisches
  base64, sodass `5RGlCnn=` und `5RGlCnk=` dieselben Bytes ergeben. Zwei verschiedene Zeichenketten
  auf der Leitung waeren also derselbe Frame gewesen. Der Server wies das auf seiner Seite schon
  ab; der Browser tut es jetzt auch.

**Dependency Audit: erledigt.** `npm audit` fuer Server und Control Web meldet null
Schwachstellen (Stand 2026-09-13). `.github/workflows/dependency-audit.yml` laeuft woechentlich
und bricht ab "high" ab - eine Schwachstelle wird veroeffentlicht, wenn sie veroeffentlicht wird,
nicht wenn hier jemand committet. Fuer Android gibt es kein Aequivalent in dieser Aufstellung;
die Abhaengigkeiten dort sind AndroidX, OkHttp und Compose.

**Batterie-Review: offen.** Nicht ohne Hardware machbar. Was zu messen waere: der Dauerverbrauch
der Hintergrundverbindung im Leerlauf, und der einer laufenden Bildschirmuebertragung - bei
15 fps und 2500 kbit/s ueber Mobilfunk ist das die teuerste Sache, die diese App tut.

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
- **Ein Download liegt vollstaendig im Browserspeicher.** Die Antwort wird gestreamt gelesen,
  damit Fortschritt und Abbruch funktionieren, am Ende aber als Blob zusammengesetzt. Ohne die
  File System Access API kann der Browser nicht auf die Platte streamen. Bei 256 MB - dem
  Protokollmaximum - ist das spuerbar. `FEEDBACK_FILE_MAX_DOWNLOAD_BYTES` ist deshalb auch eine
  Schutzgrenze fuer die Bedienoberflaeche, nicht nur fuer den Server.
- **Kein Integrationstest ueber die drei Implementierungen hinweg** - teilweise geschlossen.
  `protocol/fixtures/screen-v1.json` enthaelt kanonische Frames, die **beide** Seiten lesen:
  `server/test/protocol/fixtures.test.ts` prueft sie gegen genau die zod-Schemata, die der Server
  benutzt, und `ScreenFixtureTest.kt` faehrt den echten Android-Handler damit und vergleicht Feld
  fuer Feld, was er erzeugt. `tools/validators/check-protocol-fixtures.mjs` laesst CI fehlschlagen,
  wenn ein in Abschnitt 7.1 deklarierter `screen.*`-Typ kein Beispiel hat.

  `protocol/fixtures/files-v1.json` deckt `files.*` genauso ab - einschliesslich der
  Medienbereiche, die dieselben Nachrichten benutzen und sich nur in der regierenden Capability
  unterscheiden (Abschnitt 8.4). Die abgelehnten Beispiele sind dabei die wertvolleren: ein Name
  mit Pfadanteil, ein Verzeichnis mit Groesse, ein Digest, der keiner ist, eine Capability, die
  keinen Bereich regiert.

  Offen: `system.info` (die Android-Seite braucht dafuer einen Context und ist nicht ohne
  Weiteres im Unit-Test erreichbar) und die Presence-Nachrichten. Und ein echter
  Ende-zu-Ende-Lauf ueber eine echte WebSocket-Verbindung ersetzt das alles nicht; er fehlt
  weiterhin.

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
- **Fotoauswahl: ueberlebt eine Auswahl den Neustart der App?** Der Code nimmt das nicht an,
  sondern versucht `takePersistableUriPermission`, merkt sich die Antwort und prueft die
  Erreichbarkeit danach anders. Welcher der beiden Faelle real eintritt - und ob er sich je nach
  Android-Version unterscheidet - laesst sich nur auf einem Geraet feststellen. Tritt der
  nicht-persistente Fall ein, sagt die App das dem Besitzer; getestet ist der Text nicht.
- Fotoauswahl mit sehr vielen Elementen (bis `MAX_COLLECTION_ITEMS`), Verhalten bei
  Cloud-Mediatheken und bei Elementen, die waehrend einer Sitzung geloescht werden
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

Ein erster Durchlauf gegen ein echtes Geraet nach `docs/deployment/BETRIEB.md`.

Milestone 3 und 4 sind auf allen drei Seiten implementiert und je fuer sich getestet. Vier
Lesefaehigkeiten stehen: Systeminformationen, Dateien, Fotos, Videos. Jede weitere Schicht wuerde
auf einer Kette aufsetzen, die noch nie als Ganzes gelaufen ist - und Milestone 5 ist ausgerechnet
Bildschirmuebertragung, also die Stelle, an der ein Irrtum am teuersten ist.

`KNOWN_GAPS` im Validator ist wieder leer: die Zwischenstufe zwischen Geraet und Server ist
geschlossen.

Milestone 3 ist damit auf allen drei Seiten implementiert und je fuer sich getestet. Was fehlt,
ist keine weitere Schicht, sondern der Beweis, dass sie zusammen tragen: ein Handy, ein
erreichbarer Server, ein Browser, ein Ordner, eine Datei. Alles davor bleibt eine begruendete
Annahme.

Erst danach Milestone 4 (Media). Die Reihenfolge aus Abschnitt 3 gilt unveraendert.
