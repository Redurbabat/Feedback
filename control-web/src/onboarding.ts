/**
 * The rules behind the "Neues Gerät hinzufügen" panel.
 *
 * Kept free of the DOM for the same reason as files.ts, plus one that is specific to
 * this panel: the decision below is the difference between a setup code that works and
 * one that quietly leads nowhere. A rule that can only be checked by looking at a
 * rendered page is a rule nobody checks.
 */

/** The parts of `window.location` this decision depends on. */
export interface OriginParts {
  readonly protocol: string;
  readonly hostname: string;
  readonly origin: string;
}

/** Why a QR code of this origin would not get a phone anywhere. */
export type QrRefusal =
  | 'not-https'
  | 'loopback'
  | 'ip-literal'
  | 'local-name'
  | 'trailing-dot';

export type QrDecision =
  | { readonly kind: 'show'; readonly url: string }
  | { readonly kind: 'refuse'; readonly reason: QrRefusal };

/** The setup page a scanned code leads to. */
export function pairingUrl(location: OriginParts): string {
  return `${location.origin.replace(/\/+$/u, '')}/pair`;
}

/**
 * Whether a QR code of this page's own origin is worth showing.
 *
 * `location.origin` is whatever the owner happened to open the browser with, and on a
 * first run that is regularly `http://localhost:8080`. A code pointing there is
 * unreachable from a phone on mobile data, and the app refuses cleartext in any case -
 * so the promised way "without typing" would break without saying so. Constitution 12
 * forbids exactly that, which is why an unusable origin gets a written explanation
 * instead of a code that looks fine and scans into nothing.
 *
 * The checks are ordered from the most fundamental outwards, so the explanation names
 * the first thing the owner has to change rather than an incidental second fault.
 */
export function decideQrCode(location: OriginParts): QrDecision {
  if (location.protocol !== 'https:') {
    return { kind: 'refuse', reason: 'not-https' };
  }
  const hostname = location.hostname.toLowerCase();
  if (isLoopback(hostname)) {
    return { kind: 'refuse', reason: 'loopback' };
  }
  if (isIpLiteral(hostname)) {
    return { kind: 'refuse', reason: 'ip-literal' };
  }
  if (hostname.endsWith('.')) {
    return { kind: 'refuse', reason: 'trailing-dot' };
  }
  if (isLocalName(hostname)) {
    return { kind: 'refuse', reason: 'local-name' };
  }
  return { kind: 'show', url: pairingUrl(location) };
}

function isLoopback(hostname: string): boolean {
  if (hostname === 'localhost' || hostname.endsWith('.localhost')) {
    return true;
  }
  // Browsers hand out an IPv6 host in brackets; the bare form is accepted too so the
  // function stays usable with values that did not come from `window.location`.
  if (hostname === '[::1]' || hostname === '::1') {
    return true;
  }
  // The whole 127.0.0.0/8 block is loopback, not just 127.0.0.1.
  return /^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$/u.test(hostname) || hostname === '0.0.0.0';
}

/**
 * An address instead of a name.
 *
 * The last label decides: a registrable name never ends in a label made of digits, so
 * this catches every IPv4 literal without a separate rule - the same reasoning that
 * `ServerEndpoint.parse` uses on Android, which is what will reject such an address
 * there anyway.
 */
function isIpLiteral(hostname: string): boolean {
  if (hostname.includes(':')) {
    return true;
  }
  const labels = hostname.replace(/\.$/u, '').split('.');
  const last = labels[labels.length - 1] ?? '';
  return /^\d+$/u.test(last);
}

/**
 * A name that only resolves inside one network.
 *
 * Constitution 13: device and control center reach each other over the internet, never
 * over a shared network. A code carrying a name that only a router knows would work on
 * the couch and nowhere else, which is the worst of both.
 */
function isLocalName(hostname: string): boolean {
  if (!hostname.includes('.')) {
    return true;
  }
  return ['.local', '.home.arpa', '.internal', '.lan'].some((suffix) =>
    hostname.endsWith(suffix),
  );
}

