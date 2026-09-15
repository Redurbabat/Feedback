# Feedback Control Web

Browser-Kontrollzentrale fuer gekoppelte eigene bzw. autorisierte Geraete.

## Aktueller Stand

Implementiert:

- Anmeldung ueber die serverseitige HttpOnly-Cookie-Session
- CSRF-Schutz fuer alle zustandsaendernden Browser-Anfragen
- Wiederherstellung einer bestehenden Browser-Sitzung
- Panel "Neues Geraet hinzufuegen" mit Serveradresse und QR-Code fuer `/pair`
- Pairing-Lookup ueber den sechsstelligen Code
- explizite Pairing-Bestaetigung oder Ablehnung
- Geraeteliste mit Online-/Offline-/Widerruf-Status
- serverseitige Freigabe von `system.info`
- Live-Abfrage von `system.info` ueber eine kurzlebige Remote-Session
- Geraetewiderruf
- Audit-Anzeige
- responsive Light-/Dark-Mode-Oberflaeche

Der Browser bestaetigt keine Android-Berechtigungen stellvertretend. Die effektive Freigabe einer Capability verlangt weiterhin Serverfreigabe **und** lokale Freigabe auf dem Android-Geraet.

## Entwicklung

Voraussetzung: Node.js 22 oder neuer.

```bash
cd control-web
npm install
npm run typecheck
npm run test:run
npm run dev
```

Standardmaessig wird die API unter demselben Origin erwartet (`/api/v1/...`). Fuer eine getrennte lokale/produktive Bereitstellung kann beim Build gesetzt werden:

```bash
VITE_FEEDBACK_API_BASE_URL=https://api.example.com npm run build
```

Nicht-lokale API-Basisadressen muessen HTTPS verwenden. Browser-Cookies werden nur mit `credentials: include` gesendet; Session- oder CSRF-Werte werden nicht in `localStorage` oder `sessionStorage` persistiert.

## Build

```bash
npm run build
```

Das statische Ergebnis liegt anschliessend unter `control-web/dist/` und sollte hinter HTTPS ausgeliefert werden. Die Origin muss serverseitig in der erlaubten Control-Center-Origin-Liste stehen.

## Neues Geraet hinzufuegen

Das Panel in der Seitenleiste zeigt die Adresse dieses Servers gross und in Klartext, einen
QR-Code auf `<origin>/pair` und die drei Schritte in Worten. Gescannt wird mit der normalen
Kamera-App des Handys. Feedback bringt bewusst keinen eigenen Scanner mit: die System-Kamera ist
die Flaeche, auf der der Besitzer sieht und entscheidet, worauf er zielt, und eine zusaetzliche
Kamera-Berechtigung waere der falsche Preis dafuer.

Ein so geoeffneter Link fuehrt **nie** einen neuen Server ein. Er bestaetigt nur eine Adresse, die
die App ohnehin schon kennt; jede andere lehnt sie sichtbar ab.

### Wann kein QR-Code erscheint

`location.origin` ist das, womit der Besitzer den Browser gerade geoeffnet hat - beim ersten Test
oft `http://localhost:8080`. Ein Code darauf ist im Mobilfunk nicht erreichbar, und die App lehnt
Klartext ohnehin ab. Der Weg "ohne Tippen" wuerde still brechen, und stilles Brechen ist
ausgeschlossen (Konstitution 12). Der Code erscheint deshalb nur, wenn die Seite ueber HTTPS
ausgeliefert wird und der Host ein oeffentlich aufloesbarer Name ist. Sonst steht an seiner Stelle,
warum er hier nicht hilft und was stattdessen zu tun ist.

Abgelehnt werden:

| Grund | Beispiel |
| --- | --- |
| `not-https` | `http://feedback.example.com` |
| `loopback` | `https://localhost:8443`, `https://127.0.0.1`, `https://[::1]` |
| `ip-literal` | `https://192.168.1.10`, `https://[2001:db8::1]` |
| `trailing-dot` | `https://feedback.example.com.` |
| `local-name` | `https://nas.local`, `https://feedback` |

Die Entscheidung liegt in `src/onboarding.ts` (`decideQrCode`) und ist dort getestet, nicht in der
Ansicht.

### QR-Erzeugung

`src/qr.ts` nutzt `qrcode-generator` (eine Datei, keine eigenen Abhaengigkeiten, exakt gepinnt).
Reed-Solomon, Maskenwahl und Versionswahl werden bewusst nicht selbst geschrieben - das ist Code,
der subtil falsch sein kann, ohne dass es jemandem auffaellt.

Gerendert wird als **inline `<svg>`**, nicht als `<img src="data:...">`: inline SVG ist DOM und
faellt damit nicht unter `img-src`. Der Code bringt seinen eigenen hellen Untergrund mit und folgt
nicht dem Farbschema der Seite; ein invertierter QR-Code wird von vielen Kameras nicht gelesen.

## Dateien

Die Geraeteseite enthaelt einen Bereich "Dateien". Er zeigt ausschliesslich die Bereiche, die der
Besitzer auf dem Geraet ueber Androids Dateiauswahl freigegeben hat, erlaubt Navigation in
Unterordner und Download mit Fortschritt und Abbruch.

Es gibt keine Aktion zum Loeschen, Umbenennen oder Ausfuehren - dafuer existiert nicht einmal ein
Protokollbefehl. `files.read` ist ausschliesslich lesend.

Ein Download wird gestreamt gelesen, damit Fortschritt und Abbruch funktionieren, am Ende aber im
Browserspeicher zusammengesetzt. Das Download-Limit des Servers begrenzt deshalb auch, was die
Oberflaeche verkraftet.
