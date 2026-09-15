# Betrieb und erster echter Test

Stand: 2026-09-15

Diese Datei beantwortet eine Frage: **ab wann kann man nachsehen, ob Feedback wirklich
funktioniert - mit einem echten Handy?**

## 1. Was heute schon testbar waere

Diese Kette ist implementiert und CI-verifiziert. Sie braucht **keinen** weiteren Code, nur
einen erreichbaren Server:

| Schritt | Stand |
| --- | --- |
| App installieren, Geraeteidentitaet entsteht im Android Keystore | fertig |
| App-Sperre einrichten, entsperren, Auto-Lock, Biometrie | fertig |
| Pairing starten, sechsstelliger Code und QR-Code | fertig |
| Kopplung im Control Center bestaetigen oder ablehnen | fertig |
| Geraet erscheint online, Presence und "zuletzt gesehen" | fertig |
| Hintergrundverbindung ein- und ausschalten | fertig |
| `system.info` freigeben und live abfragen | fertig |
| Capability entziehen, Geraet widerrufen | fertig |
| Dateibereiche auf dem Geraet freigeben und entziehen | fertig |

**Noch nicht testbar:** die freigegebenen Dateien im Browser durchsuchen und herunterladen.
Das Geraet kann antworten, aber der Server hat die Files-Endpunkte noch nicht
(`docs/roadmap/OPEN_WORK.md` Abschnitt 2.2) und das Control Center keinen Dateien-Tab (2.3).

## 2. Was dafuer gebraucht wird

Genau eine Sache, und die ist nicht Code: **ein oeffentlich erreichbarer HTTPS-Endpunkt.**

Feedback ist internet-first (`FEEDBACK_CONSTITUTION.md` Punkt 13). Das Handy verbindet sich
ausgehend zum Server, nicht ins lokale Netz. Der Android-Client laesst ausschliesslich `https://`
mit echtem Hostnamen zu (`ServerEndpoint.parse`) - keine IP, kein Klartext, kein
selbstsigniertes Zertifikat. Das ist Absicht: ueber Mobilfunk gibt es kein vertrauenswuerdiges Netz.

Der Serverprozess selbst lauscht standardmaessig nur auf `127.0.0.1:8080` und terminiert kein TLS.
Davor gehoert ein Reverse Proxy oder ein Tunnel.

## 3. Bedienung

Fuenf Wege nach draussen. Was sie unterscheidet:

| Weg | Adresse | Kosten | Server laeuft | Klartext sieht ausser Ihnen |
| --- | --- | --- | --- | --- |
| 3.1 Schnelltunnel | wechselt bei jedem Start | 0 | zu Hause | Cloudflare (4.19) |
| 3.1.1 benannter Tunnel | fest | Domain, ca. 10 EUR/Jahr | zu Hause | Cloudflare (4.19) |
| 3.1.3 Tailscale Funnel | fest | 0 | zu Hause | niemand |
| 3.1.4 freie Subdomain + VM | fest | 0 | beim Anbieter | der Anbieter (4.15) |
| 3.2 eigener Host | fest | Domain + Server | beim Anbieter | der Anbieter (4.15) |

**Was eine eigene Domain bringt.** Keine Capability und keine zusaetzliche Sicherheit - die App
kann mit `*.ts.net` genau dasselbe. Sie kauft drei andere Dinge:

- **Die feste Adresse aus 3.1.1.** Ohne sie muss nach jedem Serverstart jedes Geraet neu gekoppelt
  werden.
- **Unabhaengigkeit vom Anbieter.** `*.ts.net` gehoert Tailscale, `*.duckdns.org` gehoert DuckDNS.
  Wer spaeter den Weg oder den Hoster wechselt, aendert damit die Server-URL - und weil die
  Registrierung auf dem Geraet an der Adresse haengt, muss **jedes gekoppelte Geraet neu
  gekoppelt werden**. Eine eigene Domain zeigt stattdessen einfach woanders hin, und die Geraete
  merken nichts davon.
- **Die Kopplung ohne Tippen aus 3.6.** Auch das ist Komfort und keine Sicherheit: der Link
  erspart das Abtippen der Adresse, mehr nicht. Er bringt dafuer eine Betriebspflicht mit, die es
  ohne ihn nicht gibt - die Domain muss gehalten werden (3.6, Threat Model 4.21).

Die feste Adresse allein gibt es in 3.1.3 auch umsonst. Wer nie wechseln will, braucht keine
Domain.