/**
 * The three steps, in the order the owner carries them out.
 *
 * Only the first one depends on the decision above: with no code on the screen there
 * is nothing to point a camera at, and telling someone to scan something that is not
 * there is worse than telling them to type.
 */
export function setupSteps(decision: QrDecision): readonly [string, string, string] {
  const scan =
    'Am neuen Gerät die normale Kamera-App öffnen und auf den Code halten. Feedback bringt bewusst keinen eigenen Scanner mit: die Kamera-App des Systems ist die Stelle, an der du siehst und entscheidest, worauf du zielst - eine zusätzliche Kamera-Berechtigung wäre der falsche Preis dafür.';
  const type =
    'Am neuen Gerät Feedback öffnen und die Adresse oben eintragen. Solange diese Seite unter keiner öffentlich erreichbaren HTTPS-Adresse läuft, führt kein Weg daran vorbei.';
  return [
    decision.kind === 'show' ? scan : type,
    'Dem Link folgen. Er bringt keine neue Server-Adresse mit, sondern bestätigt nur die, die in der App ohnehin hinterlegt ist. Weicht sie ab, lehnt die App den Link sichtbar ab statt still etwas anderes zu übernehmen.',
    'Das Gerät zeigt danach einen sechsstelligen Code. Ihn hier unter „Koppeln“ eingeben und Gerätename und Fingerprint prüfen, bevor du bestätigst. Kopplung erteilt Basisvertrauen, keinen Zugriff - jede Berechtigung wird danach einzeln erteilt.',
  ];
}

/** What the owner reads in place of the code. */
export interface QrRefusalCopy {
  readonly headline: string;
  readonly reason: string;
  readonly remedy: string;
}

export function qrRefusalCopy(reason: QrRefusal): QrRefusalCopy {
  switch (reason) {
    case 'not-https':
      return {
        headline: 'Hier hilft kein QR-Code',
        reason:
          'Diese Seite wird nicht über HTTPS ausgeliefert. Die Feedback-App verbindet sich grundsätzlich nicht über eine unverschlüsselte Adresse, ein Code darauf würde am Gerät abgelehnt.',
        remedy:
          'Öffne das Control Center über die HTTPS-Adresse deines Servers und zeige den Code von dort.',
      };
    case 'loopback':
      return {
        headline: 'Hier hilft kein QR-Code',
        reason:
          'Diese Seite läuft auf diesem Rechner (localhost bzw. 127.0.0.1). Ein Gerät im Mobilfunknetz erreicht diese Adresse nicht - sie zeigt auf das Gerät selbst.',
        remedy:
          'Öffne das Control Center über die feste Adresse deines Servers. Wie du zu einer kommst, steht in docs/deployment/BETRIEB.md.',
      };
    case 'ip-literal':
      return {
        headline: 'Hier hilft kein QR-Code',
        reason:
          'Diese Adresse ist eine IP-Adresse, kein Name. Die Feedback-App akzeptiert nur registrierbare Hostnamen, weil ein Zertifikat für einen Namen ausgestellt wird und nicht für eine Nummer.',
        remedy: 'Öffne das Control Center über den Hostnamen deines Servers.',
      };
    case 'trailing-dot':
      return {
        headline: 'Hier hilft kein QR-Code',
        reason:
          'Der Hostname endet auf einen Punkt. Das ist zwar gültiges DNS, die Feedback-App lehnt diese Schreibweise aber ab, weil sonst derselbe Server unter zwei verschiedenen Zeichenketten auftauchen würde.',
        remedy: 'Öffne das Control Center ohne den Punkt am Ende der Adresse.',
      };
    case 'local-name':
      return {
        headline: 'Hier hilft kein QR-Code',
        reason:
          'Dieser Name gilt nur im lokalen Netz. Feedback verbindet Geräte über das Internet und setzt nie voraus, dass beide im selben WLAN sind.',
        remedy:
          'Öffne das Control Center über die öffentlich erreichbare Adresse deines Servers.',
      };
  }
}
