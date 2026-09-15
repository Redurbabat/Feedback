import { describe, expect, it } from 'vitest';

import { ApiError } from './api.ts';
import { confirmationRefusalFor, nextCapabilities, refusalFor } from './elevation.ts';
import type { CapabilityAttempt } from './elevation.ts';

const attempt = (overrides: Partial<CapabilityAttempt> = {}): CapabilityAttempt => ({
  deviceId: 'dev-1',
  deviceName: 'Testgeraet',
  capability: 'system.info',
  grants: true,
  grantedCapabilities: ['system.info'],
  ...overrides,
});

const reauthRequired = new ApiError(403, 'REAUTH_REQUIRED', 'Bitte Passwort bestaetigen');

describe('the refused capability change', () => {
  it('asks for the password when a grant was refused', () => {
    const refusal = refusalFor(reauthRequired, attempt());

    expect(refusal.kind).toBe('ask_for_password');
    if (refusal.kind !== 'ask_for_password') return;
    expect(refusal.pending.deviceId).toBe('dev-1');
    expect(refusal.pending.capability).toBe('system.info');
  });

  /*
   * The point of the whole mechanism is that revoking stays cheap. A password prompt while the
   * owner is closing something down would teach exactly the habit it protects against - so even
   * if the server ever answered REAUTH_REQUIRED there, the browser does not ask.
   */
  it('never asks for the password while something is being taken away', () => {
    const refusal = refusalFor(
      reauthRequired,
      attempt({ grants: false, grantedCapabilities: [] }),
    );

    expect(refusal.kind).toBe('report');
  });

  it('reports every other error instead of asking for a password', () => {
    expect(refusalFor(new ApiError(403, 'CAPABILITY_DENIED', 'nein'), attempt()).kind).toBe('report');
    expect(refusalFor(new ApiError(0, null, 'offline'), attempt()).kind).toBe('report');
    expect(refusalFor(new Error('kaputt'), attempt()).kind).toBe('report');
    expect(refusalFor('REAUTH_REQUIRED', attempt()).kind).toBe('report');
  });

  /*
   * The retry repeats the refused set. Rebuilding it from a device view that a poll refreshed in
   * the meantime would send a different set than the one the owner confirmed.
   */
  it('keeps the refused set verbatim, detached from the caller', () => {
    const granted = ['system.info', 'files.read'];
    const refusal = refusalFor(reauthRequired, attempt({ grantedCapabilities: granted }));
    granted.push('screen.view');

    expect(refusal.kind).toBe('ask_for_password');
    if (refusal.kind !== 'ask_for_password') return;
    expect(refusal.pending.grantedCapabilities).toEqual(['system.info', 'files.read']);
  });
});

describe('the refused confirmation', () => {
  it('keeps the prompt standing when the password was wrong', () => {
    expect(confirmationRefusalFor(reauthRequired)).toBe('wrong_password');
  });

  /* An expired session is the one answer that must NOT leave the prompt asking for a password. */
  it('falls through to the session handling when the session is gone', () => {
    expect(confirmationRefusalFor(new ApiError(401, 'UNAUTHORIZED', 'Keine gueltige Anmeldung')))
      .toBe('report');
  });

  it('falls through for a rate limit and for a failed request', () => {
    expect(confirmationRefusalFor(new ApiError(429, 'RATE_LIMITED', 'zu viele'))).toBe('report');
    expect(confirmationRefusalFor(new ApiError(0, null, 'offline'))).toBe('report');
  });
});

describe('the set a single toggle sends', () => {
  it('adds without dropping what is already granted', () => {
    expect(nextCapabilities(['files.read'], 'system.info', true)).toEqual([
      'files.read',
      'system.info',
    ]);
  });

  it('removes only the one capability', () => {
    expect(nextCapabilities(['files.read', 'system.info'], 'files.read', false)).toEqual([
      'system.info',
    ]);
  });

  it('is unchanged when the capability is already in the state asked for', () => {
    expect(nextCapabilities(['system.info'], 'system.info', true)).toEqual(['system.info']);
    expect(nextCapabilities([], 'system.info', false)).toEqual([]);
  });
});
