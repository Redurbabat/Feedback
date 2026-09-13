# Hintergrundverbindung

Stand: 2026-09-13

## Bedienung

Nach der Kopplung gibt es auf dem Startbildschirm einen Schalter **Hintergrundverbindung**.

- **Aus** (Standard): der Agent laeuft nur, solange die App geoeffnet ist. Schliesst der Besitzer
  sie, ist das Geraet offline.
- **Ein**: ein Vordergrunddienst haelt die Verbindung, auch wenn die App geschlossen ist. Android
  zeigt dafuer eine dauerhafte Benachrichtigung mit einem **Stopp**-Knopf.

Der Schalter ist eine bewusste Entscheidung und kein Standardzustand. Auf Android 13 und neuer
fragt die App vorher die Benachrichtigungsberechtigung ab - ohne sichtbare Benachrichtigung soll
es keine Hintergrundverbindung geben.

## Architektur und Schutz

- `BackgroundAgentService` ist ein Foreground Service vom Typ `specialUse`. Er ruft
  `startForeground` sofort beim Start auf, bevor irgendetwas anderes passiert.
- Der Benachrichtigungskanal hat `IMPORTANCE_LOW`: sichtbar, aber ohne Ton. Sichtbarkeit ist
  Pflicht, Aufdringlichkeit nicht.
- Der Text der Benachrichtigung folgt dem tatsaechlichen Zustand (verbinde, online, offline).
  Er behauptet nie eine aktive Sitzung, wenn keine laeuft.
- `START_STICKY` gilt **nur**, solange der Besitzer die Hintergrundverbindung eingeschaltet hat.
  Hat er sie ausgeschaltet, antwortet der Dienst mit `START_NOT_STICKY` und kommt nach einem Kill
  nicht zurueck.
- Reconnect mit exponentiellem Backoff und Jitter, gedeckelt. Kein Polling.
- Ein Widerruf des Geraets beendet den Dienst.

## Was hier bewusst NICHT gemacht wird

Das Schwesterprojekt `Redurbabat/instagram-monitor` loest dasselbe Problem anders. Dessen
Loesungen sind hier ausdruecklich abgelehnt, und es lohnt sich, den Unterschied zu benennen:

| Dort | Hier | Grund |
| --- | --- | --- |
| dauerhafter `PARTIAL_WAKE_LOCK` | keiner | Konstitution Punkt 8: keine Dauer-Wake-Locks ohne konkrete aktive Aufgabe |
| `START_STICKY` immer | nur bei eingeschaltetem Schalter | ein Dienst, der ohne Auftrag wiederkommt, ist versteckte Persistenz (Punkt 7) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | nicht angefordert | das ist eine Umgehung der Energiesparmechanik, nicht ihre Nutzung |
| `MANAGE_EXTERNAL_STORAGE` | nie | Storage Access Framework und Photo Picker reichen |
| `usesCleartextTraffic="true"` | `false` | es gibt kein vertrauenswuerdiges Netz (Punkt 13) |

Uebernommen wurde von dort dagegen das Handwerk: `startForeground` als allererste Handlung,
`IMPORTANCE_LOW`, und - aus einem Kommentar im dortigen Code - die Lehre, dass ein
Benachrichtigungstext, der eine Taetigkeit behauptet, die gerade nicht laeuft, den Nutzer belügt.

## Ehrliche Grenzen / offen

- **Doze und herstellerspezifische Killer sind nicht besiegt.** Ein Geraet kann den Dienst
  beenden; Feedback versucht nicht, das zu unterlaufen. Es zeigt dann offline an, was zutrifft.
- Der Foreground-Service-Typ `specialUse` braucht fuer eine Store-Veroeffentlichung eine
  Begruendung gegenueber Google. Ungeprueft.
- Der Akkuverbrauch im Leerlauf mit gehaltener Verbindung ist nicht gemessen.
- Kein Push als Alternative zur Dauerverbindung. Push waere sparsamer und ist offen.

## Abnahme

**Nicht abgenommen auf Hardware.** Die Zustandslogik und die Backoff-Berechnung sind unit-getestet
und in CI gruen. Nicht geprueft: Verhalten unter Doze, nach dem Entfernen aus den Recents, bei
Netzwechsel, und der reale Akkuverbrauch. Siehe `docs/testing/ANDROID_DEVICE_MATRIX.md`.
