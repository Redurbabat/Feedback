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