### 3.1 Schnellster Weg zum ersten Test: Tunnel

Ohne Server mieten, in wenigen Minuten. Ein Tunneldienst (z. B. Cloudflare Tunnel) gibt einen
oeffentlichen HTTPS-Hostnamen, der auf den lokal laufenden Prozess zeigt.

**Mit einem Befehl:**

```bash
tools/first-run.sh https://<dein-tunnel-host>
```

Das Skript legt `.env` an (mit frisch erzeugtem Cookie-Secret), installiert, baut Control Center
und Server, wendet die Migrationen an und sagt, welcher Befehl als naechstes dran ist. Es richtet
bewusst **keinen** Tunnel ein: welcher Dienst die Verbindung nach aussen traegt, ist eine
Vertrauensentscheidung des Besitzers. Bei Cloudflare endet das oeffentliche HTTPS an deren Rand,
nicht auf Ihrer Maschine - Cloudflare sieht damit Dateiinhalte, Bildframes und das Sitzungscookie
im Klartext (Bedrohung 4.19). Fuer einen ersten Test ist das vertretbar, als Dauerzustand ist es
eine Entscheidung, die man bewusst trifft.

Es setzt dabei `FEEDBACK_STATIC_DIR`, sodass der Server das gebaute Control Center **selbst**
ausliefert. Damit gibt es eine Origin statt zweier: das HttpOnly-Cookie braucht keine Ausnahme,
es gibt kein CORS, und ein Tunnel genuegt.

Die Schritte einzeln, falls etwas schiefgeht:

```bash
# 1. Server vorbereiten
cd server
npm ci
cp .env.example .env
```

In `.env` mindestens setzen:

```bash
FEEDBACK_COOKIE_SECRET=$(node -e "console.log(require('node:crypto').randomBytes(32).toString('base64url'))")
FEEDBACK_ALLOWED_ORIGINS=https://<dein-tunnel-host>
```

```bash
# 2. Datenbank anlegen und Benutzer erstellen
npm run migrate
npm run user:create -- --email <deine-adresse>

# 3. Server starten
npm run build && npm start

# 4. In einem zweiten Terminal den Tunnel auf Port 8080 richten
```

Das Control Center wird gebaut und **von derselben Origin** ausgeliefert wie die API. Dann sieht
der Browser eine einzige Herkunft, die HttpOnly-Cookies funktionieren und es gibt kein CORS:

```bash
cd control-web
npm ci
npm run build      # Ergebnis liegt in control-web/dist/
```

Mit `FEEDBACK_STATIC_DIR=../control-web/dist` liefert der Server diese Dateien selbst aus - dann
braucht es keinen Reverse Proxy, nur den Tunnel auf Port 8080. Ein Deployment mit eigenem Proxy
laesst die Variable weg und leitet `/api/*` an den Server und alles andere auf
`control-web/dist/`.

Liegt das Control Center aus gutem Grund auf einer anderen Origin, wird es mit
`VITE_FEEDBACK_API_BASE_URL=https://<api-host>` gebaut, und diese Origin muss in
`FEEDBACK_ALLOWED_ORIGINS` stehen.

### 3.1.1 Feste Adresse: benannter Tunnel

Der Schnelltunnel bekommt bei jedem Start einen neuen Hostnamen. Weil die Registrierung auf dem
Geraet die Server-Adresse mitspeichert, heisst das: **jedes gekoppelte Geraet muss danach neu
gekoppelt werden.** Bei einem Testgeraet ist das laestig, bei mehreren ist es der Grund
aufzuhoeren.

Ein benannter Tunnel loest das. Er braucht ein Cloudflare-Konto und eine **eigene Domain** dort -
fuer die zufaelligen `*.trycloudflare.com`-Namen gibt es bauartbedingt keinen festen Ersatz.

```bash
cloudflared tunnel login                       # oeffnet den Browser, waehlt die Domain
cloudflared tunnel create feedback             # nennt eine UUID und schreibt die Credentials
cloudflared tunnel route dns feedback feedback.<deine-domain>
```

`~/.cloudflared/config.yml`:

```yaml
tunnel: <UUID aus dem create-Befehl>
credentials-file: /home/<user>/.cloudflared/<UUID>.json

ingress:
  - hostname: feedback.<deine-domain>
    service: http://127.0.0.1:8080
  - service: http_status:404
```

```bash
cloudflared tunnel run feedback                # zum Testen im Vordergrund
sudo cloudflared service install                # danach als Dienst, startet mit dem System
```

