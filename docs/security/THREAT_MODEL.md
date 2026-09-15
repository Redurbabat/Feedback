# Threat Model

Stand: 2026-09-15. Gilt fuer Protokoll v1 mit den vier implementierten Lesefaehigkeiten
`system.info`, `files.read`, `media.photos.read` und `media.videos.read` sowie fuer
`screen.view`, das in Protokoll-Abschnitt 8.5 festgelegt und derzeit in Umsetzung ist.
Bedrohungen 4.15 bis 4.18 beschreiben `screen.view`; sie stehen hier, bevor der Code steht,
weil Konstitution Punkt 5 genau diese Reihenfolge verlangt.

4.20 und 4.21 kamen mit den Einrichtungslinks dazu. Sie beschreiben keine neue Capability und
keine neue Nachricht, sondern den Weg, auf dem die Serveradresse auf das Geraet kommt - und was
daran haengt, dass diese Adresse stimmt.

Diese Datei beschreibt, wogegen Feedback schuetzt, wogegen ausdruecklich **nicht**, und was
nach allen Massnahmen an Risiko uebrig bleibt. Ein Threat Model, das nur Erfolge auflistet,
ist Werbung.

## 1. Vertrauensgrenzen

| Ebene | Einstufung |
| --- | --- |
| Der Besitzer am entsperrten Geraet | vertrauenswuerdig - er ist die Autoritaet, die Kopplung und Freigaben erteilt |
| Android-Systemdialoge (Dateiauswahl, Fotoauswahl, MediaProjection) | vertrauenswuerdig - sie sind die eigentliche Einwilligungsflaeche, nicht unsere UI |
| Android Keystore | vertrauenswuerdig, soweit die Hardware es hergibt |
| Der Control Server | **halb** vertrauenswuerdig - er vermittelt alles, sieht aber nie einen privaten Schluessel |
| Das Control Center im Browser | halb vertrauenswuerdig - an eine Cookie-Sitzung gebunden, die gestohlen werden kann |
| Das Netzwerk | **nicht** vertrauenswuerdig, immer. Es gibt kein Heimnetz-Privileg (Konstitution Punkt 13) |
| Alles, was ein Geraet ueber sich behauptet | **nicht** vertrauenswuerdig - Name, Modell und OS-Version sind Metadaten, nie Identitaet |

## 2. Schutzobjekte, nach Schadenshoehe

1. **Der private Geraeteschluessel.** Verlaesst den Android Keystore nie. Wer ihn haette, koennte
   sich dauerhaft als das Geraet ausgeben.
2. **Der Bildschirminhalt waehrend eines `screen.view`-Stroms.** Er ist umfassender als jede
   Freigabe: der Besitzer waehlt hier keinen Bereich aus, sondern nur einen Zeitpunkt, und im
   Bild liegt alles - fremde Apps, Benachrichtigungen, Eingaben.
3. **Freigegebene Inhalte** - Dateien, Fotos, Videos.
4. **`deviceToken` und Pairing-Geheimnisse.** Wer sie hat, spricht als das Geraet bzw. kann eine
   offene Kopplung uebernehmen.
5. **Die Control-Center-Sitzung.** Wer sie hat, kann Capabilities erteilen.
6. **Die App-Lock-Geheimzahl** und der lokale Fehlversuchszaehler.
7. **Audit-Metadaten.**

## 3. Nicht-Ziele

Feedback schuetzt ausdruecklich **nicht** gegen:

- einen Angreifer mit Root auf dem Zielgeraet (siehe 4.9),
- einen Angreifer, der das entsperrte Telefon in der Hand haelt und die App-Sperre kennt,
- Verkehrsanalyse: dass eine Verbindung besteht und wie viel uebertragen wird, ist sichtbar,
- einen boesartigen Besitzer, der eigene Geraete ueberwacht - das Produkt setzt voraus, dass die
  Geraete ihm gehoeren oder er ausdruecklich autorisiert ist.

## 4. Bedrohungen

### 4.1 Pairing-Code erraten

**Impact** hoch: eine fremde Kopplung waere ein dauerhaft vertrauenswuerdiges Fremdgeraet.
**Likelihood** niedrig.
**Mitigation** Der sechsstellige Code ist ausdruecklich **kein** Sicherheitsanker, sondern ein
Lookup-Komfort. Der eigentliche Anker ist ein 32-Byte-Ticket. Dazu: fuenf Minuten TTL, ein
Versuchszaehler pro Pairing-Session (`PAIRING_MAX_LOOKUP_ATTEMPTS = 5`), der die Session bei
Ueberschreitung ungueltig macht, Rate Limits pro IP und Prinzipal, und eine Antwort, die nicht
zwischen "unbekannt" und "abgelaufen" unterscheidet.
**Residual** Ein Angreifer mit vielen IPs kann raten. Bei 10^6 Codes, fuenf Versuchen je Session
und fuenf Minuten Fenster ist der Erwartungswert unattraktiv, aber nicht null. Entscheidend ist,
dass ein erratener Code allein nichts oeffnet: die Kopplung muss im Control Center bestaetigt
werden, und dort steht, welches Geraet fragt.

### 4.2 Diebstahl eines Pairing-Tickets

**Impact** hoch. **Likelihood** niedrig.
**Mitigation** Das Ticket lebt fuenf Minuten, ist einmalig verwendbar und wird serverseitig nur
als SHA-256 gespeichert. Es erscheint nie im Log (Protokoll Abschnitt 12). Der QR-Code traegt das
Ticket, aber nie den `deviceSecret` und nie einen Geraete-Token.
**Residual** Wer den Bildschirm des Geraets waehrend des Pairings sieht - Schultersurfen, eine
Bildschirmaufnahme, ein geteilter Screen - kann das Ticket abfotografieren. Deshalb ist die
Bestaetigung im Control Center ein zweiter, bewusster Schritt.

