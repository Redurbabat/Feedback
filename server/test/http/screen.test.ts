import { randomBytes } from 'node:crypto';

import { describe, expect, it } from 'vitest';

import {
  SCREEN_MAX_BITRATE_KBPS,
  SCREEN_MAX_DIMENSION,
  SCREEN_MAX_FPS,
  SCREEN_SESSION_TTL_MS,
} from '../../src/constants.js';
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
  const email = `screen-${randomBytes(8).toString('hex')}@example.test`;
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

async function connect(
  harness: Harness,
  owned: Provisioned,
  onFrame: (frame: Record<string, unknown>) => void = () => undefined,
  capabilities: readonly string[] = ['screen.view'],
): Promise<RespondingSocket> {
  for (const capability of capabilities) {
    await harness.context.repositories.deviceCapabilities.setGranted({
      deviceId: owned.recordId,
      capability,
      granted: true,
      grantedBy: owned.session.userId,
    });
  }
  const socket = new RespondingSocket(onFrame);
  const connection = harness.context.agentConnections.register(
    owned.recordId,
    socket,
    harness.clock.now(),
  );
  harness.context.agentConnections.setDeviceGrantedCapabilities(connection.id, [...capabilities]);
  return socket;
}

async function openSession(harness: Harness, owned: Provisioned): Promise<string> {
  const response = await harness.app.inject({
    method: 'POST',
    url: `/api/v1/devices/${owned.recordId}/screen/session`,
    headers: controlHeaders(owned.session),
  });
  expect(response.statusCode).toBe(200);
  return (response.json() as { sessionId: string }).sessionId;
}

interface SseEvent {
  readonly event: string;
  readonly data: Record<string, unknown>;
}

/** Parses a complete `text/event-stream` body into its events. */
function parseSse(body: string): SseEvent[] {
  return body
    .split('\n\n')
    .filter((block) => block.trim().length > 0)
    .map((block) => {
      const lines = block.split('\n');
      const event = lines.find((line) => line.startsWith('event: '))?.slice(7) ?? '';
      const data = lines.find((line) => line.startsWith('data: '))?.slice(6) ?? '{}';
      return { event, data: JSON.parse(data) as Record<string, unknown> };
    });
}

function errorCode(response: { json: () => unknown }): string {
  return (response.json() as { error: { code: string } }).error.code;
}

const CONFIG_BASE64 = Buffer.from([0, 0, 0, 1, 0x67, 0x42]).toString('base64');

describe('screen.view sessions', () => {
  it('refuses a session when nothing is granted', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      const denied = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/devices/${owned.recordId}/screen/session`,
        headers: controlHeaders(owned.session),
      });
      expect(denied.statusCode).toBe(403);
      expect(errorCode(denied)).toBe('CAPABILITY_DENIED');
    } finally {
      await harness.close();
    }
  });

  it('refuses a session the device has not granted, even when the server has', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      await harness.context.repositories.deviceCapabilities.setGranted({
        deviceId: owned.recordId,
        capability: 'screen.view',
        granted: true,
        grantedBy: owned.session.userId,
      });
      const socket = new RespondingSocket();
      harness.context.agentConnections.register(owned.recordId, socket, harness.clock.now());

      const denied = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/devices/${owned.recordId}/screen/session`,
        headers: controlHeaders(owned.session),
      });
      expect(denied.statusCode).toBe(403);
      expect(errorCode(denied)).toBe('CAPABILITY_DENIED');
    } finally {
      await harness.close();
    }
  });

  it('opens a session without asking the device anything yet', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      const socket = await connect(harness, owned);

      const response = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/devices/${owned.recordId}/screen/session`,
        headers: controlHeaders(owned.session),
      });
      expect(response.statusCode).toBe(200);
      const body = response.json() as Record<string, unknown>;
      expect(body.maxFps).toBe(SCREEN_MAX_FPS);
      expect(body.maxWidth).toBe(SCREEN_MAX_DIMENSION);
      expect(body.maxBitrateKbps).toBe(SCREEN_MAX_BITRATE_KBPS);
      expect(Date.parse(String(body.expiresAt)) - harness.clock.now()).toBe(SCREEN_SESSION_TTL_MS);

      // The point of splitting session and stream: a session nobody watches never
      // puts a consent dialog on the phone.
      expect(socket.sent).toHaveLength(0);
      expect(harness.context.screenStreams.size).toBe(0);
    } finally {
      await harness.close();
    }
  });

  it('refuses a stream for a session that was closed', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      await connect(harness, owned);
      const sessionId = await openSession(harness, owned);

      const closed = await harness.app.inject({
        method: 'DELETE',
        url: `/api/v1/devices/${owned.recordId}/screen/session`,
        headers: controlHeaders(owned.session),
        payload: { sessionId },
      });
      expect(closed.statusCode).toBe(200);

      const response = await harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${owned.recordId}/screen/stream?sessionId=${sessionId}`,
        headers: controlHeaders(owned.session),
      });
      expect(response.statusCode).toBe(409);
      expect(errorCode(response)).toBe('SESSION_EXPIRED');
    } finally {
      await harness.close();
    }
  });
});

