# Threat Model

Stand: 2026-09-13. Gilt fuer Protokoll v1 mit den vier implementierten Lesefaehigkeiten
`system.info`, `files.read`, `media.photos.read` und `media.videos.read`.

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
2. **Freigegebene Inhalte** - Dateien, Fotos, Videos.
3. **`deviceToken` und Pairing-Geheimnisse.** Wer sie hat, spricht als das Geraet bzw. kann eine
   offene Kopplung uebernehmen.
4. **Die Control-Center-Sitzung.** Wer sie hat, kann Capabilities erteilen.
5. **Die App-Lock-Geheimzahl** und der lokale Fehlversuchszaehler.
6. **Audit-Metadaten.**

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
mit echtem Hostnamen - keine IP, kein Klartext, kein selbstsigniertes Zertifikat; das Manifest
setzt `usesCleartextTraffic="false"`.
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
**Residual** Eine vorgestellte Systemuhr kann die Wartezeit verkuerzen; ein vertrauenswuerdiger
lokaler Zeitgeber steht nicht zur Verfuegung. Der Fehlversuchszaehler liegt im privaten
App-Speicher und schuetzt nicht gegen Root. Biometrie ist eine Komfortschicht vor einer bereits
per PIN etablierten Sitzung, kein zweiter kryptografischer Faktor.

### 4.9 Gerootetes oder kompromittiertes Geraet

**Impact** total. **Likelihood** niedrig.
**Mitigation** Keine wirksame. Der Keystore erschwert den Export des privaten Schluessels, je
nach Hardware bis hin zu "praktisch unmoeglich", aber die App-Schicht darueber ist umgehbar.
**Residual** Vollstaendig. Feedback **behauptet hier ausdruecklich keinen Schutz**. Eine
Root-Erkennung waere ein Signal, keine Grenze, und wird nicht als Sicherheitsmerkmal verkauft.

### 4.10 Manipulierte APK

**Impact** hoch. **Likelihood** niedrig.
**Mitigation** Android laesst ein Update nur mit derselben Signatur zu. Der Signaturschluessel
liegt ausschliesslich im GitHub Secret Store, nie im Repository, und der Fingerabdruck wird im
Build protokolliert, damit ein unbeabsichtigter Wechsel auffaellt.
**Residual** Wer eine fremd signierte APK **neu** installiert, hat eine andere App - mit einer
anderen Identitaet und ohne Kopplung. Solange kein fester Schluessel hinterlegt ist, wechselt die
Signatur zwischen Builds, was Nutzer an Deinstallieren gewoehnt - und genau diese Gewohnheit ist
der Angriffsweg. Deshalb steht das Einrichten des Schluessels in `ANDROID_RELEASE.md` so weit oben.

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
5. Kein Pinning der Server-Identitaet an den beim Pairing gesehenen Schluessel (TOFU waere moeglich).

## 7. Was vor Milestone 5 zu ergaenzen ist

`screen.view` verschiebt die Lage deutlich: eine Bildschirmuebertragung zeigt alles, was auf dem
Geraet passiert, auch Inhalte anderer Apps, Benachrichtigungen und Eingaben. Bevor sie
implementiert wird, gehoert hier hinein:

- Wer den Medienstrom sehen kann, wenn ein Relay (TURN) im Weg liegt, und was er nicht speichert.
- Ob die Strecke Ende-zu-Ende verschluesselt ist oder am Relay aufgemacht wird - und wenn ja,
  warum das akzeptabel sein soll.
- Der Angriff "Sitzung laeuft weiter, ohne dass der Besitzer es merkt": die Notification ist die
  Gegenmassnahme, und ihre Unumgehbarkeit ist zu pruefen statt anzunehmen.
- Was passiert, wenn die Verbindung abbricht, waehrend die MediaProjection noch laeuft.

## 8. Abnahme

**Nicht abgenommen.** Dieses Dokument beschreibt den Code, wie er ist, aber:

- Keine der Massnahmen wurde auf echter Hardware gegen einen echten Angriff geprueft.
- Es gab kein Fuzzing der Protokollparser und kein Dependency-Audit (Milestone 7).
- Die Wahrscheinlichkeitsangaben sind begruendete Einschaetzungen, keine Messungen.