### 4.3 Replay einer Pairing-Nachricht

**Impact** mittel. **Likelihood** niedrig.
**Mitigation** `pairing/start` ist signiert und traegt eine Nonce, die der Server fuer
`NONCE_RETENTION_MS` speichert und ablehnt, wenn sie wiederkehrt. `issuedAt` muss innerhalb
`CLOCK_SKEW_MS` liegen. `claim` ist zusaetzlich signiert und genau einmal ausfuehrbar.
**Residual** Eine Uhr, die weit falsch geht, kann eine gueltige Anfrage unbrauchbar machen. Das
ist der bewusste Preis dafuer, das Fenster eng zu halten.

### 4.4 MITM auf der Control Plane

**Impact** sehr hoch. **Likelihood** niedrig bei korrektem TLS.
**Mitigation** Ausschliesslich HTTPS/WSS. `ServerEndpoint.parse` auf Android erzwingt `https://`
mit einem registrierbaren Hostnamen: mindestens zwei Labels, kein Punkt am Ende, nur
Buchstaben/Ziffern/Bindestrich, und die letzte Marke darf nicht aus Ziffern bestehen - daran
scheitern IPv4-Literale, ohne dass es dafuer eine eigene Regel braucht. IPv6-Literale kommen nicht
durch, weil `[` kein erlaubtes Zeichen ist; ein Port ausserhalb 1..65535 ebenfalls nicht.
Punycode (`xn--...`) bleibt erlaubt - es ist ein echter Name - und wird nie dekodiert, damit ein
Homograph als `xn--...` sichtbar bleibt statt als der Name, den er nachahmt. Das Manifest setzt
`usesCleartextTraffic="false"`.

Diese Zusage stand hier, bevor der Code sie einloeste. Bis zur Haertung passierten
`https://192.168.1.10`, `https://[2001:db8::1]:8443`, `https://example.com.` und
`https://example.com:0` jede Pruefung - `java.net.URI` liefert fuer all das einen Host, und mehr
wurde nicht verlangt. Solange der Besitzer die Adresse abtippen musste, war der Schaden begrenzt;
mit einem Einrichtungslink waere er es nicht mehr.
**Residual** **Kein Certificate Pinning.** Eine im System- oder Nutzerspeicher installierte
CA kann die Verbindung aufbrechen. Auf einem Geraet, auf dem jemand eine CA installieren kann,
ist ohnehin mehr verloren; ein Pinning waere trotzdem eine echte Verbesserung und ist offen.

### 4.5 Gestohlenes Browser-Session-Cookie

**Impact** hoch: der Dieb kann Capabilities erteilen und damit Inhalte lesen.
**Likelihood** mittel - Cookie-Diebstahl ist ein realer, haeufiger Weg.
**Mitigation** HttpOnly, `SameSite=Strict`, `Secure` in Produktion, serverseitige Sitzungstabelle
mit Ablauf, Rotation beim Login, CSRF-Token plus Origin-Pruefung bei jeder schreibenden Anfrage.
Kein Token im `localStorage`.
**Residual** Erheblich. Es gibt **keine zweite Stufe** fuer das Erteilen einer Capability im
Control Center - anders als auf dem Geraet, wo eine Freigabe die App-Sperre erneut verlangt. Ein
uebernommenes Browserfenster kann serverseitig alles freigeben. Was es **nicht** kann: die lokale
Freigabe auf dem Geraet setzen. Die Und-Verknuepfung ist genau dafuer da.

### 4.6 Kompromittierter Server

**Impact** hoch. **Likelihood** niedrig bis mittel.
**Mitigation** Der Server hat nie einen privaten Geraeteschluessel und speichert Tickets, Secrets
und Tokens nur gehasht. Er schreibt keine Dateiinhalte auf Platte, weder als Cache noch als
Zwischenablage. Vor allem: er kann keine Capability erzwingen, die lokal nicht freigegeben ist -
das Geraet wertet alle vier Faktoren selbst erneut aus, bei **jeder** Anfrage.
**Residual** Erheblich und bewusst. Ein kompromittierter Server kann jede Capability anfordern,
die lokal bereits freigegeben ist, und die durchlaufenden Inhalte mitlesen. Er ist ein Relay,
keine Ende-zu-Ende-Strecke. Wer das nicht akzeptieren will, muss lokal weniger freigeben.

### 4.7 Widerrufenes Geraet spricht weiter

**Impact** hoch. **Likelihood** niedrig.
**Mitigation** Widerruf setzt `revokedAt`, loescht alle Tokens, beendet alle Remote-Sessions,
bricht laufende Uebertragungen ab und schliesst die WebSocket-Verbindung. Das Geraet geht in den
Zustand `REVOKED` und loescht seine Registrierung.
**Residual** Zwischen dem Klick und dem Abbruch liegt ein kurzes Fenster. Bereits uebertragene
Bytes sind uebertragen - ein Widerruf holt nichts zurueck, und die Oberflaeche behauptet das
auch nicht.

### 4.8 Gestohlenes oder verlorenes Handy

