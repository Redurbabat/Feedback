import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { tmpdir } from 'node:os';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { RATE_LIMITS } from '../../src/http/rateLimit.js';
import {
  DEFAULT_METADATA,
  createHarness,
  createTestDevice,
  createTestUser,
  randomPassword,
} from '../helpers/harness.js';
import type { Harness } from '../helpers/harness.js';

/**
 * The public setup surface of M8: `/pair` and `/.well-known/assetlinks.json`.
 *
 * Both are reachable by anyone who knows the domain, so these tests are mostly about what the
 * answers do NOT contain and what they do NOT require: no session, no cookie, nothing about the
 * pairing state of this deployment, and no address that came out of a request header.
 */

const TEST_FINGERPRINT =
  'AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:' +
  'AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89';

/** A stand-in for the built Control Center, so the SPA fallback is actually in the way. */
function buildSite(): string {
  const root = mkdtempSync(path.join(tmpdir(), 'feedback-setup-static-'));
  writeFileSync(
    path.join(root, 'index.html'),
    '<!doctype html><title>Control Center</title>' +
      '<script type="module" src="/assets/app.js"></script>',
  );
  mkdirSync(path.join(root, 'assets'));
  writeFileSync(path.join(root, 'assets', 'app.js'), 'console.log("app");');
  return root;
}

/** A registered device and its owner - the state the page must not talk about. */
async function registerOwnedDevice(
  harness: Harness,
): Promise<{ email: string; deviceName: string; deviceId: string }> {
  const email = `inhaber-${randomBytes(8).toString('hex')}@example.test`;
  const userId = await createTestUser(harness, email, randomPassword());
  const device = createTestDevice();
  const deviceName = `Geraet-${randomBytes(4).toString('hex')}`;
  await harness.context.repositories.devices.upsert({
    ownerId: userId,
    deviceId: device.deviceId,
    publicKey: device.publicKeyBase64,
    fingerprint: device.fingerprint,
    name: deviceName,
    platform: DEFAULT_METADATA.platform,
    osVersion: DEFAULT_METADATA.osVersion,
    sdkInt: DEFAULT_METADATA.sdkInt,
    appVersion: DEFAULT_METADATA.appVersion,
  });
  return { email, deviceName, deviceId: device.deviceId };
}

describe('GET /pair', () => {
  it('answers before the SPA fallback instead of shipping the whole bundle', async () => {
    // Without its own route this path reaches the not-found handler, which serves index.html for
    // every GET while FEEDBACK_STATIC_DIR is set. That is the regression this test pins down.
    const harness = await createHarness({ staticDir: buildSite() });
    try {
      const response = await harness.app.inject({ method: 'GET', url: '/pair' });
      expect(response.statusCode).toBe(200);
      expect(response.headers['content-type']).toMatch(/^text\/html/);
      // The page names the Control Center in a sentence, so the bundle is identified by the
      // markers only the real index.html has.
      expect(response.body).not.toContain('<title>Control Center</title>');
      expect(response.body).not.toContain('/assets/app.js');
      expect(response.body).toContain('Feedback einrichten');

      // The fallback itself still works for an ordinary deep link.
      const deep = await harness.app.inject({ method: 'GET', url: '/devices/irgendwas' });
      expect(deep.body).toContain('Control Center');
    } finally {
      await harness.close();
    }
  });

  it('answers the same page with a trailing slash', async () => {
    const harness = await createHarness();
    try {
      const plain = await harness.app.inject({ method: 'GET', url: '/pair' });
      const slashed = await harness.app.inject({ method: 'GET', url: '/pair/' });
      expect(slashed.statusCode).toBe(200);
      expect(slashed.body).toBe(plain.body);
    } finally {
      await harness.close();
    }
  });

  it('needs no session and sets no cookie', async () => {
    const harness = await createHarness();
    try {
      const anonymous = await harness.app.inject({ method: 'GET', url: '/pair' });
      expect(anonymous.statusCode).toBe(200);
      expect(anonymous.headers['set-cookie']).toBeUndefined();

      // A cookie may be sent; it must not change a single byte of the answer.
      const withCookie = await harness.app.inject({
        method: 'GET',
        url: '/pair',
        headers: { cookie: 'feedback_session=irgendein-wert' },
      });
      expect(withCookie.statusCode).toBe(200);
      expect(withCookie.body).toBe(anonymous.body);
    } finally {
      await harness.close();
    }
  });

  it('says nothing about the pairing state of this deployment', async () => {
    const harness = await createHarness();
    try {
      const owned = await registerOwnedDevice(harness);
      const body = (await harness.app.inject({ method: 'GET', url: '/pair' })).body;
      for (const secret of [owned.email, owned.deviceName, owned.deviceId]) {
        expect(body, secret).not.toContain(secret);
      }
      // Nor a count: the number of registered devices is itself information about the owner.
      expect(body).not.toMatch(/\d+\s*(Geraet|Geraete|Benutzer)/i);
    } finally {
      await harness.close();
    }
  });

  it('loads nothing, so no policy has to be widened for it', async () => {
    // The page is served under `default-src 'none'` whenever FEEDBACK_STATIC_DIR is unset. That
    // is only harmless as long as it has no stylesheet, no script and no image to load - which is
    // exactly why it has none. This test fails the day someone adds one.
    const harness = await createHarness();
    try {
      const response = await harness.app.inject({ method: 'GET', url: '/pair' });
      for (const marker of ['<link', '<script', '<style', 'style=', 'src=']) {
        expect(response.body, marker).not.toContain(marker);
      }
      expect(response.headers['content-security-policy']).toContain("default-src 'none'");
    } finally {
      await harness.close();
    }
  });

  it('shows a configured origin, never the Host header of the request', async () => {
    const harness = await createHarness();
    try {
      const response = await harness.app.inject({
        method: 'GET',
        url: '/pair',
        headers: { host: 'boese.example' },
      });
      expect(response.body).not.toContain('boese.example');
      // Falls back to the advertised origin instead of repeating what the caller asked for.
      expect(response.body).toContain('http://localhost:5173');
    } finally {
      await harness.close();
    }
  });

  it('picks the matching origin when a deployment has more than one', async () => {
    const harness = await createHarness({
      allowedOrigins: ['https://erste.example', 'https://zweite.example'],
      trustProxy: true,
    });
    try {
      const response = await harness.app.inject({
        method: 'GET',
        url: '/pair',
        headers: { host: 'zweite.example', 'x-forwarded-proto': 'https' },
      });
      expect(response.body).toContain('https://zweite.example');
      expect(response.body).not.toContain('https://erste.example');
    } finally {
      await harness.close();
    }
  });
});

