import { randomBytes } from 'node:crypto';

import { describe, expect, it } from 'vitest';

import { ConfigError, loadConfig } from '../src/config.js';

/**
 * Configuration of the Android claim (`/.well-known/assetlinks.json`).
 *
 * The fingerprint is the one value in this file that nothing else checks: a wrong one is served
 * happily, fetched happily and simply never matches, so the App Link stays unverified without a
 * single failing request. These tests keep the strict check in place.
 */

const VALID_FINGERPRINT =
  'AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:' +
  'AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89';

function baseEnv(overrides: Record<string, string> = {}): NodeJS.ProcessEnv {
  return {
    NODE_ENV: 'test',
    FEEDBACK_COOKIE_SECRET: randomBytes(32).toString('base64url'),
    FEEDBACK_ALLOWED_ORIGINS: 'https://feedback.example',
    ...overrides,
  };
}

describe('android asset link configuration', () => {
  it('stays unset when no fingerprint is configured', () => {
    const config = loadConfig(baseEnv());
    expect(config.android.certSha256).toBeUndefined();
    expect(config.android.packageName).toBe('com.redurbabat.feedback');
  });

  it('accepts a keytool fingerprint', () => {
    const config = loadConfig(
      baseEnv({
        FEEDBACK_ANDROID_CERT_SHA256: VALID_FINGERPRINT,
        FEEDBACK_ANDROID_PACKAGE: 'com.beispiel.feedback',
      }),
    );
    expect(config.android.certSha256).toBe(VALID_FINGERPRINT);
    expect(config.android.packageName).toBe('com.beispiel.feedback');
  });

  it('aborts the start on a fingerprint that Android would silently reject', () => {
    const wrong: Record<string, string> = {
      'lowercase hex': VALID_FINGERPRINT.toLowerCase(),
      'one pair short': VALID_FINGERPRINT.slice(0, -3),
      'one pair too many': `${VALID_FINGERPRINT}:AB`,
      'without colons': VALID_FINGERPRINT.replaceAll(':', ''),
      'with spaces': VALID_FINGERPRINT.replaceAll(':', ' '),
      'sha-1 length': VALID_FINGERPRINT.split(':').slice(0, 20).join(':'),
      'trailing colon': `${VALID_FINGERPRINT}:`,
      'not hex at all': VALID_FINGERPRINT.replace('AB', 'ZZ'),
    };
    for (const [label, value] of Object.entries(wrong)) {
      expect(() => loadConfig(baseEnv({ FEEDBACK_ANDROID_CERT_SHA256: value })), label).toThrow(
        ConfigError,
      );
    }
  });

  it('names the variable in the error so the typo is findable', () => {
    expect(() => loadConfig(baseEnv({ FEEDBACK_ANDROID_CERT_SHA256: 'AB:CD' }))).toThrow(
      /FEEDBACK_ANDROID_CERT_SHA256/,
    );
  });

  it('rejects a package name that is not an application id', () => {
    for (const value of ['feedback', 'Com.Beispiel.Feedback', 'com..feedback', 'com.1beispiel.x']) {
      expect(() => loadConfig(baseEnv({ FEEDBACK_ANDROID_PACKAGE: value })), value).toThrow(
        ConfigError,
      );
    }
  });
});

/**
 * The public address of this server (`FEEDBACK_PUBLIC_ORIGIN`).
 *
 * Its only job is to be printed on `/pair` as the address a visitor holds against what their app
 * knows. That makes a wrong value worse than a missing one: an address the app can never accept
 * sends the owner looking for a mismatch that is ours. So the rule here is the app's own rule
 * (`ServerEndpoint.parse`), and a value that fails it stops the start instead of reaching a page.
 */
describe('public origin configuration', () => {
  it('stays unset when nothing is configured', () => {
    expect(loadConfig(baseEnv()).publicOrigin).toBeUndefined();
  });

  it('accepts an https origin and keeps it as written', () => {
    const config = loadConfig(baseEnv({ FEEDBACK_PUBLIC_ORIGIN: 'https://feedback.example.com' }));
    expect(config.publicOrigin).toBe('https://feedback.example.com');
  });

  it('keeps a non-default port, because a different port is a different server', () => {
    const config = loadConfig(
      baseEnv({ FEEDBACK_PUBLIC_ORIGIN: 'https://feedback.example.com:8443' }),
    );
    expect(config.publicOrigin).toBe('https://feedback.example.com:8443');
  });

  it('drops a port 443 that was spelled out, exactly as both ends do', () => {
    // RFC 6454 leaves it out, the browser leaves it out, ServerEndpoint.parse leaves it out. An
    // anchor that kept it would refuse every real link to itself.
    const config = loadConfig(
      baseEnv({ FEEDBACK_PUBLIC_ORIGIN: 'https://feedback.example.com:443' }),
    );
    expect(config.publicOrigin).toBe('https://feedback.example.com');
  });

  it('refuses every address the app itself would refuse', () => {
    const wrong: Record<string, string> = {
      cleartext: 'http://feedback.example.com',
      'single label host': 'https://localhost',
      'single label host with port': 'https://feedback:8443',
      'ipv4 literal': 'https://192.0.2.10',
      'ipv6 literal': 'https://[2001:db8::1]',
      'trailing dot': 'https://feedback.example.com.',
      'with a path': 'https://feedback.example.com/pair',
      'with a query': 'https://feedback.example.com/?a=1',
      'with a fragment': 'https://feedback.example.com/#x',
      'with credentials': 'https://user:pass@feedback.example.com',
      'not a url at all': 'feedback.example.com',
      'empty': ' ',
    };
    for (const [label, value] of Object.entries(wrong)) {
      expect(() => loadConfig(baseEnv({ FEEDBACK_PUBLIC_ORIGIN: value })), label).toThrow(
        ConfigError,
      );
    }
  });

  it('names the variable in the error so the typo is findable', () => {
    expect(() => loadConfig(baseEnv({ FEEDBACK_PUBLIC_ORIGIN: 'http://localhost:5173' }))).toThrow(
      /FEEDBACK_PUBLIC_ORIGIN/,
    );
  });

  it('is independent of the control center origins', () => {
    // The whole point of the variable: the API host may be a different host than the Control
    // Center, so the one must not have to appear in the other.
    const config = loadConfig(
      baseEnv({
        FEEDBACK_ALLOWED_ORIGINS: 'https://control.example.com',
        FEEDBACK_PUBLIC_ORIGIN: 'https://api.example.com',
      }),
    );
    expect(config.publicOrigin).toBe('https://api.example.com');
    expect(config.allowedOrigins).toEqual(['https://control.example.com']);
  });
});
