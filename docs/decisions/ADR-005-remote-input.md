# ADR-005: Plattformgrenzen von `screen.control`

Status: **vorgeschlagen** · Datum: 2026-09-13

Dieser ADR entscheidet nichts, was schon gebaut waere. Er beantwortet die Frage, die Roadmap
Phase 5 an den Anfang stellt - *was ist auf Android ueberhaupt moeglich* - und legt die Bedingungen
fest, unter denen `screen.control` gebaut werden duerfte. Die Entscheidung, ob es gebaut wird,
gehoert dem Besitzer des Projekts.

## Kontext

Bis `screen.view` kann Feedback **lesen und zusehen**. `screen.control` waere die erste Faehigkeit,
die **handelt**. Das ist keine Erweiterung des Bildstroms um einen Rueckkanal, sondern die Grenze
zwischen Zusehen und Bedienen eines fremden Telefons.

Die Konstitution ist an drei Stellen einschlaegig:

- Punkt 3: eine Fernsteuerung muss lokal sichtbar und jederzeit lokal beendbar sein.
- Punkt 5: neue Capabilities brauchen Dokumentation, Bedrohungsanalyse und Widerrufspfad.
- Die harten Regeln verbieten versteckte Fernsteuerung, das Umgehen von Android-Berechtigungen und
  beliebige Codeausfuehrung.

## Was Android tatsaechlich hergibt

| Weg | Verfuegbar? | Bewertung |
| --- | --- | --- |
| **AccessibilityService** mit `dispatchGesture` und `performGlobalAction` | ja, ab API 24 | der **einzige** allgemeine Weg auf unveraendertem Android |
| `INJECT_EVENTS` | nein | Signaturberechtigung, nur fuer Systemanwendungen |
| MediaProjection | nein | nimmt auf, sendet nichts |
| OEM-Erweiterungen (Knox und Vergleichbares) | nein | herstellersigniert, pro Geraet, nicht allgemein herstellbar |
| `adb shell input` ueber drahtloses Debugging | technisch ja | **ausgeschlossen**: das ist eine Shell, und die verbieten die harten Regeln |
| Root | nein | ausserhalb des Schutzumfangs (Threat Model 4.9) |

Damit steht die unbequeme Antwort am Anfang: **ohne AccessibilityService gibt es keinen
Remote-Input**, und dieser Dienst ist von Haus aus maechtiger als die Faehigkeit, der er dienen
soll. Android sagt dem Nutzer beim Aktivieren genau das, in eigenen Worten: vollstaendige Kontrolle
ueber das Geraet, Bildschirminhalte sehen und Aktionen ausfuehren.

## Die eine Einschraenkung, die erzwungen und nicht nur versprochen ist

Ein AccessibilityService wird ueber `AccessibilityServiceInfo` konfiguriert, und diese
Konfiguration steht in der XML-Datei eines signierten APK - nicht im Laufzeitverhalten, das man
spaeter still aendern koennte.

Wird der Dienst **ohne** `canRetrieveWindowContent` und ohne
`FLAG_RETRIEVE_INTERACTIVE_WINDOWS` deklariert, sondern ausschliesslich mit
`CAPABILITY_CAN_PERFORM_GESTURES`, dann gilt:

- Er kann tippen, wischen und die drei globalen Aktionen (Zurueck, Startbildschirm, Uebersicht).
- Er kann **keine** Fensterinhalte lesen. Kein Text, keine Knopfbeschriftungen, keine
  Benachrichtigungsinhalte, keine Eingabefelder.
- Und daraus folgt etwas Wichtiges: **Texteingabe ist damit unmoeglich.** Text setzt man ueber
  `ACTION_SET_TEXT` auf einem Knoten, und einen Knoten bekommt man nur mit Inhaltszugriff. Die
  Einschraenkung ist also nicht "wir tippen keine Passwoerter ein", sondern "wir koennen es nicht".

Das ist der einzige Punkt dieser Analyse, an dem eine Zusage von der Plattform statt von unserem
Wohlverhalten getragen wird. Alles andere unten ist Sorgfalt, und Sorgfalt laesst sich aendern.

## Entscheidung (Bedingungen, keine Freigabe)

Falls `screen.control` gebaut wird, dann ausschliesslich so:

1. **Eigener AccessibilityService, nur Gesten.** Ohne Inhaltszugriff, wie oben. Ein spaeteres
   Erweitern der XML ist eine Protokoll- und Sicherheitsaenderung, kein Detail.
