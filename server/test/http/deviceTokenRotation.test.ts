import { randomBytes } from 'node:crypto';

import { describe, expect, it } from 'vitest';

import {
  DEVICE_TOKEN_ROTATE_AFTER_MS,
  DEVICE_TOKEN_TTL_MS,
} from '../../src/constants.js';
import { sha256Hex } from '../../src/crypto/tokens.js';
import {
  DEFAULT_METADATA,
  createHarness,
  createTestDevice,
  createTestUser,
  login,
  randomPassword,
} from '../helpers/harness.js';
import type { Harness } from '../helpers/harness.js';

/**
 * The token chain end to end (THREAT_MODEL 4.13).
 *
 * Driven through the real routes rather than the repository, because the interesting part is the
 * order: the device rotates before it connects, and what the server does with the old token
 * depends on whether anybody used the new one.
 */

interface PairedDevice {
  readonly deviceId: string;
  readonly recordId: string;
  token: string;
}

async function pairDevice(harness: Harness): Promise<PairedDevice> {
  const email = `owner-${randomBytes(8).toString('hex')}@example.test`;
  const password = randomPassword();
  const userId = await createTestUser(harness, email, password);
  await login(harness, email, password);
  const device = createTestDevice();
  const record = await harness.context.repositories.devices.upsert({
    ownerId: userId,
    deviceId: device.deviceId,
    publicKey: device.publicKeyBase64,
    fingerprint: device.fingerprint,
    name: DEFAULT_METADATA.deviceName,
    platform: DEFAULT_METADATA.platform,
    osVersion: DEFAULT_METADATA.osVersion,
    sdkInt: DEFAULT_METADATA.sdkInt,
    appVersion: DEFAULT_METADATA.appVersion,
  });
  const token = randomBytes(32).toString('base64url');
  await harness.context.repositories.deviceTokens.create({
    deviceId: record.id,
    tokenHash: sha256Hex(token),
    createdAt: harness.clock.now(),
    expiresAt: harness.clock.now() + DEVICE_TOKEN_TTL_MS,
  });
  return { deviceId: record.deviceId, recordId: record.id, token };
}

function rotate(harness: Harness, token: string): Promise<{ statusCode: number; body: string }> {
  return harness.app.inject({
    method: 'POST',
    url: '/api/v1/agent/token',
    headers: { authorization: `Bearer ${token}` },
    payload: {},
  });
}

function me(harness: Harness, token: string): Promise<{ statusCode: number; body: string }> {
  return harness.app.inject({
    method: 'GET',
    url: '/api/v1/agent/me',
    headers: { authorization: `Bearer ${token}` },
  });
}

function errorCode(response: { body: string }): string {
  return (JSON.parse(response.body) as { error: { code: string } }).error.code;
}

