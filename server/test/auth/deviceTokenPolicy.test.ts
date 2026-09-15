import { describe, expect, it } from 'vitest';

import { decideDeviceToken, rotationDue } from '../../src/auth/deviceTokenPolicy.js';
import { DEVICE_TOKEN_ROTATE_AFTER_MS } from '../../src/constants.js';
import type { DeviceTokenRecord } from '../../src/db/repositories/types.js';

const NOW = 1_700_000_000_000;

function token(overrides: Partial<DeviceTokenRecord> = {}): DeviceTokenRecord {
  return {
    id: 'token-1',
    deviceId: 'device-1',
    tokenHash: 'hash',
    createdAt: NOW - 1000,
    expiresAt: null,
    lastUsedAt: NOW - 500,
    revokedAt: null,
    replacedAt: null,
    replacedBy: null,
    ...overrides,
  };
}

describe('which party a device token belongs to', () => {
  it('accepts the current token', () => {
    expect(decideDeviceToken(token(), undefined, NOW).kind).toBe('accept');
  });

  it('refuses a revoked or expired token', () => {
    expect(decideDeviceToken(token({ revokedAt: NOW - 1 }), undefined, NOW)).toEqual({
      kind: 'reject',
      reason: 'revoked',
    });
    expect(decideDeviceToken(token({ expiresAt: NOW }), undefined, NOW)).toEqual({
      kind: 'reject',
      reason: 'expired',
    });
    // One millisecond of life left is still life.
    expect(decideDeviceToken(token({ expiresAt: NOW + 1 }), undefined, NOW).kind).toBe('accept');
  });

  /*
   * The case the whole design turns on. A rotation answer can be lost, and a device that never
   * received the new token has nothing but the old one. It must keep working - for as long as it
   * takes, not for a window somebody guessed.
   */
  it('keeps a superseded token alive while its successor is untouched', () => {
    const superseded = token({ replacedAt: NOW - 90 * 24 * 3600 * 1000, replacedBy: 'token-2' });
    const successor = token({ id: 'token-2', lastUsedAt: null });

    expect(decideDeviceToken(superseded, successor, NOW).kind).toBe('accept');
  });

  /*
   * And the case it exists for. Somebody picked the successor up; whoever is now presenting the
   * predecessor is a second holder of the same chain.
   */
  it('reports reuse when the successor has already been used', () => {
    const superseded = token({ replacedAt: NOW - 60_000, replacedBy: 'token-2' });
    const successor = token({ id: 'token-2', lastUsedAt: NOW - 30_000 });

    expect(decideDeviceToken(superseded, successor, NOW).kind).toBe('reuse_detected');
  });

  /** Reuse is a statement about two live holders. A revoked chain is simply over. */
  it('does not call it reuse when the chain was already revoked', () => {
    const superseded = token({ revokedAt: NOW - 10, replacedAt: NOW - 60_000, replacedBy: 'token-2' });
    const successor = token({ id: 'token-2', lastUsedAt: NOW - 30_000, revokedAt: NOW - 10 });

    expect(decideDeviceToken(superseded, successor, NOW).kind).toBe('reject');
  });

  it('refuses a successor that cannot be looked up rather than guessing', () => {
    const superseded = token({ replacedAt: NOW - 60_000, replacedBy: 'token-gone' });

    expect(decideDeviceToken(superseded, undefined, NOW)).toEqual({
      kind: 'reject',
      reason: 'broken_chain',
    });
  });
});

describe('when the next connection replaces the token', () => {
  it('waits for the rotation interval and then asks every time', () => {
    const fresh = token({ createdAt: NOW - DEVICE_TOKEN_ROTATE_AFTER_MS + 1 });
    const due = token({ createdAt: NOW - DEVICE_TOKEN_ROTATE_AFTER_MS });

    expect(rotationDue(fresh, NOW)).toBe(false);
    expect(rotationDue(due, NOW)).toBe(true);
  });

  /*
   * A token issued "in the future" by a server whose clock moved backwards must not rotate on
   * every single reconnect - that would burn a chain link per connection attempt.
   */
  it('does not rotate a token that is younger than no time at all', () => {
    expect(rotationDue(token({ createdAt: NOW + 60_000 }), NOW)).toBe(false);
  });
});
