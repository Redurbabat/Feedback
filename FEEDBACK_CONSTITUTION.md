# Feedback Engineering Constitution

Diese Regeln gelten fuer jede Aenderung am Repository.

## 1. Sicherheit vor Komfort

Remote-Funktionen werden nie so gebaut, dass sie Sicherheitsanzeigen, Berechtigungsdialoge oder Betriebssystemgrenzen umgehen. Eine Funktion, die nur durch Umgehung funktioniert, wird nicht implementiert.

## 2. Consent first

Ein Geraet wird nur nach expliziter Kopplung registriert. Hochprivilegierte Faehigkeiten werden separat freigegeben. Pairing allein ist kein Vollzugriff.

## 3. Sichtbare Sitzungen

Aktive Bildschirmfreigabe oder Fernsteuerung muss lokal sichtbar und jederzeit lokal beendbar sein.

## 4. Kryptografische Geraeteidentitaet

Jedes Geraet besitzt ein eigenes Schluesselpaar. Private Schluessel werden im nativen sicheren Speicher gehalten und niemals an Server oder Control Center uebertragen.

## 5. Least privilege

Berechtigungen werden minimal und capability-basiert vergeben. Beispiele:

- `system.info`
- `files.read`
- `media.photos.read`
- `media.videos.read`
- `screen.view`
- `screen.control`
- `clipboard.read`
- `clipboard.write`

Neue Capabilities brauchen Dokumentation, Bedrohungsanalyse und Widerrufspfad.

## 6. Keine Secrets im Repository

Keine API-Keys, Passwoerter, Tokens, Signing-Passwoerter, privaten Schluessel oder Keystores committen. CI liest Secrets ausschliesslich aus dem Secret Store.

## 7. Keine versteckte Persistenz

Hintergrundbetrieb darf nur ueber dokumentierte Betriebssystemmechanismen erfolgen. Foreground Services und sichtbare Notifications werden verwendet, wenn Android sie verlangt.

## 8. Keine unnötigen Dauerverbindungen

Idle-Betrieb soll ressourcenschonend sein. Keine aggressiven Polling-Loops oder dauerhafte Wake-Locks ohne konkrete aktive Aufgabe.

## 9. Saubere Trennung

UI, Pairing, Kryptografie, Netzwerk, Dateizugriff, Bildschirm und Remote-Input bleiben getrennte Module. Das Control Center darf native Geraeterechte nicht direkt simulieren oder umgehen.

## 10. Auditierbarkeit

Sicherheitsrelevante Ereignisse werden lokal bzw. serverseitig nachvollziehbar protokolliert, ohne sensible Inhalte oder Secrets zu loggen.

## 11. Build und Tests sind Teil der Umsetzung

Eine Phase ist erst fertig, wenn Build und relevante Tests erfolgreich sind. Sicherheitskritische Protokoll- und Pairinglogik bekommt automatisierte Tests.

## 12. Kein stilles Downgrade

Fehlt eine sichere API oder Unterstuetzung auf einem alten Geraet, wird die Funktion als nicht verfuegbar angezeigt. Es gibt keinen unsicheren Fallback.

## 13. Internet-first

Feedback verbindet Geraete ueber das Internet, nicht ueber ein gemeinsames Netzwerk. Kein Feature
darf voraussetzen, dass Control Center und Zielgeraet im selben LAN oder WLAN sind.

- Geraet und Control Center haben keine Verbindung zueinander. Beide bauen jeweils eine ausgehende
  TLS-Verbindung zum Control Server auf. Damit funktioniert Feedback ueber Mobilfunk, hinter NAT
  und ohne Portfreigabe.
- Keine Netzwerk-Discovery: kein mDNS/NSD, kein Subnetz-Scan, keine Annahme ueber private
  IP-Bereiche, keine Kopplung ueber "beide im selben WLAN".
- Eine direkte Peer-Verbindung ist eine Optimierung der
  Latenz, niemals die Voraussetzung der Funktion. Kommt sie nicht zustande, muss ein Relay
  uebernehmen. Ein Feature, das nur im selben Netz funktioniert, gilt als nicht implementiert.
  In v1 gibt es deshalb gar keine Peer-Strecke: auch der Bildschirmstrom laeuft ueber den Control
  Server (ADR-004).
- Cleartext-Abkuerzungen "weil es ja nur das Heimnetz ist" sind ausgeschlossen. Es gibt kein
  vertrauenswuerdiges Netz.
