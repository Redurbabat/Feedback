# Betrieb und erster echter Test

Stand: 2026-09-13

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

### 3.1 Schnellster Weg zum ersten Test: Tunnel

Ohne Server mieten, in wenigen Minuten. Ein Tunneldienst (z. B. Cloudflare Tunnel) gibt einen
oeffentlichen HTTPS-Hostnamen, der auf den lokal laufenden Prozess zeigt.

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

Der Reverse Proxy bzw. Tunnel leitet `/api/*` an den Server und alles andere auf
`control-web/dist/`. Liegt das Control Center aus gutem Grund auf einer anderen Origin, wird es
mit `VITE_FEEDBACK_API_BASE_URL=https://<api-host>` gebaut, und diese Origin muss in
`FEEDBACK_ALLOWED_ORIGINS` stehen.

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
2. App auf dem Handy oeffnen, App-Sperre einrichten, Server-URL eintragen, "Geraet verbinden".
3. Sechsstelligen Code im Control Center eingeben (oder den QR-Code-Link verwenden).
4. Kopplung bestaetigen. Das Geraet sollte in Sekunden als online erscheinen.
5. Auf dem Handy "Systeminformationen" freigeben, im Control Center dieselbe Capability
   freigeben, dann dort abfragen. Nur wenn **beide** Seiten freigegeben haben, kommt eine Antwort.
6. Zum Gegentest: eine Seite entziehen und erneut abfragen. Es muss eine klare Ablehnung kommen,
   keine alten Daten.
7. Mobilfunk statt WLAN einschalten. Die Verbindung muss genauso funktionieren - das ist der
   Test fuer Punkt 13.
8. Geraet im Control Center widerrufen. Die App muss das bemerken und in den Zustand `REVOKED`
   gehen.

## 4. Ehrliche Grenzen / offen

- **Presence und Rate Limits liegen im Arbeitsspeicher.** Der Server ist damit
  Single-Instance. Zwei Instanzen hinter einem Load Balancer wuerden sich gegenseitig nicht
  kennen. Fuer einen Test mit eigenen Geraeten ist das unerheblich, fuer echten Betrieb nicht.
- **SQLite als Ablage.** Produktionsziel ist PostgreSQL hinter derselben Repository-Schicht.
- **Kein Container, kein Deployment-Skript, kein Healthcheck-Endpunkt.** Der Betrieb ist
  handgemacht.
- **Keine Backup-Strategie.** Die SQLite-Datei enthaelt Benutzer, Geraete und Audit-Eintraege.
- **Kein Monitoring.** Ein abgestuerzter Prozess faellt dadurch auf, dass Geraete offline gehen.
- Ein Tunnel-Hostname wechselt je nach Dienst bei jedem Start. Aendert sich die Server-URL,
  muss das Geraet neu gekoppelt werden - die Registrierung haengt an der Adresse.

## 5. Abnahme

**Nicht abgenommen.** Nichts in dieser Datei ist ausgefuehrt worden:

- Der Server wurde in dieser Umgebung nie gegen ein echtes Geraet gestartet.
- Es gab noch keinen Pairing-Durchlauf mit einem Handy.
- Der Release-Workflow ist nie gelaufen (er loest erst auf `main` aus).
- Kein Schritt aus Abschnitt 3.4 wurde durchgefuehrt.

Was verifiziert ist, ist die Schicht darunter: Server-Typecheck und Tests, Control-Web-Build und
Tests, Android-Build, Lint und Unit-Tests in CI. Dass die Teile einzeln stimmen, ist kein Beweis,
dass die Kette als Ganzes traegt. Der erste echte Durchlauf nach Abschnitt 3.4 ist dieser Beweis.
