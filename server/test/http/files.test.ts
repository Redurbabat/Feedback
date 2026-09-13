import { createHash, randomBytes } from 'node:crypto';

import { describe, expect, it } from 'vitest';

import { FILES_SESSION_TTL_MS } from '../../src/constants.js';
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
  const email = `files-${randomBytes(8).toString('hex')}@example.test`;
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

/** Grants files.read on both sides and connects a fake agent. */
async function connect(
  harness: Harness,
  owned: Provisioned,
  onFrame: (frame: Record<string, unknown>) => void = () => undefined,
): Promise<RespondingSocket> {
  await harness.context.repositories.deviceCapabilities.setGranted({
    deviceId: owned.recordId,
    capability: 'files.read',
    granted: true,
    grantedBy: owned.session.userId,
  });
  const socket = new RespondingSocket(onFrame);
  const connection = harness.context.agentConnections.register(
    owned.recordId,
    socket,
    harness.clock.now(),
  );
  harness.context.agentConnections.setDeviceGrantedCapabilities(connection.id, ['files.read']);
  return socket;
}

async function openSession(harness: Harness, owned: Provisioned): Promise<string> {
  const response = await harness.app.inject({
    method: 'POST',
    url: `/api/v1/devices/${owned.recordId}/files/session`,
    headers: controlHeaders(owned.session),
  });
  expect(response.statusCode).toBe(200);
  return (response.json() as { sessionId: string }).sessionId;
}

function errorCode(response: { json: () => unknown }): string {
  return (response.json() as { error: { code: string } }).error.code;
}

