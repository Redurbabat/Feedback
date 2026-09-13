# Pairing

Stand: 2026-09-13. Wire-Format und Endpunkte stehen in `protocol/PROTOCOL.md` Abschnitt 5 und 6;
die Begruendung des Modells in `docs/decisions/ADR-002-pairing-model.md`. Hier steht, wie es
ablaeuft und was dabei geprueft wird.

## Bedienung

1. Auf dem Handy **Gerät verbinden** antippen. Es erscheinen ein sechsstelliger Code und ein
   QR-Code, beide fuenf Minuten gueltig, mit sichtbarem Countdown.
2. Im Control Center **/pair** oeffnen und den Code eingeben - oder den Link aus dem QR-Code
   verwenden.
3. Das Control Center zeigt, **welches** Geraet fragt: Name, Plattform, Android-Version,
   Fingerprint und Zeitpunkt.
4. **Gerät koppeln** oder **Ablehnen**.
5. Das Handy holt danach einmalig sein Geraete-Token ab und ist gekoppelt.

Kopplung erteilt **Basisvertrauen, keinen Zugriff**. Danach ist keine einzige Capability
freigegeben; jede wird auf beiden Seiten getrennt erteilt.

## Architektur und Schutz

```text
Android                     Server                      Control Web
   |-- pairing/start ------->|  Signatur, Ableitung, Nonce, Zeitfenster
   |<-- ticket, secret, code-|
   |                         |<-- pairing/lookup --------|  ticket ODER code
   |                         |--- Pending-Metadaten ---->|
   |                         |<-- pairing/{id}/approve --|  derselbe Proof
   |-- pairing/{id}/status ->|
   |-- pairing/{id}/claim -->|  einmalig, signiert
   |<-- deviceToken ---------|
```

Was der Server bei `start` prueft, bevor irgendetwas gespeichert wird:

- Die **Signatur** ueber den kanonischen Payload, gegen den mitgeschickten oeffentlichen Schluessel.
- Dass `deviceId` und `fingerprint` wirklich **aus diesem Schluessel abgeleitet** sind. Ein Name
  ist ein Metadatum; eine Identitaet ist er nicht.
- Dass der Schluessel ein gueltiger EC-P-256-SPKI ist.
- Dass `issuedAt` im `CLOCK_SKEW_MS`-Fenster liegt.
- Dass die **Nonce** nicht schon einmal da war.
- Rate Limits pro IP und pro Geraet.

Weitere Eigenschaften:

- `ticket` und `deviceSecret` werden nur als SHA-256 gespeichert und erscheinen nie im Log.
- `approve` und `reject` verlangen **denselben Proof** wie `lookup`. Eine `pairingId` allein ist
  keine Autorisierung - sonst koennte ein angemeldeter Benutzer fremde Kopplungen bestaetigen.
- Fehlversuche zaehlen **pro Pairing-Session**; ab fuenf ist sie verbrannt. Das ist die eigentliche
  Bremse gegen Raten, nicht die Codelaenge.
- Ein Lookup unterscheidet nicht zwischen "unbekannt" und "abgelaufen".
- `claim` ist genau einmal moeglich. Danach ist die Session `consumed`.
- Erneutes Pairing eines bekannten Geraets aktualisiert den Datensatz, hebt `revokedAt` auf und
  invalidiert alle alten Tokens. Ein abweichender Public Key fuer eine bekannte `deviceId` wird
  abgelehnt.

## Ehrliche Grenzen / offen

- **Der QR-Code traegt das Ticket.** Wer den Bildschirm waehrend des Pairings sieht, sieht es mit.
  Die Bestaetigung im Control Center ist die Gegenmassnahme, kein Ersatz (Threat Model 4.2).
- **Kein QR-Scanner auf dem Geraet.** Android rendert den Code, liest aber keinen. Ein Scanner
  braeuchte die Kamera-Berechtigung und wird erst mit dokumentiertem Bedarf gebaut.
- **Geht die `claim`-Antwort verloren, ist die Kopplung verbraucht** und muss neu gestartet
  werden. Der Preis fuer echte Einmalverwendung.
- **Das Geraet prueft die Identitaet des Servers nicht ueber TLS hinaus.** Kein Pinning, kein TOFU.
- Die Registrierung haengt an der Server-Adresse. Aendert sie sich, ist neu zu koppeln.

## Abnahme

**Nicht gegen ein echtes Geraet abgenommen.** Serverseitig ist der gesamte Ablauf getestet:
gueltiger Durchlauf, abgelaufenes Ticket, doppelter Claim, wiederholte Nonce, falsche Signatur,
falsche `deviceId`, zu viele Lookup-Versuche, Rate Limit, fremder Besitzer. Auf der Geraeteseite
sind die kanonischen Payloads und der Koordinator gegen einen Fake-Client unit-getestet.

Nie durchlaufen: ein Mensch, ein Handy, ein Browser, ein echter Server. Siehe
`docs/deployment/BETRIEB.md` Abschnitt 3.4.