**Impact** hoch. **Likelihood** mittel.
**Mitigation** Die Geraeteverwaltung liegt hinter einer App-Sperre, deren Verifier
Keystore-versiegelt ist, mit persistent gezaehlten Fehlversuchen und steigender Wartezeit.
Auto-Lock beim Verlassen der App. Sicherheitskritische Aktionen verlangen erneute Eingabe.
Aus der Ferne: Geraet im Control Center widerrufen.
Dazu gehoert eine Eigenschaft der ausgelieferten APK: sie ist **nicht debuggable**. Eine
debuggable App laesst jeden mit ADB-Zugang ueber `adb shell run-as` ihr privates
Datenverzeichnis lesen - ohne Root und ohne die App-Sperre zu kennen. Der Geraeteschluessel bliebe
im Keystore, aber `deviceToken` und der versiegelte App-Lock-Zustand liegen dort, und ein Token
genuegt, um als das Geraet zu sprechen. Bis zum 13.09.2026 wurde die Debug-APK veroeffentlicht;
das ist behoben, und beide Android-Workflows brechen ab, wenn die gebaute APK das Flag doch traegt.

**Residual** Eine vorgestellte Systemuhr kann die Wartezeit verkuerzen; ein vertrauenswuerdiger
lokaler Zeitgeber steht nicht zur Verfuegung. Der Fehlversuchszaehler liegt im privaten
App-Speicher und schuetzt nicht gegen Root. Biometrie ist eine Komfortschicht vor einer bereits
per PIN etablierten Sitzung, kein zweiter kryptografischer Faktor. Und ein entsperrtes Telefon
mit aktiviertem USB-Debugging bleibt ein Sonderfall: dort hilft nur, dass `run-as` auf einer
nicht-debuggable App nichts ausrichtet.

### 4.9 Gerootetes oder kompromittiertes Geraet

**Impact** total. **Likelihood** niedrig.
**Mitigation** Keine wirksame. Der Keystore erschwert den Export des privaten Schluessels, je
nach Hardware bis hin zu "praktisch unmoeglich", aber die App-Schicht darueber ist umgehbar.
**Residual** Vollstaendig. Feedback **behauptet hier ausdruecklich keinen Schutz**. Eine
Root-Erkennung waere ein Signal, keine Grenze, und wird nicht als Sicherheitsmerkmal verkauft.

### 4.10 Manipulierte APK

**Impact** hoch. **Likelihood** niedrig.
**Mitigation** Android laesst ein Update nur mit derselben Signatur zu. Der Signaturschluessel
liegt ausschliesslich im GitHub Secret Store, nie im Repository. Der Fingerabdruck wird im Lauf
protokolliert, damit ein unbeabsichtigter Wechsel auffaellt - `keytool -list -v`, gefiltert mit
`grep -Ei "sha-?256"`, unter `set -euo pipefail` und ohne `|| true`: findet der Schritt keine
Fingerabdruckzeile, faellt er aus, statt still gruen zu werden. Auch diese Zusage stand hier,
bevor der Workflow sie einloeste - der `grep` suchte "SHA256" ohne Bindestrich, `keytool` schreibt
ihn mit, und `|| true` verschluckte das leere Ergebnis. Ein Lauf ohne hinterlegten Schluessel
ueberspringt den Schritt: dort gibt es keinen festen Fingerabdruck, und der Releasetext sagt das.

Dieser Abschnitt behandelt bisher nur den Signaturpfad, also die manipulierte APK. Seit es einen
eingebauten Vertrauensanker gibt, hat er eine zweite Seite: eine APK, die **nicht** manipuliert,
sondern schlicht mit einem anderen `FEEDBACK_SERVER_URL` gebaut wurde, ist von aussen von der
echten nicht zu unterscheiden. Gleicher Name, gleiches Symbol, gleicher Quelltext - nur ihr
eingebauter Default zeigt woanders hin, und genau dieser Default entscheidet, welchen
Einrichtungslink sie annimmt (4.20). Der Releasetext nennt inzwischen die verwendete
`FEEDBACK_SERVER_URL`, in beiden Varianten; das macht den Anker eines Builds **lesbar**, beweist
ihn aber nicht - wer eine eigene APK baut, schreibt auch ihren Releasetext. Unterscheidbar werden
die beiden allein ueber die Signatur, und die unterscheidet nur dann etwas, wenn sie zwischen
Builds gleich bleibt.

**Residual** Wer eine fremd signierte APK **neu** installiert, hat eine andere App - mit einer
anderen Identitaet und ohne Kopplung. Solange kein fester Schluessel hinterlegt ist, wechselt die
Signatur zwischen Builds, was Nutzer an Deinstallieren gewoehnt - und genau diese Gewohnheit ist
der Angriffsweg. Deshalb steht das Einrichten des Schluessels in `ANDROID_RELEASE.md` so weit oben,
und deshalb ist es dort seit den Einrichtungslinks keine Empfehlung mehr, sondern Voraussetzung.

### 4.11 Session Replay auf der Protokollstrecke

**Impact** mittel. **Likelihood** niedrig.
**Mitigation** Jede Nachricht traegt eine UUID v4, die pro Verbindung nur einmal vorkommen darf;
Wiederholung ist `INVALID_MESSAGE`. Zeitstempel muessen im `CLOCK_SKEW_MS`-Fenster liegen.
Antworten werden ueber `relatesTo` korreliert, nicht ueber die Sitzung, sodass eine wiederholte
Antwort keiner offenen Anfrage mehr zugeordnet werden kann. `fileId`-Werte sind sitzungsgebunden
und loesen nach Sitzungsende `NOT_FOUND` aus.
**Residual** Innerhalb einer laufenden, legitimen Verbindung schuetzt das nicht gegen den
Verbindungsinhaber selbst - das ist auch nicht das Ziel.

### 4.12 Capability Escalation