describe('screen.view stream', () => {
  it('carries consent, configuration and frames to the viewer', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      const picture = Buffer.from('an encoded keyframe');

      await connect(harness, owned, (frame) => {
        if (frame.type !== 'screen.start') {
          return;
        }
        const payload = frame.payload as { streamId: string };
        queueMicrotask(() => {
          const hub = harness.context.screenStreams;
          const now = harness.clock.now();
          hub.handleConsent(owned.recordId, payload.streamId, 'pending', now);
          hub.handleConsent(owned.recordId, payload.streamId, 'granted', now);
          hub.handleStarted(
            owned.recordId,
            payload.streamId,
            { width: 720, height: 1280, codec: 'avc1.42E01E', fps: 15, config: CONFIG_BASE64 },
            now,
          );
          hub.handleFrame({
            deviceId: owned.recordId,
            streamId: payload.streamId,
            sequence: 0,
            chunkIndex: 0,
            chunkCount: 1,
            keyFrame: true,
            timestampUs: 0,
            data: picture,
            now,
          });
          hub.stopFromDevice(owned.recordId, payload.streamId, 'owner_stopped');
        });
      });
      const sessionId = await openSession(harness, owned);

      const response = await harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${owned.recordId}/screen/stream?sessionId=${sessionId}`,
        headers: controlHeaders(owned.session),
      });

      expect(response.statusCode).toBe(200);
      expect(response.headers['content-type']).toContain('text/event-stream');
      // A proxy that buffers this stream turns a live picture into a slideshow.
      expect(response.headers['cache-control']).toContain('no-transform');
      expect(response.headers['x-accel-buffering']).toBe('no');

      const events = parseSse(response.payload);
      expect(events.map((entry) => entry.event)).toEqual([
        'open',
        'status',
        'status',
        'config',
        'frame',
        'end',
      ]);
      expect(events[1]?.data.state).toBe('pending');
      expect(events[2]?.data.state).toBe('granted');
      expect(events[3]?.data.codec).toBe('avc1.42E01E');
      expect(events[4]?.data.keyFrame).toBe(true);
      expect(Buffer.from(String(events[4]?.data.data), 'base64')).toEqual(picture);
      expect(events[5]?.data.reason).toBe('owner_stopped');

      // Nothing is kept once the stream is over.
      expect(harness.context.screenStreams.size).toBe(0);
    } finally {
      await harness.close();
    }
  });

  it('tells the viewer when the owner refuses', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      await connect(harness, owned, (frame) => {
        if (frame.type !== 'screen.start') {
          return;
        }
        const payload = frame.payload as { streamId: string };
        queueMicrotask(() => {
          harness.context.screenStreams.handleConsent(
            owned.recordId,
            payload.streamId,
            'declined',
            harness.clock.now(),
          );
        });
      });
      const sessionId = await openSession(harness, owned);

      const response = await harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${owned.recordId}/screen/stream?sessionId=${sessionId}`,
        headers: controlHeaders(owned.session),
      });

      const events = parseSse(response.payload);
      const end = events.find((entry) => entry.event === 'end');
      // A refusal has to be distinguishable from a timeout, or the control center
      // cannot tell the owner what actually happened.
      expect(end?.data.reason).toBe('consent_declined');
      expect(events.some((entry) => entry.event === 'frame')).toBe(false);
    } finally {
      await harness.close();
    }
  });

  it('asks the device to start with the protocol limits, not with what the client wants', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      const socket = await connect(harness, owned, (frame) => {
        if (frame.type !== 'screen.start') {
          return;
        }
        const payload = frame.payload as { streamId: string };
        queueMicrotask(() => {
          harness.context.screenStreams.stopFromDevice(
            owned.recordId,
            payload.streamId,
            'encoder_error',
          );
        });
      });
      const sessionId = await openSession(harness, owned);
      await harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${owned.recordId}/screen/stream?sessionId=${sessionId}`,
        headers: controlHeaders(owned.session),
      });

      const start = socket.sent.find((frame) => frame.type === 'screen.start');
      expect(start).toBeDefined();
      expect(start?.payload).toMatchObject({
        maxWidth: SCREEN_MAX_DIMENSION,
        maxHeight: SCREEN_MAX_DIMENSION,
        maxFps: SCREEN_MAX_FPS,
        maxBitrateKbps: SCREEN_MAX_BITRATE_KBPS,
      });
      expect(start?.sessionId).toBe(sessionId);
    } finally {
      await harness.close();
    }
  });

  it('stops the stream when the device is revoked', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      await connect(harness, owned, (frame) => {
        if (frame.type !== 'screen.start') {
          return;
        }
        const payload = frame.payload as { streamId: string };
        queueMicrotask(() => {
          const now = harness.clock.now();
          harness.context.screenStreams.handleConsent(owned.recordId, payload.streamId, 'granted', now);
          harness.context.screenStreams.handleStarted(
            owned.recordId,
            payload.streamId,
            { width: 720, height: 1280, codec: 'avc1.42E01E', fps: 15, config: CONFIG_BASE64 },
            now,
          );
        });
      });
      const sessionId = await openSession(harness, owned);

      const streaming = harness.app.inject({
        method: 'GET',
        url: `/api/v1/devices/${owned.recordId}/screen/stream?sessionId=${sessionId}`,
        headers: controlHeaders(owned.session),
      });
      await new Promise((resolve) => setTimeout(resolve, 20));
      expect(harness.context.screenStreams.size).toBe(1);

      const revoked = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/devices/${owned.recordId}/revoke`,
        headers: controlHeaders(owned.session),
      });
      expect(revoked.statusCode).toBe(200);

      const response = await streaming;
      const end = parseSse(response.payload).find((entry) => entry.event === 'end');
      expect(end?.data.reason).toBe('device_revoked');
      expect(harness.context.screenStreams.size).toBe(0);
    } finally {
      await harness.close();
    }
  });

  it('refuses a keyframe request when no stream is running', async () => {
    const harness = await createHarness();
    try {
      const owned = await provision(harness);
      await connect(harness, owned);
      const sessionId = await openSession(harness, owned);

      const response = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/devices/${owned.recordId}/screen/keyframe`,
        headers: controlHeaders(owned.session),
        payload: { sessionId },
      });
      expect(response.statusCode).toBe(409);
      expect(errorCode(response)).toBe('SESSION_EXPIRED');
    } finally {
      await harness.close();
    }
  });

  it('does not let one owner reach another owner device', async () => {
    const harness = await createHarness();
    try {
      const mine = await provision(harness);
      const theirs = await provision(harness);
      await connect(harness, theirs);

      const response = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/devices/${theirs.recordId}/screen/session`,
        headers: controlHeaders(mine.session),
      });
      // NOT_FOUND rather than FORBIDDEN: the difference would confirm the id exists.
      expect(response.statusCode).toBe(404);
      expect(errorCode(response)).toBe('NOT_FOUND');
    } finally {
      await harness.close();
    }
  });
});