Dann einmal einrichten und fertig:

```bash
tools/first-run.sh https://feedback.<deine-domain>
cd server && npm start
```

**Warum die App das aushaelt.** Cloudflare trennt untaetige Verbindungen nach rund 100 Sekunden.
Der SSE-Bildstrom sendet alle `SSE_KEEPALIVE_INTERVAL_MS` (15 s) einen Kommentar, der Agent alle
`HEARTBEAT_INTERVAL_MS` (30 s) einen Heartbeat - beides liegt darunter. `Cache-Control:
no-transform` und `X-Accel-Buffering: no` verhindern, dass ein Proxy den Bildstrom puffert.
WebSockets laufen ueber Cloudflare-Tunnel ohne Zusatzkonfiguration.

**`FEEDBACK_TRUST_PROXY=true` ist hier vertretbar** - aber nur, weil der Server an `127.0.0.1`
lauscht und damit ausschliesslich ueber cloudflared erreichbar ist. Ohne den Schalter kommen alle
Anfragen scheinbar von `127.0.0.1`, und das Rate Limit pro IP wird zu einem gemeinsamen Limit.
Lauscht der Server auf einer oeffentlichen Adresse, gehoert der Schalter wieder aus: dann koennte
jeder den Header faelschen.

### 3.1.2 Ohne eigene Domain

Eine eigene Domain kostet rund 10 EUR im Jahr und macht 3.1.1 sofort moeglich - billiger als ein
Monat gemieteter Server. Wer das nicht ausgeben will, hat trotzdem zwei Wege, die dauerhaft und
kostenlos laufen: 3.1.3 (Server bleibt zu Hause) und 3.1.4 (Server bei einem Anbieter).

Kein Zertifikat gibt es nur fuer eine **nackte IP-Adresse**. Ein kostenloser Hostname wie
`*.ts.net` oder `*.duckdns.org` ist dagegen eine Domain wie jede andere, und Let's Encrypt stellt
dafuer ein gueltiges Zertifikat aus. `ServerEndpoint` verlangt sauberes HTTPS, nicht eine gekaufte
Domain.

Wer stattdessen einen kleinen Node-Hoster nimmt, prueft drei Dinge, sonst passt es nicht zu
diesem Server:

- **Kein Schlafmodus.** Ein Dienst, der bei Untaetigkeit einschlaeft, trennt die Agent-Verbindung;
  das Geraet gilt dann als offline. Kostenlose Stufen tun das haeufig.
- **Dauerhafter Datentraeger.** Die SQLite-Datei muss einen Neustart ueberleben.
- **Ein Prozess.** Presence, Rate Limits und laufende Uebertragungen liegen im Arbeitsspeicher
  (`OPEN_WORK.md` Abschnitt 4); zwei Instanzen kennen einander nicht.

Der Unterschied ist nicht nur technisch: liegt der Server bei einem Anbieter, sieht dessen
Betreiber im Prinzip, was Bedrohung 4.15 beschreibt. Zu Hause hinter einem Tunnel bleibt es bei
Ihnen.

**Nicht geeignet: Cloudflare Workers.** Kein Dateisystem fuer SQLite, keine nativen Module, kein
langlebiger Prozess fuer Presence und laufende Uebertragungen, und `ws` laeuft dort nicht. Das
waere ein Umbau auf Durable Objects, keine Deployment-Einstellung.

### 3.1.3 Kostenlos und dauerhaft: Tailscale Funnel

Der Weg mit der besten Sicherheitseigenschaft - vorausgesetzt, eine Maschine zu Hause kann
durchlaufen (alter Laptop, Raspberry Pi, NAS). Der Server bleibt dort, Funnel gibt ihm einen
festen oeffentlichen Hostnamen. Keine Portfreigabe, keine feste IP, funktioniert auch hinter
CGNAT.

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

In der Admin-Konsole einmalig HTTPS-Zertifikate fuer das Tailnet aktivieren und `funnel` in den
Access Controls fuer diesen Knoten erlauben. Danach:

```bash
tailscale funnel --bg 8080
tailscale funnel status          # nennt den Hostnamen <maschine>.<tailnet>.ts.net
```

Der Hostname bleibt derselbe, solange der Knoten im Tailnet bleibt - genau das, was die
Registrierung auf dem Geraet braucht (siehe 3.1.1).

```bash
tools/first-run.sh https://<maschine>.<tailnet>.ts.net
cd server && npm start
```