**Impact** hoch. **Likelihood** niedrig.
**Mitigation** Keine implizite Hierarchie: `screen.control` impliziert nicht `files.read`,
`files.read` nicht `media.photos.read`, und Fotos implizieren keine Videos. Jede Freigabe wird
einzeln ausgewertet, und die effektive Berechtigung ist die Und-Verknuepfung aus vier Faktoren,
die das Geraet bei jeder einzelnen Anfrage neu prueft. Ein Bereich gehoert genau einer Capability;
eine bekannte `shareId` ohne die passende Capability liefert `NOT_FOUND`, nicht `FORBIDDEN`.
Nicht implementierte Capabilities antworten `UNSUPPORTED`, auch wenn alle vier Faktoren erteilt
sind. `tools/validators/check-protocol-constants.mjs` haelt die Listen auf allen Seiten gleich.
**Residual** Der Weg ueber 4.5 bleibt: wer die Browsersitzung hat, kann serverseitig freigeben.

### 4.13 WebSocket-Hijacking

**Impact** hoch. **Likelihood** niedrig.
**Mitigation** Die Agent-Verbindung authentisiert sich im Handshake mit `Bearer <deviceToken>`
ueber WSS; der Token wird nur gehasht gespeichert und in konstanter Zeit verglichen. Die
Control-Center-Verbindung haengt an der Cookie-Sitzung mit Origin-Pruefung. Presence-Nachrichten
duerfen keine `sessionId` tragen; privilegierte Nachrichten ohne gueltige Session werden abgelehnt.
**Residual** Ein gestohlener `deviceToken` ist eine vollstaendige Uebernahme der Geraeterolle bis
zum Widerruf. Es gibt **keine Token-Rotation** - das ist offen.

### 4.14 Umgehung der Rate Limits

**Impact** mittel. **Likelihood** mittel.
**Mitigation** Token Bucket pro IP **und** pro Prinzipal. `FEEDBACK_TRUST_PROXY` ist
standardmaessig aus, sodass ein gefaelschter `X-Forwarded-For` nichts bewirkt, solange niemand
den Schalter ohne passenden Proxy umlegt.
**Residual** Die Limits liegen im Arbeitsspeicher eines einzelnen Prozesses. Mehrere Instanzen
hinter einem Load Balancer zaehlen getrennt und vervielfachen damit jedes Limit. Der Betrieb ist
deshalb ausdruecklich Single-Instance (`BETRIEB.md` Abschnitt 4). Wer `FEEDBACK_TRUST_PROXY=true`
ohne setzenden Proxy aktiviert, haendigt die Umgehung aus.

### 4.15 Der Serverbetreiber sieht den Bildschirm mit

**Impact** hoch. **Likelihood** niedrig bei eigenem Betrieb, hoch bei fremdem.
**Mitigation** Es gibt keine. Das ist die ehrliche Antwort auf die Frage aus der frueheren
Fassung dieses Dokuments, ob die Strecke Ende-zu-Ende verschluesselt ist: **sie ist es nicht.**
TLS endet am Control Server, und `screen.frame` traegt dort denselben Klartext wie
`files.download.chunk` heute schon.

Was der Server dagegen **nicht** tut: Frames auf Platte schreiben, zwischenspeichern,
Standbilder ablegen oder Bildinhalte protokollieren (Protokoll 8.5.10 und 12). Ein abgebrochener
Strom hinterlaesst nichts. Das ist eine Zusage ueber das Verhalten des Codes, keine kryptografische
Garantie - wer den Server kontrolliert, kann den Code aendern (4.6).

WebRTC wuerde hier weniger helfen, als es klingt, und das ist der Grund, warum es in v1 nicht
eingebaut ist (Protokoll 8.5.1, ADR-004): DTLS-SRTP schuetzt gegen ein *fremdes* TURN-Relay, aber
die Fingerprints werden ueber die Signalisierung ausgetauscht - und die waere unser eigener Server.
Wer die Signalisierung stellt, kann Fingerprints tauschen. WebRTC waere gegen den Serverbetreiber
also genau so wirkungslos und haette zusaetzlich NAT-Traversal, ein Relay und eine zweite
Uebertragungsstrecke mitgebracht.

**Residual** Wer den Server betreibt, kann zusehen. Der Widerrufspfad ist organisatorisch, nicht
technisch: **den Server selbst betreiben** (`docs/deployment/BETRIEB.md`). Das allein genuegt
nicht, wenn der Weg dorthin durch einen Tunnel fuehrt, der TLS selbst beendet - siehe 4.19.
Echte Ende-zu-Ende-Verschluesselung braucht einen Schluessel auf Browserseite, den das Geraet aus
dem Pairing kennt, und eine Bindung des Medienschluessels an den Geraeteschluessel im Keystore.
Beides existiert nicht und steht als Punkt 6 in Abschnitt 6.

### 4.16 Der Strom laeuft weiter, ohne dass der Besitzer es merkt

**Impact** hoch. **Likelihood** niedrig.
**Mitigation** Vier Dinge, und nur zwei davon sind unsere:

1. **Androids eigene Aufnahme-Anzeige.** Statusleistensymbol bzw. Datenschutzindikator, solange
   eine MediaProjection laeuft. Keine App kann sie entfernen, unterdruecken oder ueberzeichnen.
   Das ist die belastbare Gegenmassnahme.
2. **Androids Einwilligungsdialog pro Sitzung.** Ab Android 14 ist die Projektionszustimmung nicht
   wiederverwendbar; das Protokoll verlangt sie ohnehin fuer jede Sitzung neu (8.5.2).
3. Unser Vordergrunddienst mit laufender Benachrichtigung, die den Empfaenger nennt und einen
   Stop-Knopf traegt.