describe('GET /.well-known/assetlinks.json', () => {
  it('is not served without a configured fingerprint, not even as the SPA fallback', async () => {
    const harness = await createHarness({ staticDir: buildSite() });
    try {
      const response = await harness.app.inject({
        method: 'GET',
        url: '/.well-known/assetlinks.json',
      });
      expect(response.statusCode).toBe(404);
      expect(response.body).not.toContain('/assets/app.js');
      expect((response.json() as { error: { code: string } }).error.code).toBe('NOT_FOUND');
    } finally {
      await harness.close();
    }
  });

  it('serves exactly the format the verifier reads', async () => {
    const harness = await createHarness({
      android: { packageName: 'com.beispiel.feedback', certSha256: TEST_FINGERPRINT },
    });
    try {
      const response = await harness.app.inject({
        method: 'GET',
        url: '/.well-known/assetlinks.json',
      });
      expect(response.statusCode).toBe(200);
      // Exactly this media type, no charset parameter: it is what the verifier is documented
      // to read, and Fastify would append one to a string payload.
      expect(response.headers['content-type']).toBe('application/json');
      expect(response.body).toBe(
        '[{"relation":["delegate_permission/common.handle_all_urls"],' +
          '"target":{"namespace":"android_app","package_name":"com.beispiel.feedback",' +
          `"sha256_cert_fingerprints":["${TEST_FINGERPRINT}"]}}]`,
      );
    } finally {
      await harness.close();
    }
  });

  it('claims exactly one key and needs no session', async () => {
    const harness = await createHarness({
      android: { packageName: 'com.beispiel.feedback', certSha256: TEST_FINGERPRINT },
    });
    try {
      const response = await harness.app.inject({
        method: 'GET',
        url: '/.well-known/assetlinks.json',
      });
      const statements = response.json() as {
        target: { sha256_cert_fingerprints: string[] };
      }[];
      expect(statements).toHaveLength(1);
      // A second fingerprint would leave an old, possibly lost key a verified claim on the domain.
      expect(statements[0]?.target.sha256_cert_fingerprints).toEqual([TEST_FINGERPRINT]);
      expect(response.headers['set-cookie']).toBeUndefined();
    } finally {
      await harness.close();
    }
  });
});

describe('rate limit on the public setup paths', () => {
  it('limits both paths and leaves the API budget alone', async () => {
    const harness = await createHarness({
      android: { packageName: 'com.beispiel.feedback', certSha256: TEST_FINGERPRINT },
    });
    try {
      // The clock does not move in a test, so the bucket does not refill while it drains.
      for (let index = 0; index < RATE_LIMITS.apiDefault.limit; index += 1) {
        const response = await harness.app.inject({ method: 'GET', url: '/pair' });
        expect(response.statusCode, `Anfrage ${index}`).toBe(200);
      }

      const page = await harness.app.inject({ method: 'GET', url: '/pair' });
      expect(page.statusCode).toBe(429);
      expect((page.json() as { error: { code: string } }).error.code).toBe('RATE_LIMITED');

      const assetLinks = await harness.app.inject({
        method: 'GET',
        url: '/.well-known/assetlinks.json',
      });
      expect(assetLinks.statusCode).toBe(429);
      expect((assetLinks.json() as { error: { code: string } }).error.code).toBe('RATE_LIMITED');

      // Separate key scope: a flood against the setup pages must not lock the owner out of the
      // API from the same address.
      const api = await harness.app.inject({ method: 'GET', url: '/api/v1/gibtesnicht' });
      expect(api.statusCode).toBe(404);
    } finally {
      await harness.close();
    }
  });
});
