import { describe, expect, it } from 'vitest';

import {
  decideQrCode,
  pairingUrl,
  qrRefusalCopy,
  setupSteps,
  type OriginParts,
  type QrRefusal,
} from './onboarding.ts';

/** Builds the three `window.location` fields the decision reads. */
function location(href: string): OriginParts {
  const url = new URL(href);
  return { protocol: url.protocol, hostname: url.hostname, origin: url.origin };
}

describe('pairingUrl', () => {
  it('appends the setup path to the origin', () => {
    expect(pairingUrl(location('https://feedback.example.com/devices'))).toBe(
      'https://feedback.example.com/pair',
    );
  });

  it('keeps a non-default port', () => {
    expect(pairingUrl(location('https://feedback.example.com:8443/'))).toBe(
      'https://feedback.example.com:8443/pair',
    );
  });

  it('does not produce a double slash when the origin carries one', () => {
    const padded: OriginParts = {
      protocol: 'https:',
      hostname: 'feedback.example.com',
      origin: 'https://feedback.example.com/',
    };
    expect(pairingUrl(padded)).toBe('https://feedback.example.com/pair');
  });
});

describe('decideQrCode', () => {
  it('shows a code for an HTTPS address with a public name', () => {
    expect(decideQrCode(location('https://feedback.example.com/'))).toEqual({
      kind: 'show',
      url: 'https://feedback.example.com/pair',
    });
  });

  it('shows a code for a subdomain with a port', () => {
    expect(decideQrCode(location('https://control.feedback.example.com:8443/'))).toEqual({
      kind: 'show',
      url: 'https://control.feedback.example.com:8443/pair',
    });
  });

  it('shows a code for a punycoded name', () => {
    // The browser hands out the ASCII form, and it is never decoded for display.
    expect(decideQrCode(location('https://xn--mnchen-3ya.example/'))).toEqual({
      kind: 'show',
      url: 'https://xn--mnchen-3ya.example/pair',
    });
  });

  it('refuses cleartext even for a public name', () => {
    // The app declines cleartext outright, so a code here would scan into a refusal.
    expect(decideQrCode(location('http://feedback.example.com/'))).toEqual({
      kind: 'refuse',
      reason: 'not-https',
    });
  });

  it('names the scheme first for the usual first-run address', () => {
    // http://localhost:8080 is wrong twice over; the scheme is the fundamental fault.
    expect(decideQrCode(location('http://localhost:8080/'))).toEqual({
      kind: 'refuse',
      reason: 'not-https',
    });
  });

  it.each([
    'https://localhost:8443/',
    'https://app.localhost/',
    'https://127.0.0.1:8443/',
    'https://127.1.2.3/',
    'https://[::1]:8443/',
    'https://0.0.0.0:8443/',
  ])('refuses the loopback address %s', (href) => {
    expect(decideQrCode(location(href))).toEqual({ kind: 'refuse', reason: 'loopback' });
  });

  it.each([
    'https://192.168.1.10/',
    'https://10.0.0.5:8443/',
    'https://203.0.113.7/',
    'https://[2001:db8::1]:8443/',
  ])('refuses the IP literal %s', (href) => {
    expect(decideQrCode(location(href))).toEqual({ kind: 'refuse', reason: 'ip-literal' });
  });

  it.each([
    'https://nas.local/',
    'https://feedback.home.arpa/',
    'https://feedback.internal/',
    'https://feedback.lan/',
    'https://feedback/',
  ])('refuses the local-only name %s', (href) => {
    expect(decideQrCode(location(href))).toEqual({ kind: 'refuse', reason: 'local-name' });
  });

  it('refuses a hostname with a trailing dot', () => {
    // URL keeps the dot, and the Android side rejects that spelling.
    const dotted: OriginParts = {
      protocol: 'https:',
      hostname: 'feedback.example.com.',
      origin: 'https://feedback.example.com.',
    };
    expect(decideQrCode(dotted)).toEqual({ kind: 'refuse', reason: 'trailing-dot' });
  });

  it('is case insensitive about the hostname', () => {
    const shouted: OriginParts = {
      protocol: 'https:',
      hostname: 'LOCALHOST',
      origin: 'https://LOCALHOST',
    };
    expect(decideQrCode(shouted)).toEqual({ kind: 'refuse', reason: 'loopback' });
  });

  it('refuses anything that is not an HTTPS page, including file://', () => {
    const local: OriginParts = { protocol: 'file:', hostname: '', origin: 'null' };
    expect(decideQrCode(local)).toEqual({ kind: 'refuse', reason: 'not-https' });
  });
});

describe('qrRefusalCopy', () => {
  const reasons: QrRefusal[] = [
    'not-https',
    'loopback',
    'ip-literal',
    'local-name',
    'trailing-dot',
  ];

  it('says why and what to do instead for every refusal', () => {
    // A refusal without a way forward is the silent break this panel exists to avoid.
    for (const reason of reasons) {
      const copy = qrRefusalCopy(reason);
      expect(copy.headline.length).toBeGreaterThan(0);
      expect(copy.reason.length).toBeGreaterThan(0);
      expect(copy.remedy.length).toBeGreaterThan(0);
    }
  });

  it('gives each refusal its own wording', () => {
    const texts = new Set(reasons.map((reason) => qrRefusalCopy(reason).reason));
    expect(texts.size).toBe(reasons.length);
  });
});

describe('setupSteps', () => {
  it('tells the owner to scan when there is a code to scan', () => {
    const steps = setupSteps({ kind: 'show', url: 'https://feedback.example.com/pair' });
    expect(steps).toHaveLength(3);
    expect(steps[0]).toMatch(/Kamera-App/u);
  });

  it('tells the owner to type when there is no code', () => {
    const steps = setupSteps({ kind: 'refuse', reason: 'loopback' });
    expect(steps).toHaveLength(3);
    expect(steps[0]).not.toMatch(/Kamera-App/u);
    expect(steps[0]).toMatch(/eintragen/u);
  });

  it('keeps the two steps after the first identical', () => {
    // Only the way the device learns the address changes, not what happens afterwards.
    const scanned = setupSteps({ kind: 'show', url: 'https://feedback.example.com/pair' });
    const typed = setupSteps({ kind: 'refuse', reason: 'not-https' });
    expect(scanned.slice(1)).toEqual(typed.slice(1));
  });

  it('says that the link confirms a known address rather than introducing one', () => {
    const steps = setupSteps({ kind: 'show', url: 'https://feedback.example.com/pair' });
    expect(steps[1]).toMatch(/keine neue Server-Adresse/u);
  });
});