4. `SCREEN_SESSION_TTL_MS` ohne Verlaengerung: nach zehn Minuten ist Schluss, auch wenn zugesehen
   wird. Eine Sitzung, die sich durch Aktivitaet verlaengert, laeuft genau so lange, wie jemand
   zusieht.

**Die Unumgehbarkeit unserer Benachrichtigung ist ausdruecklich nicht behauptet.** Ein
Vordergrunddienst-Posten laesst sich nicht wegwischen, solange der Dienst laeuft - aber der
Benachrichtigungskanal laesst sich in den Systemeinstellungen stummschalten, und einzelne
Hersteller-ROMs gehen darueber hinaus. Wer sich auf Punkt 3 verlaesst, verlaesst sich auf etwas,
das der Nutzer selbst abschalten kann. Punkt 1 kann er nicht abschalten.

Der Beitrag unserer Benachrichtigung ist deshalb nicht *dass* aufgenommen wird - das sagt das
System - sondern *fuer wen*, und der lokale Stop ohne Umweg ueber den Server.

**Residual** Auf einem ROM, das die Systemindikatoren manipuliert, faellt diese Verteidigung weg;
das ist 4.9 und ausserhalb des Schutzumfangs. Ungeprueft auf echter Hardware: dass die
Benachrichtigung tatsaechlich nicht wischbar ist und dass der Stop-Knopf die Projektion in unter
einer Sekunde beendet, steht bis zu einem Geraetetest als Behauptung da (Abschnitt 8).

### 4.17 Verbindungsabbruch waehrend laufender Aufnahme

**Impact** hoch. **Likelihood** hoch - Mobilfunk bricht ab, das ist der Normalfall, nicht der
Ausnahmefall.
**Mitigation** Das Geraet stoppt die MediaProjection **sofort**, wenn die Agent-Verbindung
verloren geht, und beendet danach den Vordergrunddienst (Protokoll 8.5.9). Es haelt die Aufnahme
nicht warm und wartet keinen Reconnect ab. Nach einem Reconnect gibt es keine Fortsetzung: ein
neuer Strom braucht eine neue Sitzung und beide Zustimmungen erneut.

Der naheliegende Optimierungsgedanke - Aufnahme laufen lassen, damit der Strom nach dem Reconnect
sofort wieder Bilder hat - ist genau die Schwachstelle: das Geraet filmt dann weiter, waehrend
niemand zusieht und der Besitzer die Sitzung fuer beendet haelt. Zusaetzlich greift
`SCREEN_STREAM_IDLE_TIMEOUT_MS` fuer den Fall, dass die Verbindung steht, aber niemand mehr
bestaetigt.

**Residual** Wird der Prozess hart beendet (Speichermangel, Force Stop), gibt es keinen Code mehr,
der aufraeumt; Android beendet die Projektion dann selbst mit dem Prozess. Der ungepruefte Punkt
ist die Reihenfolge: erst Aufnahme stoppen, dann Dienst beenden. Andersherum bliebe ein kurzes
Fenster ohne sichtbaren Hinweis bei noch laufender Projektion.

### 4.18 Der Bildstrom liest mit, was er nicht soll

**Impact** hoch. **Likelihood** mittel.
**Mitigation** MediaProjection nimmt die **gesamte** Anzeige auf, inklusive Benachrichtigungen,
Tastatureingaben und fremder Apps. Das ist der Unterschied zu `files.read`, wo der Besitzer einen
Bereich auswaehlt; hier waehlt er nur den Zeitpunkt.

Drei Dinge begrenzen den Schaden:

- Fenster mit `FLAG_SECURE` erscheinen schwarz. Banking-Apps und Passwortmanager setzen das.
- **Feedbacks eigene App-Sperre ist `FLAG_SECURE`.** Ohne das waere ein laufender Bildstrom der
  bequemste Weg, die Geheimzahl abzulesen, die die App schuetzt - ein Angreifer mit gestohlener
  Browser-Sitzung (4.5) koennte den Strom starten und warten, bis der Besitzer entsperrt.
- Kein Audio: weder Mikrofon noch `AudioPlaybackCaptureConfiguration`. Der Encoder hat keine
  Audiospur, und das Protokoll hat kein Feld dafuer.

**Residual** Apps, die `FLAG_SECURE` nicht setzen - also die meisten -, sind vollstaendig sichtbar.
Eine Zwei-Faktor-SMS in einer Benachrichtigung ist im Bild. Dagegen hilft nur, den Strom nicht zu
starten; deshalb ist `screen.view` eine eigene Capability ohne jede implizite Herleitung und
deshalb ist die Sitzung hart befristet.

### 4.19 Der Tunneldienst sieht mit

**Impact** hoch. **Likelihood** hoch, sobald der Tunnel TLS selbst beendet.
**Mitigation** 4.15 nennt als Widerrufspfad "den Server selbst betreiben". Das genuegt nicht, wenn
der Weg dorthin durch einen fremden Dienst fuehrt, der die TLS-Verbindung beendet. Bei einem
Cloudflare-Tunnel - dem Weg aus `BETRIEB.md` 3.1 und 3.1.1 - endet das oeffentliche HTTPS an
Cloudflares Rand, und von dort laeuft eine zweite, getrennte Verbindung zu `cloudflared`.
Cloudflare sieht dazwischen denselben Klartext wie der Serverbetreiber in 4.15: Dateiinhalte,
`system.info`, jeden `screen.frame` - und das Sitzungscookie.

