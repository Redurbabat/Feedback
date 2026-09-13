import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { CSRF_HEADER_NAME, SESSION_COOKIE_NAME } from '../../src/auth/sessionService.js';
import type { Harness } from '../helpers/harness.js';
import {
  TEST_ORIGIN,
  createHarness,
  createTestUser,
  login,
  randomPassword,
} from '../helpers/harness.js';

const EMAIL = 'owner@example.test';

describe('control center authentication', () => {
  let harness: Harness;
  let password: string;

  beforeEach(async () => {
    harness = await createHarness();
    password = randomPassword();
    await createTestUser(harness, EMAIL, password);
  });

  afterEach(async () => {
    await harness.close();
  });

  it('logs in, sets a hardened cookie and hands out a CSRF token', async () => {
    const response = await harness.app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      headers: { origin: TEST_ORIGIN, 'sec-fetch-site': 'same-origin' },
      payload: { email: EMAIL, password },
    });

    expect(response.statusCode).toBe(200);
    const body = response.json() as { csrfToken: string; user: { email: string } };
    expect(body.user.email).toBe(EMAIL);
    expect(body.csrfToken.length).toBeGreaterThan(20);

    const cookie = response.cookies.find((entry) => entry.name === SESSION_COOKIE_NAME);
    expect(cookie).toBeDefined();
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.sameSite?.toLowerCase()).toBe('strict');
    expect(cookie?.path).toBe('/');
    // Not production in tests, so Secure stays off; the flag is driven by config.
    expect(cookie?.secure ?? false).toBe(false);
    expect(response.body).not.toContain(password);
  });

  it('answers a wrong password exactly like an unknown account', async () => {
    const wrongPassword = await harness.app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      headers: { origin: TEST_ORIGIN },
      payload: { email: EMAIL, password: randomPassword() },
    });
    const unknownAccount = await harness.app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      headers: { origin: TEST_ORIGIN },
      payload: { email: 'nobody@example.test', password: randomPassword() },
    });

    expect(wrongPassword.statusCode).toBe(401);
    expect(unknownAccount.statusCode).toBe(401);
    expect(wrongPassword.json()).toEqual(unknownAccount.json());
    expect((wrongPassword.json() as { error: { code: string } }).error.code).toBe('UNAUTHORIZED');
    expect(wrongPassword.cookies.find((entry) => entry.name === SESSION_COOKIE_NAME)).toBeUndefined();
  });

  it('records login success and failure in the audit trail', async () => {
    await harness.app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      headers: { origin: TEST_ORIGIN },
      payload: { email: EMAIL, password: randomPassword() },
    });
    await login(harness, EMAIL, password);

    const events = await harness.context.repositories.audit.listRecent(10);
    const types = events.map((event) => event.eventType);
    expect(types).toContain('auth.login.failure');
    expect(types).toContain('auth.login.success');
    for (const event of events) {
      expect(event.detail ?? '').not.toContain(password);
    }
  });

  it('rejects a login from a foreign origin', async () => {
    const response = await harness.app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      headers: { origin: 'https://attacker.example', 'sec-fetch-site': 'cross-site' },
      payload: { email: EMAIL, password },
    });

    expect(response.statusCode).toBe(403);
    expect((response.json() as { error: { code: string } }).error.code).toBe('FORBIDDEN');
  });

  it('rejects a state changing request without an Origin header', async () => {
    const response = await harness.app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      payload: { email: EMAIL, password },
    });
    expect(response.statusCode).toBe(403);
  });

  it('rejects a cross-site Sec-Fetch-Site even with an allowed Origin', async () => {
    const response = await harness.app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      headers: { origin: TEST_ORIGIN, 'sec-fetch-site': 'cross-site' },
      payload: { email: EMAIL, password },
    });
    expect(response.statusCode).toBe(403);
  });

  it('rotates the session on a second login and invalidates the old cookie', async () => {
    const first = await login(harness, EMAIL, password);
    const second = await harness.app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      headers: { origin: TEST_ORIGIN, cookie: first.cookie },
      payload: { email: EMAIL, password },
    });
    expect(second.statusCode).toBe(200);

    const withOldCookie = await harness.app.inject({
      method: 'GET',
      url: '/api/v1/auth/session',
      headers: { cookie: first.cookie },
    });
    expect(withOldCookie.statusCode).toBe(401);

    const newCookie = second.cookies.find((entry) => entry.name === SESSION_COOKIE_NAME);
    const withNewCookie = await harness.app.inject({
      method: 'GET',
      url: '/api/v1/auth/session',
      headers: { cookie: `${SESSION_COOKIE_NAME}=${newCookie?.value ?? ''}` },
    });
    expect(withNewCookie.statusCode).toBe(200);
  });

  it('returns the session and a stable CSRF token', async () => {
    const session = await login(harness, EMAIL, password);
    const response = await harness.app.inject({
      method: 'GET',
      url: '/api/v1/auth/session',
      headers: { cookie: session.cookie },
    });

    expect(response.statusCode).toBe(200);
    const body = response.json() as { csrfToken: string; user: { email: string } };
    expect(body.user.email).toBe(EMAIL);
    expect(body.csrfToken).toBe(session.csrfToken);
  });

  it('refuses an unauthenticated session request', async () => {
    const response = await harness.app.inject({ method: 'GET', url: '/api/v1/auth/session' });
    expect(response.statusCode).toBe(401);
    expect((response.json() as { error: { code: string } }).error.code).toBe('UNAUTHORIZED');
  });

  it('refuses a logout without the CSRF header', async () => {
    const session = await login(harness, EMAIL, password);
    const response = await harness.app.inject({
      method: 'POST',
      url: '/api/v1/auth/logout',
      headers: { origin: TEST_ORIGIN, cookie: session.cookie },
    });

    expect(response.statusCode).toBe(403);
    expect((response.json() as { error: { code: string } }).error.code).toBe('FORBIDDEN');

    // The session must still be usable after the rejected request.
    const still = await harness.app.inject({
      method: 'GET',
      url: '/api/v1/auth/session',
      headers: { cookie: session.cookie },
    });
    expect(still.statusCode).toBe(200);
  });

  it('refuses a logout with a CSRF token from a different session', async () => {
    const session = await login(harness, EMAIL, password);
    const otherEmail = 'second@example.test';
    const otherPassword = randomPassword();
    await createTestUser(harness, otherEmail, otherPassword);
    const other = await login(harness, otherEmail, otherPassword);

    const response = await harness.app.inject({
      method: 'POST',
      url: '/api/v1/auth/logout',
      headers: {
        origin: TEST_ORIGIN,
        cookie: session.cookie,
        [CSRF_HEADER_NAME]: other.csrfToken,
      },
    });
    expect(response.statusCode).toBe(403);
  });

  it('logs out and invalidates the session', async () => {
    const session = await login(harness, EMAIL, password);
    const response = await harness.app.inject({
      method: 'POST',
      url: '/api/v1/auth/logout',
      headers: {
        origin: TEST_ORIGIN,
        cookie: session.cookie,
        [CSRF_HEADER_NAME]: session.csrfToken,
      },
    });
    expect(response.statusCode).toBe(200);

    const after = await harness.app.inject({
      method: 'GET',
      url: '/api/v1/auth/session',
      headers: { cookie: session.cookie },
    });
    expect(after.statusCode).toBe(401);
  });

  it('treats an expired session as unauthenticated', async () => {
    const session = await login(harness, EMAIL, password);
    harness.clock.advance(harness.context.config.sessionTtlMs + 1000);

    const response = await harness.app.inject({
      method: 'GET',
      url: '/api/v1/auth/session',
      headers: { cookie: session.cookie },
    });
    expect(response.statusCode).toBe(401);
  });

  it('rejects an unknown body field and an invalid e-mail', async () => {
    const extraField = await harness.app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      headers: { origin: TEST_ORIGIN },
      payload: { email: EMAIL, password, admin: true },
    });
    expect(extraField.statusCode).toBe(400);
    expect((extraField.json() as { error: { code: string } }).error.code).toBe('INVALID_MESSAGE');

    const badEmail = await harness.app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      headers: { origin: TEST_ORIGIN },
      payload: { email: 'not-an-email', password },
    });
    expect(badEmail.statusCode).toBe(400);
  });

  it('sets security headers and a request id on every response', async () => {
    const response = await harness.app.inject({ method: 'GET', url: '/api/v1/auth/session' });
    expect(response.headers['x-content-type-options']).toBe('nosniff');
    expect(response.headers['x-frame-options']).toBe('DENY');
    expect(response.headers['referrer-policy']).toBe('no-referrer');
    expect(response.headers['cache-control']).toBe('no-store');
    expect(String(response.headers['x-request-id'] ?? '')).toHaveLength(36);
  });
});
