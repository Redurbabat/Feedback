# Feedback

Feedback ist eine eigenstaendige, native Geraete-App fuer die Verwaltung eigener, ausdruecklich gekoppelter Geraete.

## Zielbild

- Native Android-App in Kotlin/Jetpack Compose
- spaeter Windows-Agent
- Control Center im Browser
- sichere Geraetekopplung mit kurzlebigem Code/QR
- geraeteeigene Schluessel im Android Keystore
- granulare Berechtigungen pro Funktion
- sichtbare aktive Remote-Sitzungen
- Dateien, Fotos/Videos und Systeminformationen nur nach Freigabe
- Bildschirmfreigabe und Fernsteuerung nur ueber die vom Betriebssystem vorgesehenen Berechtigungen

## Repository-Struktur

```text
Feedback/
├── android/       Native Android-App
├── server/        Auth, Pairing, Signaling und Sessions
├── control-web/   Web-Control-Center
├── protocol/      Gemeinsames Geraeteprotokoll
├── docs/          Vision, Architektur, Sicherheit und Roadmap
├── AI_START_HERE.md
├── FEEDBACK_CONSTITUTION.md
├── AGENTS.md
└── CLAUDE.md
```

## Sicherheitsgrundsaetze

Feedback ist fuer eigene bzw. autorisierte Geraete gedacht. Keine versteckte Ueberwachung, keine Umgehung von Android-Sicherheitsdialogen und keine heimliche Kamera-/Mikrofonaktivierung. Kopplung allein erteilt keinen Vollzugriff; Faehigkeiten werden getrennt freigegeben und koennen widerrufen werden.

## Status

Phase 0: Repository- und Architekturgrundlage.