Tailscale Funnel (`BETRIEB.md` 3.1.3) verhaelt sich anders: das Zertifikat liegt auf der eigenen
Maschine, `tailscaled` beendet TLS dort, und die Relays leiten nur verschluesselte Bytes weiter.
Dadurch kommt kein zusaetzlicher Mitleser hinzu.

**Residual** Wer einen TLS-beendenden Tunnel waehlt, hat einen zweiten Betreiber mit denselben
Moeglichkeiten wie in 4.15 - anders als dort ist es aber eine freie Wahl. Deshalb richtet
`tools/first-run.sh` bewusst keinen Tunnel ein: welcher Dienst die Verbindung nach aussen traegt,
ist eine Vertrauensentscheidung des Besitzers. Technisch aufloesen laesst sich auch das nur mit
Punkt 6 aus Abschnitt 6.

Mit den Einrichtungslinks kommt ein weiterer Dritter hinzu. Er sieht keine Inhalte, aber er ist
beteiligt: die Verifikation der App Links laeuft ab Android 12 nicht auf dem Geraet des Besitzers,
sondern ueber den Domain-Verification-Agent der Play-Dienste. Ein Dokument, das hier jeden
zusaetzlichen Mitleser benennt, benennt auch ihn - die Einzelheiten stehen in 4.21.

### 4.20 Untergeschobene Server-Adresse

**Impact** hoch: das Geraet koppelt mit einem fremden Control Server. Wer ihn betreibt, ist der
Betreiber aus 4.6 - mit allem, was dort steht, nur ohne dass der Besitzer ihn gewaehlt haette.
**Likelihood** mittel, und sie steigt mit dem Komfort. Eine Adresse, die abgetippt werden muss,
wird dabei gelesen; eine, die ein Link mitbringt, nicht.
**Mitigation** Eine Regel traegt das hier, und sie ist bewusst die einfachste, die reicht: **ein
Einrichtungslink fuehrt nie einen Server ein, er bestaetigt nur einen.** `SetupLinkPolicy.decide`
vergleicht die Origin aus dem Link gegen bis zu zwei Anker: den eingebauten Default
(`BuildConfig.DEFAULT_SERVER_URL`) und die Origin der bereits gespeicherten Registrierung. Ist sie
zu keinem von beiden zeichengleich, wird sie abgelehnt.

Verglichen wird immer die vollstaendige normalisierte Origin - Schema, Host **und** Port -, nie
nur der Host: `https://feedback.example.com` und `https://feedback.example.com:8443` sind
verschiedene Server, und `feedback.example.com.angreifer.example` ist ein dritter. Normalisiert
heisst dabei: kleingeschriebener Host, kein abschliessender Schraegstrich, und ein
ausgeschriebener Standardport 443 faellt weg - `https://feedback.example.com:443` und
`https://feedback.example.com` sind **dieselbe** Origin, jeder andere Port bleibt Teil des
Vergleichs. Diese Normalisierung steht in `ServerEndpoint.parse` und noch einmal, absichtlich
gleich, im Gradle-Skript, das den Default in den Build schreibt: ein Anker, den die App selbst
nicht akzeptieren wuerde, soll gar nicht erst entstehen.

**Was ein bestaetigter Link tatsaechlich aendert - und wann.** Uebernommen, also in das Adressfeld
geschrieben, wird eine Origin nur in genau einem Zustand: das Geraet ist **nicht gekoppelt**,
Einrichtungslinks sind nicht abgeschaltet, und es laeuft gerade keine Kopplung
(`SetupLinkPresentation.outcome`). Genau in diesem Zustand gibt es den zweiten Anker nicht: die
gespeicherte Registrierung wird an `SetupLinkPolicy` als `registeredOrigin` gereicht, und die
existiert nur zusammen mit `paired` (`FeedbackController`: `pairedServer` wird mit der Kopplung
gesetzt und mit Widerruf oder lokalem Entfernen geloescht). Bei einem gekoppelten Geraet endet ein
zeichengleicher Link deshalb als Meldung - "Der Einrichtungslink bestätigt den bereits gekoppelten
Server ..., es wurde nichts geändert" - und nie als Schreibvorgang.

**Die Registrierung ist damit ein Anker der Rueckmeldung, nicht der Uebernahme.** Sie entscheidet
allein darueber, ob der Besitzer eine Bestaetigung oder eine Ablehnung liest; ein Feld aendert sie
nie. Zu einer Uebernahme fuehren kann ausschliesslich der eingebaute Default. Das ist die
gewollte Eigenschaft und keine Luecke: ein gekoppeltes Geraet soll seine Serveradresse nicht per
Link wechseln - auch nicht auf die eigene -, denn ein Adresswechsel ist ein neues Pairing und
gehoert in die Hand des Besitzers.

Warum Gleichheit und nicht Herkunft: ein Link ist eine Zeichenkette ohne jede Bindung an den
Ueberbringer. Ein praeparierter Link in einer Nachricht, ein expliziter Intent einer fremden App
an die exportierte Einstiegs-Activity, ein ueberklebter QR-Code auf einem Ausdruck - alle drei
kommen als derselbe Eingabewert an, und keine Pruefung, woher er "stammt", trennt sie
voneinander. An der Gleichheitsregel scheitern alle drei an derselben Stelle. Der Vertrauensanker
ist der Build, nicht der Link.

Dazu, aus dem uebrigen Modell unveraendert: `ServerEndpoint.parse` laesst keine Adressliterale zu
(4.4), die Kopplung startet immer der Besitzer - ein bestaetigter Link fuellt hoechstens das
Adressfeld aus und drueckt keinen Knopf -, und ein Link, der bei gesperrter App ankommt, aendert
nichts, sondern wartet auf das Entsperren. Eine abgelehnte Adresse wird dem Besitzer als Ablehnung
gezeigt und nennt beide Seiten; eine stille Ablehnung waere keine. Wer Einrichtungslinks gar nicht
will, schaltet sie dauerhaft ab, und kein Link kann sie wieder einschalten.

