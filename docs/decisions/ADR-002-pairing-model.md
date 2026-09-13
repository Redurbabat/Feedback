# ADR-002: Pairing-Modell

Status: angenommen · Datum: 2026-09-13

## Kontext

Ein Geraet muss einem Konto zugeordnet werden, ohne dass ein Dritter sich dazwischenschieben kann.
Der bequeme Weg - ein sechsstelliger Code - hat rund 20 Bit Entropie. Das reicht nicht als
alleinige Absicherung.

## Entscheidung

**Zwei getrennte Geheimnisse mit je 32 Byte, und der sechsstellige Code ist ausdruecklich keines
davon.**

| Geheimnis | Wer haelt es | Wofuer |
| --- | --- | --- |
| `ticket` | Geraet zeigt es im QR-Code | Der Browser identifiziert **diese** Kopplung |
| `deviceSecret` | nur das Geraet | Statusabfrage und einmaliges Abholen des `deviceToken` |
| `displayCode` | Anzeige auf dem Geraet | reiner Lookup-Komfort |

Dazu: fuenf Minuten TTL, Einmalverwendung, signierter Start (`feedback-pairing-start-v1`) und
signierter Abschluss (`feedback-pairing-claim-v1`), Nonce gegen Replay, Versuchszaehler pro
Pairing-Session, und eine ausdrueckliche Bestaetigung im Control Center.

## Begruendung

- **Der Code ist Komfort, nicht Sicherheit.** Wer ihn errät, sieht eine Bestaetigungsseite mit
  Geraetename, Plattform und Fingerprint - und muss einen Menschen dazu bringen, "koppeln" zu
  druecken. Der Zaehler pro Session macht Raten zusaetzlich teuer.
- **Zwei Geheimnisse statt einem**, weil sie zwei verschiedene Dinge beweisen: das Ticket beweist
  "ich sehe den Bildschirm dieses Geraets", der `deviceSecret` beweist "ich bin dieses Geraet".
  Ein einziges Geheimnis fuer beides haette bedeutet, dass der Browser ein Geheimnis kennt, mit
  dem er als das Geraet auftreten koennte.
- **Signiert, nicht nur uebertragen**: der Server prueft, dass `deviceId` und `fingerprint`
  wirklich aus dem mitgeschickten Public Key abgeleitet sind. Ein Geraetename ist Metadatum.
- **Bestaetigung durch einen Menschen**, weil Kopplung Vertrauen begruendet. Automatisch waere
  bequemer und genau deshalb falsch.

## Alternativen

- **Nur QR, kein Zahlencode**: sicherer, aber unbrauchbar, wenn die Kamera fehlt oder der
  Bildschirm schlecht lesbar ist. Der Code ist der Rueckfallweg, nicht der Hauptweg.
- **Laengerer Zahlencode**: haette den Komfortgewinn aufgehoben, ohne den Bestaetigungsschritt zu
  ersetzen.
- **Ein Geheimnis fuer beide Seiten**: einfacher, siehe oben.

## Konsequenzen

- Ein verlorener `claim`-Antwortweg kostet die Kopplung: sie ist danach `consumed` und muss neu
  gestartet werden. Das ist der Preis fuer echte Einmalverwendung.
- Der QR-Code enthaelt das Ticket. Wer den Bildschirm waehrend des Pairings sieht, sieht es mit -
  die Bestaetigung im Control Center ist die Gegenmassnahme (Threat Model 4.2).
- Ein erneutes Pairing eines bekannten Geraets aktualisiert den Datensatz und invalidiert alle
  alten Tokens. Ein abweichender Public Key fuer eine bekannte `deviceId` wird abgelehnt.
