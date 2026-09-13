import { randomBytes } from 'node:crypto';

import { describe, expect, it } from 'vitest';

import { sha256Hex } from '../../src/crypto/tokens.js';
import type { AgentSocketLike } from '../../src/services/agentConnections.js';
import {
  DEFAULT_METADATA,
  controlHeaders,
  createHarness,
  createTestDevice,
  createTestUser,
  login,
  randomPassword,
} from '../helpers/harness.js';
import type { Harness, LoggedIn } from '../helpers/harness.js';

interface Provisioned {
  readonly recordId: string;
  readonly publicDeviceId: string;
  readonly session: LoggedIn;
}

async function provision(harness: Harness): Promise<Provisioned> {
  const email = `sysinfo-${randomBytes(8).toString('hex')}@example.test`;
  const password = randomPassword();
  const userId = await createTestUser(harness, email, password);
  const session = await login(harness, email, password);
  const identity = createTestDevice();
  const record = await harness.context.repositories.devices.upsert({
    ownerId: userId,
    deviceId: identity.deviceId,
    publicKey: identity.publicKeyBase64,
    fingerprint: identity.fingerprint,
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
  return { recordId: record.id, publicDeviceId: record.deviceId, session };
}

class RespondingSocket implements AgentSocketLike {
  readonly sent: Array<Record<string, unknown>> = [];

  constructor(
    private readonly onFrame: (frame: Record<string, unknown>) => void = () => undefined,
  ) {}

  send(data: string): void {
    const frame = JSON.parse(data) as Record<string, unknown>;
    this.sent.push(frame);
    this.onFrame(frame);
  }

  close(): void {}
}

const SYSTEM_INFO = {
  manufacturer: 'Samsung',
  model: 'SM-S921B',
  osVersion: '16',
  sdkInt: 36,
  appVersion: '0.2.0',
  batteryPercent: 81,
  charging: false,
  storageTotalBytes: 256_000_000_000,
  storageFreeBytes: 91_000_000_000,
  networkType: 'wifi',
  deviceTime: '2026-09-13T14:00:00.000Z',
  lastAgentActivity: '2026-09-13T14:00:00.000Z',
} as const;

describe('live system.info route', () => {
  it('creates a one-shot remote session and returns the agent response', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      await harness.context.repositories.deviceCapabilities.setGranted({
        deviceId: owned.recordId,
        capability: 'system.info',
        granted: true,
        grantedBy: owned.session.userId,
      });

      const socket = new RespondingSocket((frame) => {
        if (frame.type !== 'system.info.request') {
          return;
        }
        const sessionId = frame.sessionId;
        if (typeof sessionId !== 'string') {
          throw new Error('system.info.request missing sessionId');
        }
        queueMicrotask(() => {
          harness.context.agentConnections.resolveResponse(
            owned.recordId,
            sessionId,
            SYSTEM_INFO,
          );
        });
      });
      const connection = harness.context.agentConnections.register(
        owned.recordId,
        socket,
        harness.clock.now(),
      );
      harness.context.agentConnections.setDeviceGrantedCapabilities(connection.id, ['system.info']);

      const response = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/devices/${owned.recordId}/system-info`,
        headers: controlHeaders(owned.session),
      });
      expect(response.statusCode).toBe(200);
      const body = response.json() as {
        sessionId: string;
        systemInfo: typeof SYSTEM_INFO;
      };
      expect(body.systemInfo).toEqual(SYSTEM_INFO);
      expect(socket.sent).toHaveLength(1);
      expect(socket.sent[0]?.type).toBe('system.info.request');
      expect(socket.sent[0]?.sessionId).toBe(body.sessionId);

      const remote = await harness.context.repositories.remoteSessions.findById(body.sessionId);
      expect(remote?.revokedAt).toBe(harness.clock.now());
      const audit = await harness.context.repositories.audit.listForDevice(owned.publicDeviceId, 20);
      expect(
        audit.some(
          (event) => event.eventType === 'system.info.request' && event.result === 'success',
        ),
      ).toBe(true);
    } finally {
      await harness.close();
    }
  });

  it('denies the request unless both server and device granted system.info', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      const connection = harness.context.agentConnections.register(
        owned.recordId,
        new RespondingSocket(),
        harness.clock.now(),
      );
      harness.context.agentConnections.setDeviceGrantedCapabilities(connection.id, ['system.info']);

      const serverDenied = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/devices/${owned.recordId}/system-info`,
        headers: controlHeaders(owned.session),
      });
      expect(serverDenied.statusCode).toBe(403);
      expect((serverDenied.json() as { error: { code: string } }).error.code).toBe(
        'CAPABILITY_DENIED',
      );

      await harness.context.repositories.deviceCapabilities.setGranted({
        deviceId: owned.recordId,
        capability: 'system.info',
        granted: true,
        grantedBy: owned.session.userId,
      });
      harness.context.agentConnections.setDeviceGrantedCapabilities(connection.id, []);

      const deviceDenied = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/devices/${owned.recordId}/system-info`,
        headers: controlHeaders(owned.session),
      });
      expect(deviceDenied.statusCode).toBe(403);
      expect((deviceDenied.json() as { error: { code: string } }).error.code).toBe(
        'CAPABILITY_DENIED',
      );
    } finally {
      await harness.close();
    }
  });

  it('fails immediately when no agent connection is active', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      await harness.context.repositories.deviceCapabilities.setGranted({
        deviceId: owned.recordId,
        capability: 'system.info',
        granted: true,
        grantedBy: owned.session.userId,
      });

      const response = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/devices/${owned.recordId}/system-info`,
        headers: controlHeaders(owned.session),
      });
      expect(response.statusCode).toBe(409);
      expect((response.json() as { error: { code: string } }).error.code).toBe('SESSION_EXPIRED');
    } finally {
      await harness.close();
    }
  });
});