**Residual** **Damit ist 4.20 nur halb gemildert.** Wer eine APK verteilt, die mit einem fremden
`FEEDBACK_SERVER_URL` gebaut wurde, umgeht die Regel vollstaendig: dort *ist* der fremde Server der
eingebaute Default, und die Gleichheitsregel bestaetigt ihn bereitwillig. Dagegen hilft keine Regel
in der App, sondern nur nachpruefbare Herkunft der APK - also 4.10 und ein stabiler
Signaturschluessel. Solange die Signatur zwischen Builds wechselt, gibt es diese Nachpruefbarkeit
nicht, und dann traegt die andere Haelfte niemand.

### 4.21 Verlust der Domain bei verifizierten App Links

**Impact** hoch, aber mit Verzoegerung. **Likelihood** niedrig. **Eintritt nicht erkennbar** - auf
dem Geraet sieht das Ergebnis genauso aus wie vorher, weil es dasselbe ist: ein Link auf die
gewohnte Domain, der die App oeffnet.
**Mitigation** Kaum eine technische. `/.well-known/assetlinks.json` nennt den SHA-256 des
Signaturzertifikats, und dieser Fingerabdruck ist kein Geheimnis: er steht in jeder Kopie der
veroeffentlichten APK und laesst sich daraus ausrechnen. Wer die Domain spaeter uebernimmt - sie
laeuft ab, wird verkauft, ein Konto wird gekuendigt -, legt dieselbe Datei mit demselben
Fingerabdruck ab und ist von da an das verifizierte Ziel fuer `https://<domain>/pair` auf jedem
Geraet, das die Verifikation neu durchlaufen laesst.

Was geschuetzt bleibt, ist eine **bestehende** Registrierung gegen den *Link*: ihre
Endpunkt-Adresse liegt Keystore-versiegelt neben dem Geraeteschluessel, und kein Link kann sie
umbiegen - er bestaetigt dort hoechstens, was ohnehin gilt. Gegen den Domainwechsel selbst
schuetzt das nicht: das Geraet spricht weiter mit *der Adresse*, und hinter der Adresse steht dann
der neue Inhaber, dem der Agent im Handshake seinen `deviceToken` reicht. Nicht geschuetzt ist
ausserdem jede **Neu**kopplung: ein frisch installiertes Geraet hat als Anker allein den
eingebauten Default, und der zeigt auf genau die verlorene Domain.

Bleibt der betriebliche Weg, und der steht in `BETRIEB.md` 3.6: die Domain halten, und bei Verlust
alle Geraete widerrufen und einen Build mit neuer Adresse ausliefern. Der Widerruf ist dabei nicht
die Kuer - er ist der einzige Schritt, der ein bereits gekoppeltes Geraet von der Adresse loest.

**Residual** Vollstaendig, solange die Server-Identitaet nicht an einen beim Pairing gesehenen
Schluessel gebunden ist. Das ist **Abschnitt 6 Punkt 5**, und die Einrichtungslinks machen diesen
offenen Punkt dringlicher: bis dahin war die Serveradresse etwas, das der Besitzer bei jeder
Kopplung selbst eintippte, und ein Vertippen fiel ihm auf. Jetzt bringt sie der Build mit, und ein
Geraet, das nie eine falsche Adresse zu sehen bekommt, kann auch keine bemerken.

Zwei Nebenwirkungen, die nicht als Feature durchgehen duerfen:

- **Ein Dritter verifiziert.** `autoVerify` laeuft ab Android 12 ueber den
  Domain-Verification-Agent der Play-Dienste. Die `assetlinks.json` wird also nicht vom Geraet des
  Besitzers geholt, und der Host muss dafuer oeffentlich und ohne Authentisierung ueber Port 443
  erreichbar sein. Dieser Dritte sieht keine Inhalte, aber er entscheidet, ob ein Link die App
  oeffnet - deshalb steht er neben den Beteiligten aus 4.19.
- **Ein Konfigurations-Orakel.** `/.well-known/assetlinks.json` antwortet 404 oder 200 und sagt
  damit jedem, der fragt, ob diese Installation ein Android-Geraet erwartet. Zusammen mit dem
  unauthentifizierten `/health` ist jede Feedback-Installation im Internet als solche erkennbar.
  Es leakt dabei kein Geheimnis - der Fingerabdruck ist per Definition oeffentlich, und beide
  Antworten sagen nichts ueber den Kopplungszustand -, aber wer nach Feedback-Servern sucht,
  findet sie, und das gehoert hingeschrieben statt beschwiegen.

## 5. Wiederkehrende Muster

Drei Entscheidungen tauchen in fast jeder Zeile oben auf:

- **Zwei Seiten muessen zustimmen.** Server- und Geraetefreigabe sind getrennt, und das Geraet
  prueft selbst. Das ist der Grund, warum 4.5 und 4.6 begrenzt bleiben statt total zu sein.
- **Unbekannt statt verboten.** Eine Antwort, die zwischen "existiert nicht" und "darfst du
  nicht" unterscheidet, ist ein Orakel. Feedback antwortet `NOT_FOUND`.
- **Ablehnen statt reparieren.** Ein Dateiname mit Pfadanteil, ein Chunk ausser der Reihe, eine
  Antwort ohne `relatesTo`: alles wird verworfen, nicht zurechtgebogen.

