-- Rotation der Geraete-Token (THREAT_MODEL 4.13).
--
-- Bisher galt ein deviceToken bis zum Widerruf, unbegrenzt. Ein gestohlener Token war damit eine
-- vollstaendige Uebernahme der Geraeterolle auf Dauer. Jetzt bildet jedes Geraet eine Kette:
-- ein Token wird abgeloest, der Nachfolger zeigt zurueck, und der Vorgaenger stirbt in dem
-- Moment, in dem der Nachfolger zum ersten Mal benutzt wird.
--
-- replaced_at NULL heisst "aktuell". Ein abgeloester Token bleibt bewusst gueltig, solange sein
-- Nachfolger ungenutzt ist: die Antwort mit dem neuen Token kann verloren gehen, und ein Geraet,
-- das den neuen nie gesehen hat, darf davon nicht ausgesperrt werden. Es gibt deshalb absichtlich
-- KEIN Zeitfenster - ein Zeitfenster wuerde genau den Fall aussperren, den es schuetzen soll.
--
-- Wird ein abgeloester Token benutzt, NACHDEM sein Nachfolger benutzt wurde, halten zwei Parteien
-- dieselbe Kette. Welche davon das Geraet ist, kann der Server nicht wissen - deshalb endet die
-- Kopplung dort.
ALTER TABLE device_tokens ADD COLUMN replaced_at INTEGER;
ALTER TABLE device_tokens ADD COLUMN replaced_by TEXT;

CREATE INDEX device_tokens_replaced_by_idx ON device_tokens (replaced_by);
