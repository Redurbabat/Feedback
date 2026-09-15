-- Eine zweite Stufe vor dem Erteilen einer Capability (THREAT_MODEL 4.5).
--
-- Auf dem Geraet verlangt eine Freigabe die App-Sperre erneut; im Control Center verlangte sie
-- bisher gar nichts, ein uebernommenes Browserfenster konnte serverseitig alles freigeben. Die
-- Sitzung merkt sich jetzt, bis wann sie das Passwort zuletzt bestaetigt hat.
--
-- NULL heisst "nie bestaetigt" und ist der Normalzustand: eine frisch angemeldete Sitzung ist
-- bewusst NICHT erhoeht, sonst waere die Anmeldung selbst die zweite Stufe.
ALTER TABLE auth_sessions ADD COLUMN elevated_until INTEGER;