**Warum das der sicherste kostenlose Weg ist.** Das Zertifikat liegt auf der eigenen Maschine, und
`tailscaled` terminiert die TLS-Verbindung dort. Die Funnel-Relays leiten nur verschluesselte
Bytes weiter und sehen den Klartext nicht. Es kommt also kein weiterer Betreiber hinzu, der unter
Bedrohung 4.15 faellt - der Server bleibt bei Ihnen.

**`FEEDBACK_TRUST_PROXY` bleibt hier aus.** Funnel setzt `X-Forwarded-For` nicht selbst und reicht
einen vom Client mitgeschickten Wert durch; mit `true` koennte deshalb jeder das Rate Limit
umgehen. Der Preis fuer `false` ist kleiner: alle Anfragen kommen scheinbar von wenigen
Relay-Adressen, das Limit pro IP wirkt dadurch wie ein gemeinsames Limit.

**Ungeprueft: Durchsatz fuer `screen.view`.** Tailscale nennt keine Zahl fuer die
Funnel-Bandbreite und beschreibt Funnel als Weg, einen Dienst zu teilen, nicht als Transport fuer
dauerhaft hohen Durchsatz. `system.info` und `files.*` sind klein, der Bildstrom laeuft bis zu
`SCREEN_MAX_BITRATE_KBPS` (2500 kbit/s). Ob das durchgeht, ist hier nicht getestet - das zeigt
erst 3.5 auf echter Hardware.

### 3.1.4 Kostenlos und dauerhaft: freie Subdomain und eigener Server

Fuer den Fall, dass zu Hause keine Maschine durchlaufen kann. Beide Teile sind kostenlos:

- **Hostname:** ein kostenloser DNS-Dienst, z. B. `<name>.duckdns.org`. Let's Encrypt stellt
  dafuer ein Zertifikat aus - per DNS-01-Challenge auch dann, wenn Port 80 nicht offen ist.
- **Maschine:** eine Always-Free-VM (z. B. Oracle Cloud). Sie schlaeft nicht ein, hat einen
  dauerhaften Datentraeger und laeuft als ein Prozess; die drei Bedingungen aus 3.1.2 sind
  erfuellt.

Danach gilt 3.2 unveraendert: Reverse Proxy mit Zertifikat davor, `NODE_ENV=production`,
`FEEDBACK_TRUST_PROXY=true` nur wenn dieser Proxy `X-Forwarded-For` selbst setzt, und ein
Dienst-Manager.

Drei Dinge, die auf diesem Weg wirklich stoeren koennen:

- **Untaetige Instanzen werden eingesammelt.** Oracle behaelt sich vor, Always-Free-Instanzen
  zurueckzuholen, die ueber sieben Tage kaum Last haben. Dieser Server ist die meiste Zeit genau
  das. Wer den Weg geht, braucht ein Backup der SQLite-Datei und muss mit Ausfall rechnen.
- **Kapazitaet.** Die kostenlosen ARM-Instanzen sind in vielen Regionen ausgebucht, und die
  Heimatregion laesst sich spaeter nicht mehr wechseln.
- **Bedrohung 4.15 gilt voll.** Der Anbieter hat den Klartext im Zugriff - Dateien, `system.info`
  und den Bildstrom. Bei 3.1.3 ist das nicht so.

### 3.1.5 Wenn der Server nur zeitweise laeuft

Laeuft der Server auf einem PC, der nicht durchgehend an ist, gilt:

- **Server aus**: die Geraete stehen offline. Es gibt keinen Weg am Server vorbei, er vermittelt
  alles.
- **Server wieder an**: das Geraet meldet sich von selbst zurueck. Der Agent versucht es nach
  `5 s`, dann verdoppelt, gedeckelt bei `5 min` mit 80-120 % Streuung - spaetestens nach etwa
  sechs Minuten steht die Verbindung wieder. Nichts ist zu tun.
- **Die Kopplung ueberlebt das.** Geraete-Token und Server-Adresse liegen versiegelt neben dem
  Keystore-Schluessel; ein ausgeschalteter Server ist fuer das Geraet ein Netzausfall, kein
  Identitaetswechsel. Erneutes Koppeln ist nicht noetig.
- **Geraet neu gestartet**: der `BootReceiver` bringt die Hintergrundverbindung zurueck, sofern
  der Besitzer sie eingeschaltet hatte (`docs/architecture/BACKGROUND_SERVICE.md`). Ob das auf
  jeder Hersteller-Oberflaeche klappt, ist nicht auf Hardware geprueft - manche halten Autostart
  zusaetzlich zurueck.

