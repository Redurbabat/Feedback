import { randomBytes } from 'node:crypto';

import { describe, expect, it } from 'vitest';

import {
  CONTROL_ELEVATION_TTL_MS,
  HEARTBEAT_INTERVAL_MS,
  HEARTBEAT_MISS_LIMIT,
} from '../../src/constants.js';
import { sha256Hex } from '../../src/crypto/tokens.js';
import {
  DEFAULT_METADATA,
  TEST_ORIGIN,
  controlHeaders,
  createHarness,
  createTestDevice,
  createTestUser,
  elevate,
  login,
  randomPassword,
} from '../helpers/harness.js';
import type { Harness, LoggedIn, TestDevice } from '../helpers/harness.js';

interface ProvisionedDevice {
  readonly device: TestDevice;
  readonly recordId: string;
  readonly publicDeviceId: string;
  readonly token: string;
  readonly session: LoggedIn;
  /** The owner's credentials, so a test can take the second step in front of a grant. */
  readonly email: string;
  readonly password: string;
}

async function provisionOwnedDevice(harness: Harness): Promise<ProvisionedDevice> {
  const email = `owner-${randomBytes(8).toString('hex')}@example.test`;
  const password = randomPassword();
  const userId = await createTestUser(harness, email, password);
  const session = await login(harness, email, password);
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
  });
  return {
    device,
    recordId: record.id,
    publicDeviceId: record.deviceId,
    token,
    session,
    email,
    password,
  };
}