describe('device token rotation', () => {
  it('says "not yet" until the token is old enough, and changes nothing', async () => {
    const harness = await createHarness();
    try {
      const device = await pairDevice(harness);

      harness.clock.advance(DEVICE_TOKEN_ROTATE_AFTER_MS - 1);
      const early = await rotate(harness, device.token);

      expect(early.statusCode).toBe(200);
      expect((JSON.parse(early.body) as { rotated: boolean }).rotated).toBe(false);
      expect((await me(harness, device.token)).statusCode).toBe(200);
    } finally {
      await harness.close();
    }
  });

  it('hands out a new token once the interval has passed and keeps both working until the new one is used', async () => {
    const harness = await createHarness();
    try {
      const device = await pairDevice(harness);
      harness.clock.advance(DEVICE_TOKEN_ROTATE_AFTER_MS);

      const rotated = await rotate(harness, device.token);
      expect(rotated.statusCode).toBe(200);
      const body = JSON.parse(rotated.body) as {
        rotated: boolean;
        deviceToken: string;
        expiresAt: string;
      };
      expect(body.rotated).toBe(true);
      expect(body.deviceToken).not.toBe(device.token);
      expect(Date.parse(body.expiresAt)).toBe(harness.clock.now() + DEVICE_TOKEN_TTL_MS);

      // The answer could still be lost on the way. Until the new token is used, the old one is
      // all the device has.
      expect((await me(harness, device.token)).statusCode).toBe(200);

      expect((await me(harness, body.deviceToken)).statusCode).toBe(200);
    } finally {
      await harness.close();
    }
  });

  it('kills the old token the moment the new one is used', async () => {
    const harness = await createHarness();
    try {
      const device = await pairDevice(harness);
      harness.clock.advance(DEVICE_TOKEN_ROTATE_AFTER_MS);
      const next = (JSON.parse((await rotate(harness, device.token)).body) as {
        deviceToken: string;
      }).deviceToken;

      expect((await me(harness, next)).statusCode).toBe(200);

      // Now the predecessor is a second copy of a chain somebody else is holding.
      const stale = await me(harness, device.token);
      expect(stale.statusCode).toBe(403);
      expect(errorCode(stale)).toBe('DEVICE_REVOKED');
    } finally {
      await harness.close();
    }
  });

  /*
   * The theft this is for. A copy of the token is taken, the thief rotates first, and the real
   * device comes back with what it still believes is its token.
   */
  it('ends the pairing when a stolen copy and the device both use the chain', async () => {
    const harness = await createHarness();
    try {
      const device = await pairDevice(harness);
      const stolen = device.token;
      harness.clock.advance(DEVICE_TOKEN_ROTATE_AFTER_MS);

      const thiefToken = (JSON.parse((await rotate(harness, stolen)).body) as {
        deviceToken: string;
      }).deviceToken;
      expect((await me(harness, thiefToken)).statusCode).toBe(200);

      const device_returns = await me(harness, stolen);
      expect(device_returns.statusCode).toBe(403);
      expect(errorCode(device_returns)).toBe('DEVICE_REVOKED');

      // And the thief loses it too - that is the point. Whoever holds which half is exactly what
      // the server cannot know, so neither half survives.
      expect((await me(harness, thiefToken)).statusCode).toBe(403);

      const stored = await harness.context.repositories.devices.findById(device.recordId);
      expect(stored?.revokedAt).toBe(harness.clock.now());

      const events = await harness.context.repositories.audit.listForDevice(device.deviceId, 50);
      expect(events.some((event) => event.eventType === 'device.token.reuse')).toBe(true);
    } finally {
      await harness.close();
    }
  });

  /** A rotation whose answer was lost leaves no usable leftovers behind. */
  it('revokes a successor that was issued but never picked up', async () => {
    const harness = await createHarness();
    try {
      const device = await pairDevice(harness);
      harness.clock.advance(DEVICE_TOKEN_ROTATE_AFTER_MS);

      const abandoned = (JSON.parse((await rotate(harness, device.token)).body) as {
        deviceToken: string;
      }).deviceToken;
      // The device never saw it, so it asks again with the only token it has.
      const second = (JSON.parse((await rotate(harness, device.token)).body) as {
        deviceToken: string;
      }).deviceToken;

      expect((await me(harness, abandoned)).statusCode).toBe(401);
      expect((await me(harness, second)).statusCode).toBe(200);
    } finally {
      await harness.close();
    }
  });

  it('refuses a token that outlived its window without ever rotating', async () => {
    const harness = await createHarness();
    try {
      const device = await pairDevice(harness);
      harness.clock.advance(DEVICE_TOKEN_TTL_MS);

      const response = await me(harness, device.token);
      expect(response.statusCode).toBe(401);
      // Not "revoked": nothing was taken away, the token simply ran out.
      expect(errorCode(response)).toBe('UNAUTHORIZED');
      // And it cannot rotate its way back either.
      expect((await rotate(harness, device.token)).statusCode).toBe(401);
    } finally {
      await harness.close();
    }
  });

  it('refuses to rotate without a token at all', async () => {
    const harness = await createHarness();
    try {
      const response = await harness.app.inject({
        method: 'POST',
        url: '/api/v1/agent/token',
        payload: {},
      });
      expect(response.statusCode).toBe(401);
    } finally {
      await harness.close();
    }
  });
});
