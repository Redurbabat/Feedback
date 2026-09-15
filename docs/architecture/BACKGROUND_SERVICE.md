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
- **Nach einem Neustart des Geraets und nach einem App-Update kommt die Verbindung von selbst
  zurueck** (`BootReceiver`). `START_STICKY` deckt nur ab, dass das System den Dienst beendet,
  nicht dass es herunterfaehrt: vorher musste der Besitzer die App nach jedem Neustart einmal
  oeffnen, und nichts sagte das - das Geraet stand einfach offline im Control Center.

### Warum der Boot-Empfaenger nicht die versteckte Persistenz ist, die unten abgelehnt wird

Der Unterschied liegt in der Richtung, und er ist in `BootResumePolicy` als einzige Regel
festgeschrieben: **ein Broadcast darf fortsetzen, was der Besitzer eingeschaltet hat, und niemals
etwas einschalten.** Ist der Schalter aus, passiert nichts. Ohne diese Regel waere ein Neustart ein
Weg, sich eine Hintergrundverbindung zu verschaffen, die nie erteilt wurde.

Dazu drei Einzelheiten, die jede fuer sich eine stille Fehlfunktion gewesen waeren:

- `RECEIVE_BOOT_COMPLETED` ist die einzige neue Berechtigung. Sie gibt keinen Zugriff auf Daten.
  Der Widerrufspfad ist derselbe Schalter wie bisher: aus heisst aus, auch ueber Neustarts.
- Der Empfaenger ist **nicht** `directBootAware`. `BOOT_COMPLETED` kommt damit erst, wenn der
  Besitzer entsperrt hat - und erst dann ist die versiegelte Registrierung ueberhaupt lesbar.
  Frueher zu starten hiesse, sie nicht lesen zu koennen und den Schalter grundlos auszuschalten.
- Laesst sich die Benachrichtigung nicht mehr zeigen (auf Android 13+ zurueckgezogene
  Berechtigung), wird der Schalter ausgeschaltet statt unsichtbar weiterzulaufen. Eine
  Hintergrundverbindung, die der Besitzer nicht in der Statusleiste sieht, ist genau die
  versteckte Fernsteuerung, die dieses Projekt nicht baut.

Einen Foreground Service aus dem Hintergrund zu starten ist sonst verboten; `BOOT_COMPLETED` und
`MY_PACKAGE_REPLACED` sind benannte Ausnahmen. Deshalb ein Empfaenger und kein geplanter Job.

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

**Nicht abgenommen auf Hardware.** Die Zustandslogik, die Backoff-Berechnung und die Regel des
Boot-Empfaengers sind unit-getestet und in CI gruen. Nicht geprueft: Verhalten unter Doze, nach dem
Entfernen aus den Recents, bei Netzwechsel, und der reale Akkuverbrauch. **Ebenfalls ungeprueft:
ob der Dienst auf echter Hardware nach einem Neustart wirklich wiederkommt.** Getestet ist die
Entscheidung, nicht ihre Ausfuehrung - manche Hersteller-Oberflaechen halten Autostart zusaetzlich
zurueck, und das zeigt erst ein Geraet. Siehe `docs/testing/ANDROID_DEVICE_MATRIX.md`.
