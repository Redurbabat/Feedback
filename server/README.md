# Feedback Server

Geplanter Control-Plane-Server fuer:

- Authentisierung des Control Centers
- kurzlebige Pairing-Tickets
- Public-Key-basierte Geraeteregistrierung
- Presence / Last-Seen
- Session-Policies und Widerruf
- WebRTC-Signaling
- Audit-Metadaten

Der Server speichert keine privaten Geraeteschluessel und soll standardmaessig keine Datei-, Foto-, Video- oder Bildschirm-Inhalte persistieren.

Die konkrete Runtime/Framework-Entscheidung wird vor Implementierung in einer ADR dokumentiert.
