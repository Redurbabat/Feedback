import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { createHarness } from '../helpers/harness.js';

/**
 * One percent-encoded character used to be enough to leave the API behind.
 *
 * The router decodes before it matches, so `/%61pi/v1/pairing/start` reaches exactly the same
 * handler as `/api/v1/pairing/start`. Every check that asked `request.url.startsWith('/api/v1')`
 * disagreed with the router: measured against a running server, forty encoded requests went
 * through without a single 429 while ten canonical ones were already refused - the per-IP limit of
 * protocol section 11 switched off on every endpoint - and the same requests were answered with
 * the document CSP instead of the strict one.
 */

function buildSite(): string {
  const root = mkdtempSync(path.join(tmpdir(), 'feedback-encoded-'));
  writeFileSync(path.join(root, 'index.html'), '<!doctype html><title>Control Center</title>');
  mkdirSync(path.join(root, 'assets'));
  return root;
}

/** Spellings that the router reads as /api/v1 and a raw prefix comparison does not. */
const ENCODED = [
  '/%61pi/v1/devices',
  '/a%70i/v1/devices',
  '/api/v%31/devices',
  '/%61%70%69/%76%31/devices',
];

describe('an encoded API prefix is still the API', () => {
  it('reaches the same handler as the canonical spelling', async () => {
    const harness = await createHarness({ staticDir: buildSite() });
    try {
      const canonical = await harness.app.inject({ method: 'GET', url: '/api/v1/devices' });
      for (const url of ENCODED) {
        const encoded = await harness.app.inject({ method: 'GET', url });
        expect(encoded.statusCode, url).toBe(canonical.statusCode);
        expect((encoded.json() as { error: { code: string } }).error.code, url).toBe('UNAUTHORIZED');
      }
    } finally {
      await harness.close();
    }
  });

  it('keeps the strict policy, not the one meant for documents', async () => {
    const harness = await createHarness({ staticDir: buildSite() });
    try {
      for (const url of ENCODED) {
        const response = await harness.app.inject({ method: 'GET', url });
        const csp = response.headers['content-security-policy'] as string | undefined;
        expect(csp, url).toBe("default-src 'none'; frame-ancestors 'none'");
      }
    } finally {
      await harness.close();
    }
  });

  it('is rate limited exactly like the canonical spelling', async () => {
    const harness = await createHarness();
    try {
      const codes: number[] = [];
      for (let attempt = 0; attempt < 40; attempt += 1) {
        const response = await harness.app.inject({
          method: 'POST',
          url: '/%61pi/v1/pairing/start',
          payload: {},
        });
        codes.push(response.statusCode);
      }
      // The pairing bucket is far below forty, so a limit that works has to show up here.
      expect(codes).toContain(429);
    } finally {
      await harness.close();
    }
  });

  it('answers an unknown encoded API path in the protocol error shape', async () => {
    const harness = await createHarness({ staticDir: buildSite() });
    try {
      // Without this, a static deployment hands out index.html for a path the client reads as API.
      const response = await harness.app.inject({ method: 'GET', url: '/%61pi/v1/gibtesnicht' });
      expect(response.statusCode).toBe(404);
      expect((response.json() as { error: { code: string } }).error.code).toBe('NOT_FOUND');
    } finally {
      await harness.close();
    }
  });

  it('still treats a real document path as a document', async () => {
    const harness = await createHarness({ staticDir: buildSite() });
    try {
      const csp = (await harness.app.inject({ method: 'GET', url: '/' })).headers[
        'content-security-policy'
      ] as string | undefined;
      expect(csp).toContain("script-src 'self'");
    } finally {
      await harness.close();
    }
  });
});
