import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { createHarness } from '../helpers/harness.js';

/**
 * Serving the control center from the API origin (FEEDBACK_STATIC_DIR).
 *
 * The point of the option is one origin for a first test against a real phone. The point of
 * these tests is that it stays an option and never becomes a way past the API: a static file
 * must not shadow a route, and a deep link must not turn an API error into an HTML page.
 */

function buildSite(): string {
  const root = mkdtempSync(path.join(tmpdir(), 'feedback-static-'));
  writeFileSync(path.join(root, 'index.html'), '<!doctype html><title>Control Center</title>');
  mkdirSync(path.join(root, 'assets'));
  writeFileSync(path.join(root, 'assets', 'app.js'), 'console.log("app");');
  // A file one level above the served root, to prove traversal does not reach it.
  writeFileSync(path.join(root, '..', 'feedback-static-secret.txt'), 'nicht ausliefern');
  return root;
}

describe('control center served from the API origin', () => {
  it('serves index.html and its assets', async () => {
    const harness = await createHarness({ staticDir: buildSite() });
    try {
      const index = await harness.app.inject({ method: 'GET', url: '/' });
      expect(index.statusCode).toBe(200);
      expect(index.body).toContain('Control Center');

      const asset = await harness.app.inject({ method: 'GET', url: '/assets/app.js' });
      expect(asset.statusCode).toBe(200);
    } finally {
      await harness.close();
    }
  });

  it('answers a deep link with the app rather than a 404', async () => {
    const harness = await createHarness({ staticDir: buildSite() });
    try {
      const deep = await harness.app.inject({ method: 'GET', url: '/devices/irgendwas' });
      expect(deep.statusCode).toBe(200);
      expect(deep.body).toContain('Control Center');
    } finally {
      await harness.close();
    }
  });

  it('still answers an unknown API path in the protocol error shape', async () => {
    const harness = await createHarness({ staticDir: buildSite() });
    try {
      const missing = await harness.app.inject({ method: 'GET', url: '/api/v1/gibtesnicht' });
      expect(missing.statusCode).toBe(404);
      // An HTML page here would break every client that expects the error shape.
      expect((missing.json() as { error: { code: string } }).error.code).toBe('NOT_FOUND');
    } finally {
      await harness.close();
    }
  });

  it('does not turn a wrong method into the app', async () => {
    const harness = await createHarness({ staticDir: buildSite() });
    try {
      const posted = await harness.app.inject({ method: 'POST', url: '/devices/irgendwas' });
      expect(posted.statusCode).toBe(404);
      expect((posted.json() as { error: { code: string } }).error.code).toBe('NOT_FOUND');
    } finally {
      await harness.close();
    }
  });

  it('refuses to walk out of the served directory', async () => {
    const harness = await createHarness({ staticDir: buildSite() });
    try {
      for (const url of [
        '/../feedback-static-secret.txt',
        '/%2e%2e/feedback-static-secret.txt',
        '/assets/../../feedback-static-secret.txt',
      ]) {
        const response = await harness.app.inject({ method: 'GET', url });
        expect(response.body, url).not.toContain('nicht ausliefern');
      }
    } finally {
      await harness.close();
    }
  });

  it('serves nothing at all when the option is unset', async () => {
    const harness = await createHarness();
    try {
      const index = await harness.app.inject({ method: 'GET', url: '/' });
      expect(index.statusCode).toBe(404);
      expect((index.json() as { error: { code: string } }).error.code).toBe('NOT_FOUND');
    } finally {
      await harness.close();
    }
  });
});
