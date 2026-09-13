# Android Device Test Matrix

Status: **NOT TESTED ON PHYSICAL DEVICE**

Diese Datei ist der Testplan fuer physische Android-Geraete. Kein Eintrag darf abgehakt werden,
solange der Test nicht wirklich auf echter Hardware gelaufen ist. Emulatorergebnisse werden als
`Emulator` gekennzeichnet und ersetzen keinen Geraetetest.

Die automatisierten Unit-Tests und der CI-Build decken Logik, Kodierung und Policy ab. Sie decken
**nicht** ab: Keystore-Verhalten echter Hardware, Doze, Herstellertreiber, Akkuverbrauch,
Foreground-Service-Policies und Berechtigungsdialoge.

## Zielversionen

`minSdk` ist derzeit 24. Die Matrix beginnt trotzdem bei Android 8, weil Android 7 keinen
verbreiteten Testbestand mehr hat; Android 7 wird als "unverifiziert lauffaehig" gefuehrt.

| Android | API | Geraet | Status |
| --- | --- | --- | --- |
| 8 | 26 | offen | NOT TESTED |
| 10 | 29 | offen | NOT TESTED |
| 12 | 31 | offen | NOT TESTED |
| 13 | 33 | offen | NOT TESTED |
| 14 | 34 | offen | NOT TESTED |
| 15 | 35 | offen | NOT TESTED |
| 16 | 36 | offen | NOT TESTED |

## Testpunkte pro Geraet

### Installation und Identitaet

1. Installation der Debug-APK.
2. Erststart ohne Absturz.
3. Geraeteidentitaet wird im Android Keystore erzeugt.
4. Device-ID und Fingerprint bleiben nach Neustart identisch.
5. Deinstallation entfernt den Keystore-Schluessel (Kopplung muss danach neu erfolgen).

### App-Lock

6. Setup-Bildschirm erscheint beim Erststart.
7. PIN unter 6 Stellen wird abgelehnt.
8. Passphrase unter 8 Zeichen wird abgelehnt.
9. Abweichende Wiederholung wird abgelehnt.
10. Korrekte Eingabe entsperrt.
11. Falsche Eingabe zeigt die verbleibenden freien Versuche.
12. Ab dem 5. Fehlversuch erscheint die Wartezeit mit laufendem Countdown.
13. App vollstaendig beenden und neu starten: Wartezeit besteht weiter.
14. Geraeteneustart waehrend der Wartezeit: Wartezeit besteht weiter.
15. Systemzeit zurueckstellen: Wartezeit wird nicht verkuerzt.
16. Auto-Lock `Sofort`, `30 Sekunden`, `1 Minute`, `5 Minuten`, `Nie` einzeln pruefen.
17. Recents-Ansicht und Bildschirm aus loesen den Auto-Lock-Zaehler aus.
18. Nach Auto-Lock ist kein Geraeteinhalt mehr sichtbar.
19. Biometrie-Schalter nur aktivierbar, wenn das Geraet einen `BIOMETRIC_STRONG`-Sensor
    eingerichtet hat. Auf Android 8 muss der Schalter deaktiviert bleiben: die Funktion ist
    bewusst auf Android 9 und neuer begrenzt.
20. Biometrische Entsperrung funktioniert und ist waehrend einer Wartezeit blockiert.
21. App-Schutz deaktivieren verlangt die korrekte Eingabe.
22. Lokale Kopplung entfernen verlangt nach Ablauf des Re-Auth-Fensters erneut die Eingabe.
23. `system.info` freigeben verlangt Re-Authentisierung, entziehen nicht.

### Pairing und Hintergrund

24. Pairing gegen einen erreichbaren Server.
25. Ablauf eines Pairing-Codes nach 5 Minuten.
26. Benachrichtigungsberechtigung auf Android 13+.
27. Hintergrundverbindung aktivieren: dauerhafte Benachrichtigung sichtbar.
28. Beenden-Aktion in der Benachrichtigung stoppt den Dienst.
29. Kein Autostart nach Geraeteneustart.
30. Reconnect nach WLAN-Verlust.
31. Wechsel WLAN zu Mobilfunk.
32. Verhalten in Doze (Geraet laenger unbenutzt liegen lassen).
33. App aus den Recents entfernt: dokumentiertes Verhalten pruefen.
34. Widerruf im Control Center beendet die Verbindung.

### Verbrauch

35. Akkuverbrauch ueber 12 Stunden Idle mit aktiver Hintergrundverbindung notieren.
36. Geraetetemperatur bei Dauerverbindung notieren.

### Spaeter (noch nicht implementiert)

37. MediaProjection-Systemdialog und sichtbare Sitzungsanzeige.
38. Lokaler Stop einer Bildschirmfreigabe.
39. AccessibilityService-Aktivierung durch den Nutzer.

## Ergebnisprotokoll

Pro Durchlauf festhalten: Geraet, Android-Version, Build-Nummer der APK, Datum, Testpunkt,
Ergebnis, Auffaelligkeiten. Fehlgeschlagene Punkte bekommen ein Issue, keinen stillen Workaround.
