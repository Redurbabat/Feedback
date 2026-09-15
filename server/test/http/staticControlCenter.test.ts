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
  // Shaped like the real bundle: the page loads its script and its stylesheet from /assets.
  writeFileSync(
    path.join(root, 'index.html'),
    '<!doctype html><title>Control Center</title>' +
      '<link rel="stylesheet" href="/assets/app.css">' +
      '<script type="module" src="/assets/app.js"></script>',
  );
  mkdirSync(path.join(root, 'assets'));
  writeFileSync(path.join(root, 'assets', 'app.js'), 'console.log("app");');
  writeFileSync(path.join(root, 'assets', 'app.css'), 'body{margin:0}');
  // A file one level above the served root, to prove traversal does not reach it.
  writeFileSync(path.join(root, '..', 'feedback-static-secret.txt'), 'nicht ausliefern');
  return root;
}

/** Splits a Content-Security-Policy header into directive name -> source list. */
function directives(header: string | undefined): Map<string, string[]> {
  const parsed = new Map<string, string[]>();
  for (const part of (header ?? '').split(';')) {
    const [name, ...sources] = part.trim().split(/\s+/);
    if (name !== undefined && name !== '') {
      parsed.set(name.toLowerCase(), sources);
    }
  }
  return parsed;
}

/**
 * What a browser does with one resource of `kind` under `header`: the specific directive if it
 * exists, otherwise the `default-src` fallback. Deliberately not a string comparison against the
 * expected policy - that kind of test passes while the page stays blank.
 */
function allows(header: string | undefined, kind: string, source: string): boolean {
  const parsed = directives(header);
  const sources = parsed.get(kind) ?? parsed.get('default-src') ?? [];
  return sources.includes(source);
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

  /*
   * These four exist because the tests above were all green while the page was blank in a real
   * browser. `default-src 'none'` on every answer meant the delivered index.html was not allowed
   * to load its own script or its own stylesheet - a 200 with the right content type either way,
   * and no HTTP level test can see the difference. Found in Chromium, not here.
   */
  it('lets the delivered page load its own script and stylesheet', async () => {
    const harness = await createHarness({ staticDir: buildSite() });
    try {
      const index = await harness.app.inject({ method: 'GET', url: '/' });
      const csp = index.headers['content-security-policy'] as string | undefined;
      expect(allows(csp, 'script-src', "'self'"), csp).toBe(true);
      expect(allows(csp, 'style-src', "'self'"), csp).toBe(true);
      // The page talks to its own API and reads the screen.view event stream from it.
      expect(allows(csp, 'connect-src', "'self'"), csp).toBe(true);
    } finally {
      await harness.close();
    }
  });

  it('allows nothing from anywhere else', async () => {
    const harness = await createHarness({ staticDir: buildSite() });
    try {
      const csp = (await harness.app.inject({ method: 'GET', url: '/' })).headers[
        'content-security-policy'
      ] as string | undefined;
      for (const kind of ['script-src', 'style-src', 'connect-src', 'img-src']) {
        expect(allows(csp, kind, '*'), `${kind} in ${csp}`).toBe(false);
        expect(allows(csp, kind, "'unsafe-inline'"), `${kind} in ${csp}`).toBe(false);
        expect(allows(csp, kind, "'unsafe-eval'"), `${kind} in ${csp}`).toBe(false);
      }
      expect(directives(csp).get('frame-ancestors'), csp).toEqual(["'none'"]);
    } finally {
      await harness.close();
    }
  });

  it('keeps the strict policy on API answers even while serving the page', async () => {
    const harness = await createHarness({ staticDir: buildSite() });
    try {
      const api = await harness.app.inject({ method: 'GET', url: '/api/v1/gibtesnicht' });
      const csp = api.headers['content-security-policy'] as string | undefined;
      expect(allows(csp, 'script-src', "'self'"), csp).toBe(false);
      expect(directives(csp).get('default-src'), csp).toEqual(["'none'"]);
    } finally {
      await harness.close();
    }
  });

  it('keeps the strict policy on every path when the option is unset', async () => {
    const harness = await createHarness();
    try {
      for (const url of ['/', '/health', '/api/v1/gibtesnicht']) {
        const csp = (await harness.app.inject({ method: 'GET', url })).headers[
          'content-security-policy'
        ] as string | undefined;
        expect(directives(csp).get('default-src'), `${url}: ${csp}`).toEqual(["'none'"]);
      }
    } finally {
      await harness.close();
    }
  });
});