Ein schlafender PC ist dabei dasselbe wie ein ausgeschalteter. Wer jederzeit zugreifen will,
schaltet den Ruhezustand ab oder nimmt einen kleinen Dauerlaeufer.

### 3.2 Dauerhafter Weg: eigener Host

Gleiche Schritte, aber mit einem echten Hostnamen, einem Zertifikat (z. B. Let's Encrypt) und
einem Reverse Proxy davor. Zusaetzlich:

- `FEEDBACK_TRUST_PROXY=true` **nur**, wenn der Proxy `X-Forwarded-For` selbst setzt - sonst
  laesst sich das Rate Limit per Header umgehen.
- `NODE_ENV=production`, damit das Session-Cookie `Secure` gesetzt bekommt.
- Ein Dienst-Manager (systemd o. ae.), der den Prozess neu startet.

### 3.3 APK aufs Handy

Siehe `ANDROID_RELEASE.md`. Kurz: den Release-Workflow auf `main` laufen lassen und
`Feedback.apk` direkt auf dem Handy aus dem GitHub-Release laden.

**Vorher den Signaturschluessel hinterlegen.** Ohne ihn aendert sich die Signatur zwischen
Builds, Android verlangt vor jedem Update eine Deinstallation - und die loescht den
Keystore-Schluessel, also Geraeteidentitaet und Kopplung. Beim Testen mit mehreren Builds
hintereinander ist das der Unterschied zwischen "Update" und "jedes Mal neu koppeln".

### 3.4 Der eigentliche Test

1. Control Center im Browser oeffnen, anmelden.
2. App auf dem Handy oeffnen, App-Sperre einrichten, in der Karte "Mit Control Center koppeln"
   die Server-Adresse eintragen und "Kopplung starten" antippen.
3. Sechsstelligen Code im Control Center in der Karte **Koppeln** eingeben und **Gerät prüfen**
   anklicken. Der QR-Code auf dem Handy hilft hier nicht: er traegt `feedback://...`, das kein
   Browser oeffnet, und das Control Center hat kein Feld fuer ein Ticket (`PAIRING.md`).
4. Kopplung bestaetigen. Das Geraet sollte in Sekunden als online erscheinen.
5. Auf dem Handy "Systeminformationen" freigeben, im Control Center dieselbe Capability
   freigeben, dann dort abfragen. Nur wenn **beide** Seiten freigegeben haben, kommt eine Antwort.
6. Zum Gegentest: eine Seite entziehen und erneut abfragen. Es muss eine klare Ablehnung kommen,
   keine alten Daten.
7. Mobilfunk statt WLAN einschalten. Die Verbindung muss genauso funktionieren - das ist der
   Test fuer Punkt 13.
8. Geraet im Control Center widerrufen. Die App muss das bemerken und in den Zustand `REVOKED`
   gehen.

### 3.5 Bildschirm (`screen.view`)

Der Teil mit den meisten ungepruefeten Annahmen. Schritte 1 bis 4 aus 3.4 muessen stehen.

1. Auf dem Handy "Bildschirm zeigen" freigeben (fragt die App-Sperre ab), im Control Center
   dieselbe Capability freigeben.
2. Im Control Center "Bildschirm anfragen". Auf dem Handy muessen **zwei** Dialoge erscheinen:
   erst Feedbacks eigene Rueckfrage, danach Androids MediaProjection-Dialog. Erscheint nur einer,
   ist das ein Fehler.
3. Erst ablehnen. Im Control Center muss "Am Geraet abgelehnt" stehen, kein Timeout, und es darf
   kein Bild kommen.
4. Neu anfragen und zustimmen. Das Bild muss erscheinen; in der Statusleiste muss Androids eigene
   Aufnahme-Anzeige stehen **und** Feedbacks Benachrichtigung mit "Stoppen".
5. Pruefen, was 4.16 im Threat Model offen laesst: laesst sich die Feedback-Benachrichtigung
   wegwischen? Bleibt Androids Anzeige, wenn man den Benachrichtigungskanal stummschaltet?
   Das Ergebnis gehoert ins Threat Model, egal wie es ausfaellt.
6. "Stoppen" in der Benachrichtigung druecken. Das Bild muss sofort aufhoeren, und Androids
   Aufnahme-Anzeige muss verschwinden.
7. Neu starten, dann das Handy in den Flugmodus schalten. Die Aufnahme muss **sofort** enden -
   nicht erst nach einer Zeitueberschreitung (Protokoll 8.5.9). Androids Anzeige ist der Beweis.
8. Neu starten und die App-Sperre oeffnen. Im Bild muss die PIN-Eingabe **schwarz** sein
   (`FLAG_SECURE`). Ist sie lesbar, ist das ein Sicherheitsfehler und kein Schoenheitsfehler.
9. Waehrend der Uebertragung `screen.view` im Control Center entziehen. Das Bild muss sofort
   enden, nicht erst nach zehn Minuten.
10. Ueber Mobilfunk wiederholen. Latenz und Bildrate notieren - bei 15 fps und 2500 kbit/s ist
    das der Punkt, an dem sich zeigt, ob der Serverpfad (ADR-004) in der Praxis taugt.
11. Zehn Minuten laufen lassen. Nach `SCREEN_SESSION_TTL_MS` muss Schluss sein, und ein neuer
    Blick muss beide Zustimmungen erneut verlangen.

### 3.6 Kopplung ohne Tippen

Bis hierher wird die Serveradresse auf jedem Geraet abgetippt (3.4, Schritt 2). Mit einer eigenen
Domain geht es ohne: der Besitzer oeffnet auf dem Geraet `https://<deine-domain>/pair`, die App
faengt den Link ab und traegt die Adresse ein. **Die Kopplung startet danach weiterhin der
Besitzer** - der Link fuellt ein Feld aus, mehr nicht.

Was der Link *nicht* kann, gehoert zum Verstaendnis dazu: er bringt der App keinen Server bei. Sie
akzeptiert eine Adresse aus einem Link nur, wenn sie zeichengleich ist mit der beim Build
eingebauten oder mit der, mit der sie bereits gekoppelt ist. Jede andere Adresse wird abgelehnt und
die Ablehnung angezeigt (Threat Model 4.20). Ein Link an ein Geraet, dessen App Ihren Server nicht
ohnehin kennt, richtet deshalb nichts aus.

**Und ein gekoppeltes Geraet aendert seine Adresse ueberhaupt nicht per Link.** Eingetragen wird
eine Adresse nur, solange das Geraet noch nicht gekoppelt ist; auf einem gekoppelten meldet die App
lediglich, dass der Link den bereits gekoppelten Server bestaetigt, und aendert nichts. Eine neue
Serveradresse bedeutet immer: lokale Kopplung entfernen bzw. im Control Center widerrufen und neu
koppeln.

**Zwei Einstellungen, die zusammengehoeren.**

| Wo | Schluessel | Wert |
| --- | --- | --- |
| GitHub → Settings → Secrets and variables → Actions → **Variables** | `FEEDBACK_SERVER_URL` | `https://<deine-domain>` - genau die Origin, kein Pfad, kein abschliessender Schraegstrich |
| Server-`.env` | `FEEDBACK_ANDROID_CERT_SHA256` | die `SHA256:`-Zeile aus `keytool -list -v -keystore <datei> -alias <alias>`: 32 Hex-Paare in Grossbuchstaben, durch Doppelpunkte getrennt |
| Server-`.env`, nur bei abweichender Anwendungs-ID | `FEEDBACK_ANDROID_PACKAGE` | Standard ist `com.redurbabat.feedback` |
| Server-`.env`, wenn API und Control Center auf verschiedenen Hosts liegen | `FEEDBACK_PUBLIC_ORIGIN` | `https://<deine-domain>` - die oeffentliche Adresse **dieses** Servers |

`FEEDBACK_PUBLIC_ORIGIN` braucht nur, wer API und Control Center auf getrennten Hosts betreibt.
Bei der in 3.1 empfohlenen Ein-Origin-Installation leitet der Server die Adresse selbst ab. Fehlt
die Variable in einem getrennten Aufbau, nennt `/pair` bewusst **gar keine** Adresse und sagt das
auch: die Seite bittet den Besucher, sich auf den Namen zu verlassen, und darf ihn deshalb nicht
raten. Nur `https` und ein echter Hostname sind erlaubt - dieselbe Regel, die die App auf jede
Serveradresse anwendet -, sonst startet der Server nicht.

`FEEDBACK_SERVER_URL` ist bewusst eine **Variable und kein Secret**: der Hostname steht im Manifest
jeder veroeffentlichten APK und in der `assetlinks.json` des Servers, er ist also ohnehin
oeffentlich. Er gehoert trotzdem nicht ins Repository, weil er zur Installation des Besitzers
gehoert und nicht zum Quelltext. Ist die Variable nicht gesetzt, baut die App ohne festen Anker:
die Adresse wird eingetippt wie bisher, und **jeder** Einrichtungslink wird abgelehnt - es gibt
dann nichts, womit er uebereinstimmen koennte.

**Bis die Adresse wirkt, fehlen zwei Schritte.** Der Anker steckt in der APK, nicht auf dem Server:
der Wert wird beim Build in `BuildConfig.DEFAULT_SERVER_URL` geschrieben. Eine neu gesetzte oder
geaenderte Variable aendert an einer bereits installierten App also nichts.

1. **Den Release-Workflow ausloesen.** Eine geaenderte Actions-Variable startet von sich aus keinen
   Lauf. `.github/workflows/android-release.yml` laeuft nur bei einem Push auf `main`, der etwas
   unter `android/**` oder an der Workflow-Datei selbst aendert - und bei einem manuellen Start:
   GitHub → Actions → **Android Release APK** → *Run workflow*. Wer nur die Variable gesetzt hat,
   braucht den manuellen Start. Der Releasetext des Laufs nennt die verwendete Adresse; steht dort
   "ohne feste Serveradresse", hat der Build die Variable nicht gesehen.
2. **Die neue APK auf jedem Geraet installieren.** Erst die Installation bringt den Anker auf das
   Geraet. Bis dahin traegt die dort installierte App den alten Anker - oder gar keinen - und lehnt
   den Einrichtungslink weiter ab, voellig unabhaengig davon, was der Server ausliefert. Mit
   hinterlegtem Signaturschluessel laesst sich die neue Version ueber die installierte setzen; ohne
   ihn verlangt Android eine Deinstallation, und die kostet Geraeteidentitaet und Kopplung (3.3 und
   `ANDROID_RELEASE.md`).

Die beiden Werte ergeben nur zusammen etwas. Die APK beansprucht den Host aus
`FEEDBACK_SERVER_URL`, der Server bestaetigt unter `/.well-known/assetlinks.json` den
Signaturschluessel genau dieser APK, und Android glaubt den Link erst, wenn beide Seiten dasselbe
sagen. Ein Tippfehler auf der Serverseite bricht den Start ab; ein Tippfehler auf der Buildseite
bricht den Build ab. Was keine Fehlermeldung erzeugt, ist die Kombination aus zwei je fuer sich
gueltigen, aber nicht zueinander passenden Werten - dann bleibt der Link einfach unverifiziert.
Nachsehen laesst sich das von aussen:

```bash
curl -sS -i https://<deine-domain>/.well-known/assetlinks.json
```

Kommt `404`, ist `FEEDBACK_ANDROID_CERT_SHA256` nicht gesetzt. Kommt JSON mit einem anderen
Fingerabdruck als dem der ausgelieferten APK, passen die Seiten nicht zueinander. Fehlt eine Seite,
passiert nichts Schlimmes: der Link oeffnet die Seite `/pair` im Browser, und die Adresse wird
eingetippt wie vorher.

**Ein Port in der Serveradresse macht App Links unmoeglich.** Die Verifikation holt
`https://<host>/.well-known/assetlinks.json` immer ueber Port 443. Eine Adresse wie
`https://feedback.example.com:8443` ist dort nicht erreichbar, der Link wird nie verifiziert - und
ein beanspruchter, aber unverifizierter Link ist schlechter als keiner, weil Android dann den
Auswahldialog zeigt. Der Android-Build warnt deshalb bei einem Port im Wert und beansprucht gar
keinen Host. Wer einen Port braucht, behaelt das Eintippen; alles andere funktioniert unveraendert.

**Betriebspflicht: die Domain halten.** Die Domain ist hier kein Komfort, sondern der Anker. Der
Fingerabdruck in `assetlinks.json` ist oeffentlich und aus jeder Kopie der APK berechenbar - wer
die Domain spaeter uebernimmt, legt dieselbe Datei ab und ist fuer neu installierte Geraete das
verifizierte Ziel (Threat Model 4.21). Daraus folgen zwei Pflichten:

1. **Halten.** Verlaengerung automatisch, Kontaktadresse beim Registrar erreichbar, Ablaufdatum
   notiert. Eine abgelaufene Domain ist hier kein Ausfall, sondern eine Uebergabe.
2. **Bei Verlust: widerrufen und neu ausliefern.** Alle Geraete im Control Center widerrufen, dann
   einen Build mit neuer Adresse ausliefern und die Geraete neu koppeln. Eine bestehende
   Registrierung hilft dabei **nicht**: sie haengt an der Adresse, und hinter der Adresse steht
   dann jemand anderes. Der Widerruf ist der einzige Schritt, der ein gekoppeltes Geraet von ihr
   loest.

**Widerrufspfad: die Host-Bindung wieder loesen.** `CLAUDE.md` verlangt fuer jede Funktion einen
Widerrufspfad; hier ist er, und er hat drei voneinander unabhaengige Schalter:

- **In Android, pro Geraet.** App-Info → *Standardmaessig oeffnen* → unterstuetzte Links
  abschalten bzw. *Standardeinstellungen loeschen*. Danach oeffnet `https://<deine-domain>/pair`
  wieder den Browser, und die App reagiert nicht mehr darauf. Die Beschriftung unterscheidet sich
  je nach Android-Version und Hersteller; der Ort ist immer die App-Info.
- **In Feedback selbst.** Einrichtungslinks lassen sich in der App dauerhaft abschalten. Danach
  wird auch ein zeichengleicher Link nicht mehr uebernommen, und kein Link kann die Einstellung
  wieder einschalten.
- **Im Build.** Eine APK ohne `FEEDBACK_SERVER_URL` beansprucht ueberhaupt keinen Host und lehnt
  jeden Link ab.

Keiner der drei Schalter beruehrt eine bestehende Kopplung: sie schalten nur ab, wie die Adresse
auf das Geraet kommt.

## 4. Ehrliche Grenzen / offen

- **Presence und Rate Limits liegen im Arbeitsspeicher.** Der Server ist damit
  Single-Instance. Zwei Instanzen hinter einem Load Balancer wuerden sich gegenseitig nicht
  kennen. Fuer einen Test mit eigenen Geraeten ist das unerheblich, fuer echten Betrieb nicht.
- **SQLite als Ablage.** Produktionsziel ist PostgreSQL hinter derselben Repository-Schicht.
- **Kein Container und kein Deployment-Skript.** Der Betrieb ist handgemacht. Einen
  unauthentifizierten `GET /health` gibt es, aber nichts fragt ihn ab - und wer ihn abfragt,
  erkennt damit eine Feedback-Installation (Threat Model 4.21).
- **Keine Backup-Strategie.** Die SQLite-Datei enthaelt Benutzer, Geraete und Audit-Eintraege.
- **Kein Monitoring.** Ein abgestuerzter Prozess faellt dadurch auf, dass Geraete offline gehen.
- Ein Tunnel-Hostname wechselt je nach Dienst bei jedem Start. Aendert sich die Server-URL,
  muss das Geraet neu gekoppelt werden - die Registrierung haengt an der Adresse.
- **Kein Transportweg aus 3.1 ist unter Last gemessen worden.** Weder Cloudflare-Tunnel noch
  Tailscale Funnel sind hier mit einem laufenden Bildstrom getestet worden; fuer Funnel gibt es
  ausserdem keine veroeffentlichte Bandbreitenzahl. Ob `screen.view` ueber den gewaehlten Weg
  fluessig bleibt, entscheidet sich erst im Test aus 3.5.

## 5. Abnahme

**Nicht abgenommen.** Nichts in dieser Datei ist ausgefuehrt worden:

- Der Server wurde in dieser Umgebung nie gegen ein echtes Geraet gestartet.
- Es gab noch keinen Pairing-Durchlauf mit einem Handy.
- Der Release-Workflow ist nie gelaufen (er loest erst auf `main` aus).
- Kein Schritt aus Abschnitt 3.4 oder 3.5 wurde durchgefuehrt.
- Kein Einrichtungslink wurde je auf einem Geraet geoeffnet. Ob Android die Domain verifiziert und
  ob der Link die App statt des Browsers oeffnet, zeigt erst ein Geraet mit installierter,
  fest signierter APK (3.6).
- Von der Bildschirmuebertragung ist auf echter Hardware **nichts** geprueft: weder die beiden
  Dialoge, noch die Benachrichtigung, noch der Stop, noch der Encoder.

Was verifiziert ist, ist die Schicht darunter: Server-Typecheck und Tests, Control-Web-Build und
Tests, Android-Build, Lint und Unit-Tests in CI. Dass die Teile einzeln stimmen, ist kein Beweis,
dass die Kette als Ganzes traegt. Der erste echte Durchlauf nach Abschnitt 3.4 ist dieser Beweis.