describe('device and agent routes', () => {
  it('authenticates /agent/me with a device token and refreshes last-seen', async () => {
    const harness = await createHarness();
    try {
      const provisioned = await provisionOwnedDevice(harness);

      const missing = await harness.app.inject({ method: 'GET', url: '/api/v1/agent/me' });
      expect(missing.statusCode).toBe(401);

      const malformed = await harness.app.inject({
        method: 'GET',
        url: '/api/v1/agent/me',
        headers: { authorization: 'Bearer not-a-32-byte-token' },
      });
      expect(malformed.statusCode).toBe(401);

      const response = await harness.app.inject({
        method: 'GET',
        url: '/api/v1/agent/me',
        headers: { authorization: `Bearer ${provisioned.token}` },
      });
      expect(response.statusCode).toBe(200);
      const body = response.json() as {
        device: { id: string; deviceId: string; lastSeenAt: string };
        capabilities: { serverGranted: string[] };
      };
      expect(body.device.id).toBe(provisioned.recordId);
      expect(body.device.deviceId).toBe(provisioned.publicDeviceId);
      expect(body.capabilities.serverGranted).toEqual([]);
      expect(Date.parse(body.device.lastSeenAt)).toBe(harness.clock.now());

      const stored = await harness.context.repositories.devices.findById(provisioned.recordId);
      expect(stored?.lastSeenAt).toBe(harness.clock.now());
    } finally {
      await harness.close();
    }
  });

  it('reports conservative online/offline presence in the Control Center list', async () => {
    const harness = await createHarness();
    try {
      const provisioned = await provisionOwnedDevice(harness);
      const headers = { cookie: provisioned.session.cookie, origin: TEST_ORIGIN };

      const before = await harness.app.inject({
        method: 'GET',
        url: '/api/v1/devices',
        headers,
      });
      expect(before.statusCode).toBe(200);
      expect((before.json() as { devices: Array<{ online: boolean }> }).devices[0]?.online).toBe(
        false,
      );

      const agent = await harness.app.inject({
        method: 'GET',
        url: '/api/v1/agent/me',
        headers: { authorization: `Bearer ${provisioned.token}` },
      });
      expect(agent.statusCode).toBe(200);

      const online = await harness.app.inject({
        method: 'GET',
        url: '/api/v1/devices',
        headers,
      });
      expect((online.json() as { devices: Array<{ online: boolean }> }).devices[0]?.online).toBe(
        true,
      );

      harness.clock.advance(HEARTBEAT_INTERVAL_MS * HEARTBEAT_MISS_LIMIT + 1);
      const offline = await harness.app.inject({
        method: 'GET',
        url: '/api/v1/devices',
        headers,
      });
      expect((offline.json() as { devices: Array<{ online: boolean }> }).devices[0]?.online).toBe(
        false,
      );
    } finally {
      await harness.close();
    }
  });

  it('updates only implemented server capabilities and exposes them to the agent', async () => {
    const harness = await createHarness();
    try {
      const provisioned = await provisionOwnedDevice(harness);
      await elevate(harness, provisioned.session, provisioned.password);

      const update = await harness.app.inject({
        method: 'PUT',
        url: `/api/v1/devices/${provisioned.recordId}/capabilities`,
        headers: controlHeaders(provisioned.session),
        payload: { grantedCapabilities: ['system.info'] },
      });
      expect(update.statusCode).toBe(200);
      expect(
        (update.json() as { serverGrantedCapabilities: string[] }).serverGrantedCapabilities,
      ).toEqual(['system.info']);

      const agent = await harness.app.inject({
        method: 'GET',
        url: '/api/v1/agent/me',
        headers: { authorization: `Bearer ${provisioned.token}` },
      });
      expect(agent.statusCode).toBe(200);
      expect(
        (agent.json() as { capabilities: { serverGranted: string[] } }).capabilities.serverGranted,
      ).toEqual(['system.info']);

      const unsupported = await harness.app.inject({
        method: 'PUT',
        url: `/api/v1/devices/${provisioned.recordId}/capabilities`,
        headers: controlHeaders(provisioned.session),
        // screen.control has no implementation behind it, so the server must refuse
        // to grant it no matter what the owner ticks in the control center.
        payload: { grantedCapabilities: ['screen.control'] },
      });
      expect(unsupported.statusCode).toBe(400);
      expect((unsupported.json() as { error: { code: string } }).error.code).toBe('UNSUPPORTED');
    } finally {
      await harness.close();
    }
  });

  it('revokes device tokens, sessions and capability grants atomically at the API boundary', async () => {
    const harness = await createHarness();
    try {
      const provisioned = await provisionOwnedDevice(harness);
      await harness.context.repositories.deviceCapabilities.setGranted({
        deviceId: provisioned.recordId,
        capability: 'system.info',
        granted: true,
        grantedBy: provisioned.session.userId,
      });
      const remote = await harness.context.repositories.remoteSessions.create({
        ownerId: provisioned.session.userId,
        deviceId: provisioned.recordId,
        expiresAt: harness.clock.now() + 60_000,
        requestedCapabilities: ['system.info'],
        approvedCapabilities: ['system.info'],
      });

      const revoke = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/devices/${provisioned.recordId}/revoke`,
        headers: controlHeaders(provisioned.session),
      });
      expect(revoke.statusCode).toBe(200);
      expect((revoke.json() as { status: string }).status).toBe('revoked');

      const stored = await harness.context.repositories.devices.findById(provisioned.recordId);
      expect(stored?.revokedAt).toBe(harness.clock.now());
      expect(
        await harness.context.repositories.deviceCapabilities.listForDevice(provisioned.recordId),
      ).toEqual([]);
      expect((await harness.context.repositories.remoteSessions.findById(remote.id))?.revokedAt).toBe(
        harness.clock.now(),
      );

      const agent = await harness.app.inject({
        method: 'GET',
        url: '/api/v1/agent/me',
        headers: { authorization: `Bearer ${provisioned.token}` },
      });
      expect(agent.statusCode).toBe(401);

      const audit = await harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${provisioned.recordId}/audit`,
        headers: { cookie: provisioned.session.cookie, origin: TEST_ORIGIN },
      });
      expect(audit.statusCode).toBe(200);
      const events = (audit.json() as { events: Array<{ eventType: string }> }).events;
      expect(events.some((event) => event.eventType === 'device.revoke')).toBe(true);
    } finally {
      await harness.close();
    }
  });

  it('does not reveal devices owned by another user', async () => {
    const harness = await createHarness();
    try {
      const provisioned = await provisionOwnedDevice(harness);
      const otherEmail = `other-${randomBytes(8).toString('hex')}@example.test`;
      const otherPassword = randomPassword();
      await createTestUser(harness, otherEmail, otherPassword);
      const otherSession = await login(harness, otherEmail, otherPassword);

      const response = await harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${provisioned.recordId}`,
        headers: { cookie: otherSession.cookie, origin: TEST_ORIGIN },
      });
      expect(response.statusCode).toBe(404);
    } finally {
      await harness.close();
    }
  });
  /*
   * THREAT_MODEL 4.5. The grant is the dangerous direction, so only the grant asks again.
   */
  describe('the second step in front of a capability grant', () => {
    it('refuses a grant from a session that has not confirmed the password', async () => {
      const harness = await createHarness();
      try {
        const provisioned = await provisionOwnedDevice(harness);

        const response = await harness.app.inject({
          method: 'PUT',
          url: `/api/v1/devices/${provisioned.recordId}/capabilities`,
          headers: controlHeaders(provisioned.session),
          payload: { grantedCapabilities: ['system.info'] },
        });

        expect(response.statusCode).toBe(403);
        expect((response.json() as { error: { code: string } }).error.code).toBe('REAUTH_REQUIRED');
        // And nothing was granted on the way to the refusal.
        expect(
          await harness.context.repositories.deviceCapabilities.listForDevice(provisioned.recordId),
        ).toEqual([]);
      } finally {
        await harness.close();
      }
    });

    /*
     * The asymmetry is deliberate: the moment the owner most wants to revoke is the moment
     * something is wrong, and that is the worst moment to send them looking for a password.
     */
    it('lets the same unelevated session revoke a capability', async () => {
      const harness = await createHarness();
      try {
        const provisioned = await provisionOwnedDevice(harness);
        await harness.context.repositories.deviceCapabilities.setGranted({
          deviceId: provisioned.recordId,
          capability: 'system.info',
          granted: true,
          grantedBy: provisioned.session.userId,
        });

        const response = await harness.app.inject({
          method: 'PUT',
          url: `/api/v1/devices/${provisioned.recordId}/capabilities`,
          headers: controlHeaders(provisioned.session),
          payload: { grantedCapabilities: [] },
        });

        expect(response.statusCode).toBe(200);
        expect(
          (response.json() as { serverGrantedCapabilities: string[] }).serverGrantedCapabilities,
        ).toEqual([]);
      } finally {
        await harness.close();
      }
    });

    /** Re-sending the set that is already granted adds nothing, so it needs no confirmation. */
    it('lets an unelevated session re-send the capabilities it already granted', async () => {
      const harness = await createHarness();
      try {
        const provisioned = await provisionOwnedDevice(harness);
        await harness.context.repositories.deviceCapabilities.setGranted({
          deviceId: provisioned.recordId,
          capability: 'system.info',
          granted: true,
          grantedBy: provisioned.session.userId,
        });

        const response = await harness.app.inject({
          method: 'PUT',
          url: `/api/v1/devices/${provisioned.recordId}/capabilities`,
          headers: controlHeaders(provisioned.session),
          payload: { grantedCapabilities: ['system.info'] },
        });

        expect(response.statusCode).toBe(200);
      } finally {
        await harness.close();
      }
    });

    it('refuses the wrong password and does not elevate the session', async () => {
      const harness = await createHarness();
      try {
        const provisioned = await provisionOwnedDevice(harness);

        const wrong = await harness.app.inject({
          method: 'POST',
          url: '/api/v1/auth/reauthenticate',
          headers: controlHeaders(provisioned.session),
          payload: { password: `${provisioned.password}-nicht` },
        });
        // Not 401: the session is still valid, only the confirmation failed. A 401 here would
        // send the Control Center back to the login screen over a typo.
        expect(wrong.statusCode).toBe(403);
        expect((wrong.json() as { error: { code: string } }).error.code).toBe('REAUTH_REQUIRED');

        const grant = await harness.app.inject({
          method: 'PUT',
          url: `/api/v1/devices/${provisioned.recordId}/capabilities`,
          headers: controlHeaders(provisioned.session),
          payload: { grantedCapabilities: ['system.info'] },
        });
        expect((grant.json() as { error: { code: string } }).error.code).toBe('REAUTH_REQUIRED');
      } finally {
        await harness.close();
      }
    });

    it('lets the confirmation expire', async () => {
      const harness = await createHarness();
      try {
        const provisioned = await provisionOwnedDevice(harness);
        await elevate(harness, provisioned.session, provisioned.password);

        harness.clock.advance(CONTROL_ELEVATION_TTL_MS + 1);

        const response = await harness.app.inject({
          method: 'PUT',
          url: `/api/v1/devices/${provisioned.recordId}/capabilities`,
          headers: controlHeaders(provisioned.session),
          payload: { grantedCapabilities: ['system.info'] },
        });
        expect(response.statusCode).toBe(403);
        expect((response.json() as { error: { code: string } }).error.code).toBe('REAUTH_REQUIRED');
      } finally {
        await harness.close();
      }
    });

    /** Confirming in one browser must not unlock the grant in another. */
    it('elevates one session, not the account', async () => {
      const harness = await createHarness();
      try {
        const provisioned = await provisionOwnedDevice(harness);
        const second = await login(harness, provisioned.email, provisioned.password);
        await elevate(harness, provisioned.session, provisioned.password);

        const response = await harness.app.inject({
          method: 'PUT',
          url: `/api/v1/devices/${provisioned.recordId}/capabilities`,
          headers: controlHeaders(second),
          payload: { grantedCapabilities: ['system.info'] },
        });
        expect(response.statusCode).toBe(403);
        expect((response.json() as { error: { code: string } }).error.code).toBe('REAUTH_REQUIRED');
      } finally {
        await harness.close();
      }
    });
  });
});