2. **Kein Eingabekanal ohne laufenden Bildstrom.** Wer nicht sieht, was er beruehrt, steuert
   blind. `screen.control` ist nur innerhalb einer aktiven `screen.view`-Sitzung wirksam.
3. **Eigene Zustimmung pro Sitzung**, getrennt von der des Bildstroms, plus Androids eigener
   Aktivierungsdialog fuer den Dienst - der ohnehin nur ueber die Systemeinstellungen geht und von
   einer App nicht ausgeloest werden kann.
4. **Feedback fasst sich nicht selbst an.** Solange eine sensible eigene Oberflaeche im
   Vordergrund ist - App-Sperre, Kopplungsbestaetigung, Capability-Schalter, Widerruf - wird keine
   Geste ausgefuehrt. Ohne diese Regel waere der erste sinnvolle Angriff, die eigene Sperre
   auszuschalten und die Kopplung zu bestaetigen.
5. **Harte, kurze Sitzung** ohne Verlaengerung, sichtbarer lokaler Stop, der nicht ueber den Server
   geht, und sofortiges Ende bei Verbindungsverlust - wie bei 8.5.9.
6. **Kein Shell-Kommando, kein Intent-Start, keine Zwischenablage.** Das Protokoll transportiert
   Koordinaten und drei benannte globale Aktionen, sonst nichts.
7. **Jede Aktion ins Audit**, als Art und Zeitpunkt, ohne Koordinaten - Koordinaten waeren eine
   Aufzeichnung dessen, was der Besitzer getan hat.

## Was diese Entscheidung nicht heilt

- **Der Dienst kann mehr, als er tut.** Die Einschraenkung auf Gesten ist im APK nachlesbar, aber
  der Nutzer sieht beim Aktivieren Androids allgemeine Warnung. Wer der App nicht traut, hat an
  dieser Stelle recht.
- **Eine uebernommene Steuerung ist schlimmer als ein uebernommener Bildstrom.** Wer den Bildstrom
  hat, sieht eine Bestaetigung in einer Banking-App. Wer die Steuerung hat, drueckt sie.
- **Ausserhalb von Feedback gibt es keine Schutzzone.** Regel 4 schuetzt unsere eigenen
  Oberflaechen. Eine Berechtigungsabfrage von Android oder eine Bestaetigung in einer fremden App
  kann eine Geste treffen.
- **Vertrieb ueber Google Play waere damit fraglich.** Die Accessibility-API darf dort nur fuer
  Barrierefreiheit benutzt werden. Fuer eigene und ausdruecklich autorisierte Geraete per
  Direktinstallation ist das unerheblich, fuer eine Veroeffentlichung im Play Store nicht.

## Alternativen

| Alternative | Warum nicht |
| --- | --- |
| `adb shell input` ueber drahtloses Debugging | Eine Shell auf dem Zielgeraet ist durch die harten Regeln ausgeschlossen, und drahtloses Debugging ist ein deutlich groesserer Zugang als die Funktion braucht. |
| AccessibilityService **mit** Inhaltszugriff, fuer Texteingabe | Kauft Bequemlichkeit mit der Faehigkeit, jeden Bildschirminhalt jeder App zu lesen - einschliesslich dessen, was der Besitzer tippt. Der Unterschied zu Schadsoftware waere dann nur noch die Absicht. |
| Auf `screen.control` verzichten | Eine ehrliche Option, und gegenueber Punkt 2 und 4 oben keine schlechte: `screen.view` deckt Zusehen und Diagnose ab. Der Zugewinn durch Steuerung ist real, aber kleiner als der Zuwachs an Angriffsflaeche. |

## Empfehlung

**Nicht bauen, solange kein konkreter Bedarf benannt ist, der `screen.view` nicht deckt.**

Das ist keine Ablehnung des Auftrags, sondern die Anwendung von Punkt 5 der Konstitution auf den
Fall, in dem er am meisten kostet. Der Weg ist offen und die Bedingungen stehen oben; was fehlt,
ist die Antwort auf "wofuer". Wird sie gegeben, ist dieser ADR die Bauanleitung.

Bis dahin bleibt `screen.control` deklariert, deny-by-default und antwortet `UNSUPPORTED`, ohne
reservierten Nachrichtentyp.