describe('files.read routes', () => {
  it('opens a session only when both sides granted the capability', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);

      // Nothing granted at all.
      const denied = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/devices/${owned.recordId}/files/session`,
        headers: controlHeaders(owned.session),
      });
      expect(denied.statusCode).toBe(403);
      expect(errorCode(denied)).toBe('CAPABILITY_DENIED');

      // Server granted, but the device has not.
      await harness.context.repositories.deviceCapabilities.setGranted({
        deviceId: owned.recordId,
        capability: 'files.read',
        granted: true,
        grantedBy: owned.session.userId,
      });
      const socket = new RespondingSocket();
      const connection = harness.context.agentConnections.register(
        owned.recordId,
        socket,
        harness.clock.now(),
      );
      const stillDenied = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/devices/${owned.recordId}/files/session`,
        headers: controlHeaders(owned.session),
      });
      expect(stillDenied.statusCode).toBe(403);
      expect(errorCode(stillDenied)).toBe('CAPABILITY_DENIED');

      // Now the device grants it too.
      harness.context.agentConnections.setDeviceGrantedCapabilities(connection.id, ['files.read']);
      const opened = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/devices/${owned.recordId}/files/session`,
        headers: controlHeaders(owned.session),
      });
      expect(opened.statusCode).toBe(200);
      const body = opened.json() as { sessionId: string; expiresAt: string };
      const session = await harness.context.repositories.remoteSessions.findById(body.sessionId);
      expect(session?.approvedCapabilities).toEqual(['files.read']);
      // A files session outlives a one-shot session, otherwise browsing expires halfway.
      expect((session?.expiresAt ?? 0) - harness.clock.now()).toBe(FILES_SESSION_TTL_MS);
    } finally {
      await harness.close();
    }
  });

  it('lists shares from the device', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      await connect(harness, owned, (frame) => {
        if (frame.type !== 'files.shares.request') {
          return;
        }
        queueMicrotask(() => {
          harness.context.agentConnections.resolveResponse(
            owned.recordId,
            String(frame.messageId),
            {
              shares: [
                {
                  shareId: 'abc123',
                  displayName: 'Dokumente',
                  kind: 'tree',
                  addedAt: '2026-09-13T10:00:00.000Z',
                },
              ],
            },
          );
        });
      });
      const sessionId = await openSession(harness, owned);

      const response = await harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${owned.recordId}/files/shares?sessionId=${sessionId}`,
        headers: controlHeaders(owned.session),
      });

      expect(response.statusCode).toBe(200);
      expect(response.json()).toEqual({
        shares: [
          {
            shareId: 'abc123',
            displayName: 'Dokumente',
            kind: 'tree',
            addedAt: '2026-09-13T10:00:00.000Z',
          },
        ],
      });
    } finally {
      await harness.close();
    }
  });

  it('refuses a listing whose entry name carries a path component', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      await connect(harness, owned, (frame) => {
        if (frame.type !== 'files.list.request') {
          return;
        }
        queueMicrotask(() => {
          harness.context.agentConnections.resolveResponse(
            owned.recordId,
            String(frame.messageId),
            {
              shareId: 'abc123',
              entries: [
                {
                  id: 'file1',
                  // A name that would escape the folder on the receiving side.
                  name: '../../etc/passwd',
                  mimeType: 'text/plain',
                  size: 10,
                  modifiedAt: null,
                  kind: 'file',
                },
              ],
            },
          );
        });
      });
      const sessionId = await openSession(harness, owned);

      const response = await harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${owned.recordId}/files/entries?sessionId=${sessionId}&shareId=abc123`,
        headers: controlHeaders(owned.session),
      });

      expect(response.statusCode).toBe(400);
      expect(errorCode(response)).toBe('INVALID_MESSAGE');
    } finally {
      await harness.close();
    }
  });

  it('refuses a listing that answers about a different share', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      await connect(harness, owned, (frame) => {
        if (frame.type !== 'files.list.request') {
          return;
        }
        queueMicrotask(() => {
          harness.context.agentConnections.resolveResponse(
            owned.recordId,
            String(frame.messageId),
            { shareId: 'somethingelse', entries: [] },
          );
        });
      });
      const sessionId = await openSession(harness, owned);

      const response = await harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${owned.recordId}/files/entries?sessionId=${sessionId}&shareId=abc123`,
        headers: controlHeaders(owned.session),
      });

      expect(response.statusCode).toBe(400);
      expect(errorCode(response)).toBe('INVALID_MESSAGE');
    } finally {
      await harness.close();
    }
  });

  it('streams a file through the bridge and hands over the exact bytes', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      // Three chunks, the last one short, so the reassembly is not trivially aligned.
      const content = Buffer.concat([
        Buffer.alloc(1000, 0x41),
        Buffer.alloc(1000, 0x42),
        Buffer.from('tail'),
      ]);
      const digest = createHash('sha256').update(content).digest('hex');

      await connect(harness, owned, (frame) => {
        if (frame.type === 'files.metadata.request') {
          queueMicrotask(() => {
            harness.context.agentConnections.resolveResponse(
              owned.recordId,
              String(frame.messageId),
              {
                entry: {
                  id: 'file1',
                  name: 'Bericht.pdf',
                  mimeType: 'application/pdf',
                  size: content.length,
                  modifiedAt: '2026-09-13T10:00:00.000Z',
                  kind: 'file',
                },
              },
            );
          });
          return;
        }

        if (frame.type === 'files.download.start') {
          const payload = frame.payload as { transferId: string };
          queueMicrotask(() => {
            const pieces = [content.subarray(0, 1000), content.subarray(1000, 2000), content.subarray(2000)];
            pieces.forEach((piece, index) => {
              harness.context.fileTransfers.handleChunk({
                deviceId: owned.recordId,
                transferId: payload.transferId,
                sequence: index,
                data: piece,
                last: index === pieces.length - 1,
                now: harness.clock.now(),
              });
            });
            harness.context.fileTransfers.handleComplete({
              deviceId: owned.recordId,
              transferId: payload.transferId,
              totalBytes: content.length,
              sha256: digest,
            });
          });
        }
      });
      const sessionId = await openSession(harness, owned);

      const response = await harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${owned.recordId}/files/content?sessionId=${sessionId}&shareId=abc123&fileId=file1`,
        headers: controlHeaders(owned.session),
      });

      expect(response.statusCode).toBe(200);
      expect(response.rawPayload.equals(content)).toBe(true);
      expect(response.headers['content-type']).toBe('application/pdf');
      expect(response.headers['content-disposition']).toContain('Bericht.pdf');
      // The browser must not be allowed to guess a different type for a file the
      // owner shared for reading.
      expect(response.headers['x-content-type-options']).toBe('nosniff');
      // Nothing is kept: the transfer is gone once the last byte went out.
      expect(harness.context.fileTransfers.size).toBe(0);
    } finally {
      await harness.close();
    }
  });

  it('refuses a file larger than the configured download limit before any transfer starts', async () => {
    const harness = await createHarness({ fileMaxDownloadBytes: 1024 });
    try {
      const owned = await provision(harness);
      const socket = await connect(harness, owned, (frame) => {
        if (frame.type !== 'files.metadata.request') {
          return;
        }
        queueMicrotask(() => {
          harness.context.agentConnections.resolveResponse(
            owned.recordId,
            String(frame.messageId),
            {
              entry: {
                id: 'file1',
                name: 'gross.bin',
                mimeType: null,
                size: 4096,
                modifiedAt: null,
                kind: 'file',
              },
            },
          );
        });
      });
      const sessionId = await openSession(harness, owned);

      const response = await harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${owned.recordId}/files/content?sessionId=${sessionId}&shareId=abc123&fileId=file1`,
        headers: controlHeaders(owned.session),
      });

      expect(response.statusCode).toBe(400);
      expect(errorCode(response)).toBe('UNSUPPORTED');
      // The device was never asked to start reading.
      expect(socket.sent.some((frame) => frame.type === 'files.download.start')).toBe(false);
      expect(harness.context.fileTransfers.size).toBe(0);
    } finally {
      await harness.close();
    }
  });

  it('stops answering as soon as the capability is withdrawn, without closing the session', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      await connect(harness, owned, (frame) => {
        if (frame.type !== 'files.shares.request') {
          return;
        }
        queueMicrotask(() => {
          harness.context.agentConnections.resolveResponse(
            owned.recordId,
            String(frame.messageId),
            { shares: [] },
          );
        });
      });
      const sessionId = await openSession(harness, owned);

      const before = await harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${owned.recordId}/files/shares?sessionId=${sessionId}`,
        headers: controlHeaders(owned.session),
      });
      expect(before.statusCode).toBe(200);

      await harness.context.repositories.deviceCapabilities.setGranted({
        deviceId: owned.recordId,
        capability: 'files.read',
        granted: false,
        grantedBy: owned.session.userId,
      });

      const after = await harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${owned.recordId}/files/shares?sessionId=${sessionId}`,
        headers: controlHeaders(owned.session),
      });
      // The session is still open; the capability is what stops it. Checking per
      // request rather than per session is what makes the withdrawal immediate.
      expect(after.statusCode).toBe(403);
      expect(errorCode(after)).toBe('CAPABILITY_DENIED');
    } finally {
      await harness.close();
    }
  });

  it('closes a session on request and refuses it afterwards', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      await connect(harness, owned);
      const sessionId = await openSession(harness, owned);

      const closed = await harness.app.inject({
        method: 'DELETE',
        url: `/api/v1/devices/${owned.recordId}/files/session`,
        headers: controlHeaders(owned.session),
        payload: { sessionId },
      });
      expect(closed.statusCode).toBe(200);

      const after = await harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${owned.recordId}/files/shares?sessionId=${sessionId}`,
        headers: controlHeaders(owned.session),
      });
      expect(after.statusCode).toBe(409);
      expect(errorCode(after)).toBe('SESSION_EXPIRED');
    } finally {
      await harness.close();
    }
  });

  it('hides a session that belongs to another owner', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      const stranger = await provision(harness);
      await connect(harness, owned);
      await connect(harness, stranger);
      const sessionId = await openSession(harness, stranger);

      // Somebody else's session id, used against our own device.
      const response = await harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${owned.recordId}/files/shares?sessionId=${sessionId}`,
        headers: controlHeaders(owned.session),
      });
      expect(response.statusCode).toBe(409);
      expect(errorCode(response)).toBe('SESSION_EXPIRED');
    } finally {
      await harness.close();
    }
  });
});
