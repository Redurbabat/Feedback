# Feedback Control Web

Browser-Kontrollzentrale fuer gekoppelte eigene bzw. autorisierte Geraete.

## Aktueller Stand

Implementiert:

- Anmeldung ueber die serverseitige HttpOnly-Cookie-Session
- CSRF-Schutz fuer alle zustandsaendernden Browser-Anfragen
- Wiederherstellung einer bestehenden Browser-Sitzung
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
