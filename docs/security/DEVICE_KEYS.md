# Geraeteschluessel

Stand: 2026-09-13. Die Entscheidung selbst steht in `docs/decisions/ADR-003-device-identity.md`;
hier steht, was daraus im Betrieb folgt.

## Was es gibt

| Schluessel | Ort | Zweck | Verlaesst das Geraet |
| --- | --- | --- | --- |
| Geraeteidentitaet (EC P-256) | Android Keystore, nicht exportierbar | signiert Pairing-Start und -Claim | **nie** (nur der oeffentliche Teil) |
| SecretStore-Schluessel (AES-256-GCM) | Android Keystore | versiegelt lokale Geheimnisse | nie |
| App-Lock-Verifier | ueber SecretStore versiegelt | prueft PIN/Passphrase | nie |
| `deviceToken` | ueber SecretStore versiegelt | authentisiert den Agenten am Server | nur zum Server, nur ueber TLS |

Der Server speichert vom Geraet ausschliesslich den **oeffentlichen** Schluessel und den daraus
abgeleiteten Fingerprint. Tokens und Pairing-Geheimnisse liegen dort nur als SHA-256.

## Ableitungen

```text
fingerprintHex = hex(sha256(spkiDer))
fingerprint    = fingerprintHex in 4er-Gruppen mit ":"
deviceId       = "fb-" + fingerprintHex[0..23]
```

Der Server rechnet beides bei jeder Registrierung nach. Ein Geraet, das eine `deviceId` behauptet,
die nicht zu seinem Schluessel passt, wird abgelehnt.

## Was der Besitzer sieht

Die App zeigt `deviceId` und Fingerprint, aber **nie den vollstaendigen oeffentlichen Schluessel**
in der normalen Oberflaeche. Der Fingerprint ist das, was sich beim Koppeln vergleichen laesst;
der ganze Schluessel waere nur Rauschen auf dem Bildschirm.

## Lebensdauer

Der Schluessel entsteht beim ersten Start und lebt so lange wie die Installation.

**Er ueberlebt keine Deinstallation.** Es gibt kein Backup und keine Wiederherstellung - das ist
die Kehrseite von "nicht exportierbar". Daraus folgt unmittelbar:

- Eine Deinstallation kostet Geraeteidentitaet und Kopplung. Danach ist ein neues Pairing noetig.
- Ein Wechsel der APK-Signatur erzwingt eine Deinstallation und damit dasselbe. Deshalb ist der
  feste Signaturschluessel aus `docs/deployment/ANDROID_RELEASE.md` sicherheitsrelevant.
- Ein Geraetewechsel ist ein neues Geraet. Das ist korrekt und nicht zu umgehen.

## Ehrliche Grenzen / offen

- **Keine Schluesselrotation.** Das Protokoll sieht sie als eigene Operation vor, sie ist nicht
  implementiert. Ein kompromittierter Schluessel heisst heute: widerrufen und neu koppeln.
- **Kein Nachweis ueber die Hardware-Bindung.** Android kann per Key Attestation bescheinigen,
  dass ein Schluessel in sicherer Hardware liegt. Feedback fordert das nicht an und behauptet
  deshalb nichts darueber. Auf einem Geraet ohne Hardware-Keystore gilt dieselbe API mit
  schwaecheren Garantien.
- **Keine Bindung an die Server-Identitaet.** Das Geraet merkt sich nicht, welchen
  Server-Schluessel es beim Pairing gesehen hat (TOFU waere moeglich). Siehe Threat Model 6.5.
- Der `deviceToken` rotiert nicht (Threat Model 4.13).

## Abnahme

**Nicht abgenommen auf Hardware.** Die Ableitungen sind gegen feste Testvektoren unit-getestet und
in CI gruen. Das Verhalten des echten Android Keystore - Erzeugung, Signatur, Verhalten bei
gesperrtem Geraet, Verhalten ohne Hardware-Backing - ist ungeprueft.