## 6. Offene Punkte

Ungeloest, nach Nutzen sortiert:

1. **Kein Certificate Pinning** auf Android (4.4).
2. **Keine zweite Stufe im Control Center** vor dem Erteilen einer Capability (4.5).
3. **Keine Rotation des `deviceToken`** (4.13).
4. **Rate Limits und Presence nur im Prozessspeicher** (4.14).
5. **Kein Pinning der Server-Identitaet** an den beim Pairing gesehenen Schluessel (TOFU waere
   moeglich). Mit den Einrichtungslinks ist dieser Punkt **dringlicher** geworden: er ist der
   einzige, der 4.20 und 4.21 zugleich schliessen wuerde. Solange die Server-Identitaet eine
   Adresse ist, erbt jeder ihr Vertrauen, der diese Adresse bekommt - der Angreifer, der sie
   unterschiebt, und der naechste Inhaber der Domain. Ein beim Pairing gesehener Schluessel ist
   nicht uebertragbar.
6. **Keine Ende-zu-Ende-Verschluesselung der Inhalte** gegenueber dem Serverbetreiber und gegen
   einen TLS-beendenden Tunnel - weder fuer Dateien noch fuer Bildframes (4.15, 4.19). Das ist
   der groesste offene Punkt der Liste und der einzige, dessen Loesung neue Kryptografie braucht
   statt nur Sorgfalt.
7. **Kein Schutz fremder Oberflaechen gegen Remote-Input**, falls `screen.control` je gebaut wird
   (Abschnitt 7 und ADR-005). Fuer Feedbacks eigene Bildschirme gibt es eine Massnahme, fuer die
   aller anderen Apps nicht.

## 7. Was vor Milestone 6 zu ergaenzen ist - beantwortet

Die vier Fragen, die hier standen, sind beantwortet. Die Antworten stehen in
`docs/decisions/ADR-005-remote-input.md`; hier die Kurzfassung, weil sie unbequem ist.

**Was ein Eingabeereignis ausloesen kann, das der Besitzer nicht will.** Alles, was ein Finger
kann: eine Bestaetigung in einer Banking-App, die Annahme einer Berechtigungsabfrage, das
Deaktivieren von Feedbacks eigener App-Sperre, die Bestaetigung einer neuen Kopplung. Der Angriff
auf den eigenen Widerrufspfad ist der wichtigste, und er ist der einzige, gegen den wir selbst
etwas tun koennen: keine Geste, solange eine sensible eigene Oberflaeche im Vordergrund ist
(ADR-005, Bedingung 4). Ausserhalb von Feedback gibt es keine solche Schutzzone.

**Ob es ohne AccessibilityService geht: nein.** `INJECT_EVENTS` ist Signaturberechtigung,
MediaProjection sendet nichts, OEM-Erweiterungen sind herstellersigniert, und `adb shell input`
waere eine Shell und damit durch die harten Regeln ausgeschlossen. Der Dienst kann von Haus aus
jeden Bildschirminhalt jeder App lesen.

Dagegen gibt es genau eine Massnahme, die die Plattform erzwingt statt wir sie zu versprechen: den
Dienst **ohne** Inhaltszugriff deklarieren, nur mit `CAPABILITY_CAN_PERFORM_GESTURES`. Dann sind
Tippen, Wischen und drei globale Aktionen moeglich - und **Texteingabe ist unmoeglich**, weil Text
einen Knoten braucht und Knoten Inhaltszugriff. Die Deklaration steht in der XML eines signierten
APK und ist damit nachpruefbar.

**Warum eine uebernommene Steuerung etwas anderes ist als ein uebernommener Bildstrom.** Wer den
Bildstrom hat, *sieht* die Bestaetigung. Wer die Steuerung hat, *drueckt* sie. Derselbe Angreifer
aus 4.5 - gestohlene Browser-Sitzung - geht damit von Beobachten zu Handeln ueber, und der
Besitzer merkt es nur, solange er hinsieht.

**Ob Eingabe eine eigene Zustimmung braucht: ja, und mehr.** Eigene Zustimmung pro Sitzung, nur
innerhalb eines laufenden und sichtbaren Bildstroms, harte kurze Sitzung ohne Verlaengerung,
lokaler Stop ohne Umweg ueber den Server, sofortiges Ende bei Verbindungsverlust.

**Empfehlung dieses Dokuments: nicht bauen, solange kein Bedarf benannt ist, den `screen.view`
nicht deckt.** Der Weg ist in ADR-005 beschrieben und die Bedingungen stehen fest; was fehlt, ist
die Antwort auf "wofuer". Bis dahin bleibt `screen.control` deklariert, deny-by-default und
antwortet `UNSUPPORTED`.

Was **stattdessen** zuerst ansteht, weil es bestehende Zusagen betrifft statt neue zu machen:

- Ein Lauf gegen echte Hardware nach `docs/deployment/BETRIEB.md` 3.4 und 3.5. Ohne ihn sind
  4.16 und 4.17 Behauptungen.
- Milestone 7: Fuzzing der Protokollparser, Dependency-Audit, Batterie-Review.
- Die offenen Punkte aus Abschnitt 6, vor allem Nummer 6.

## 8. Abnahme

**Nicht abgenommen.** Dieses Dokument beschreibt den Code, wie er ist, aber:

- Keine der Massnahmen wurde auf echter Hardware gegen einen echten Angriff geprueft.
- Es gab kein Fuzzing der Protokollparser und kein Dependency-Audit (Milestone 7).
- Die Wahrscheinlichkeitsangaben sind begruendete Einschaetzungen, keine Messungen.
