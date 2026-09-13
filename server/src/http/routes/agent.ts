import { randomUUID } from 'node:crypto';

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  CLOCK_SKEW_MS,
  DEVICE_NAME_MAX,
  HEARTBEAT_INTERVAL_MS,
  HEARTBEAT_MISS_LIMIT,
  IMPLEMENTED_CAPABILITIES_V1,
  MAX_FRAME_BYTES,
  PROTOCOL_VERSION,
} from '../../constants.js';
import type { AppContext } from '../../context.js';
import type { ErrorCode } from '../../errors.js';
import { requireDevicePrincipal, requireDeviceToken } from '../deviceAuth.js';

const MESSAGE_ID_V4 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MAX_MESSAGE_IDS_PER_CONNECTION = 8192;

const capabilitySchema = z.enum([
  'system.info',
  'files.read',
  'media.photos.read',
  'media.videos.read',
  'screen.view',
  'screen.control',
  'clipboard.read',
  'clipboard.write',
]);

function safeText(max: number) {
  return z
    .string()
    .min(1)
    .max(max)
    .refine((value) => !/\p{Cc}/u.test(value), {
      message: 'enthaelt unzulaessige Steuerzeichen',
    });
}

const envelopeSchema = z
  .object({
    version: z.number().int(),
    type: z.string().min(1).max(64),
    messageId: z.string().regex(MESSAGE_ID_V4, 'muss UUID v4 sein'),
    sessionId: z.string().uuid().nullable(),
    timestamp: z.string().min(20).max(40),
    payload: z.unknown(),
  })
  .strict();

const helloPayloadSchema = z
  .object({
    appVersion: safeText(32),
    osVersion: safeText(32),
    sdkInt: z.number().int().min(1).max(10_000),
    deviceName: safeText(DEVICE_NAME_MAX),
    grantedCapabilities: z.array(capabilitySchema).max(8),
  })
  .strict();

const heartbeatPayloadSchema = z.object({}).strict();
const capabilityStatePayloadSchema = z
  .object({ grantedCapabilities: z.array(capabilitySchema).max(8) })
  .strict();

interface WritableSocket {
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

function toIso(value: number): string {
  return new Date(value).toISOString();
}

function rawFrame(raw: unknown): Buffer | undefined {
  if (Buffer.isBuffer(raw)) {
    return raw;
  }
  if (typeof raw === 'string') {
    return Buffer.from(raw, 'utf8');
  }
  if (raw instanceof ArrayBuffer) {
    return Buffer.from(raw);
  }
  if (Array.isArray(raw)) {
    const parts = raw.filter((part: unknown): part is Buffer => Buffer.isBuffer(part));
    if (parts.length !== raw.length) {
      return undefined;
    }
    return Buffer.concat(parts);
  }
  return undefined;
}

function sendFrame(socket: WritableSocket, input: {
  type: string;
  payload: Record<string, unknown>;
  now: number;
  sessionId?: string | null;
}): void {
  socket.send(
    JSON.stringify({
      version: PROTOCOL_VERSION,
      type: input.type,
      messageId: randomUUID(),
      sessionId: input.sessionId ?? null,
      timestamp: toIso(input.now),
      payload: input.payload,
    }),
  );
}

function sendError(
  socket: WritableSocket,
  now: number,
  code: ErrorCode,
  message: string,
  relatesTo?: string,
): void {
  sendFrame(socket, {
    type: 'error',
    now,
    payload: {
      code,
      message,
      ...(relatesTo === undefined ? {} : { relatesTo }),
    },
  });
}

async function serverGrantedCapabilities(
  context: AppContext,
  deviceId: string,
): Promise<readonly string[]> {
  const entries = await context.repositories.deviceCapabilities.listForDevice(deviceId);
  return entries
    .filter((entry) => entry.granted)
    .map((entry) => entry.capability)
    .filter((capability) =>
      (IMPLEMENTED_CAPABILITIES_V1 as readonly string[]).includes(capability),
    );
}

/**
 * Agent REST + WebSocket endpoints.
 *
 * The WebSocket is authenticated with the same bearer device token as
 * `/agent/me`. Only protocol-v1 presence/capability messages are accepted at
 * this stage; privileged requests are added separately with Remote Sessions.
 */
export async function registerAgentRoutes(
  app: FastifyInstance,
  context: AppContext,
): Promise<void> {
  app.get('/agent/me', async (request) => {
    const principal = await requireDeviceToken(context, request);
    const granted = await serverGrantedCapabilities(context, principal.device.id);

    return {
      version: PROTOCOL_VERSION,
      device: {
        id: principal.device.id,
        deviceId: principal.device.deviceId,
        fingerprint: principal.device.fingerprint,
        name: principal.device.name,
        platform: principal.device.platform,
        osVersion: principal.device.osVersion,
        sdkInt: principal.device.sdkInt,
        appVersion: principal.device.appVersion,
        pairedAt: toIso(principal.device.createdAt),
        lastSeenAt: toIso(principal.authenticatedAt),
      },
      capabilities: {
        serverGranted: granted,
      },
      serverTime: toIso(principal.authenticatedAt),
    };
  });

  app.get(
    '/agent/ws',
    {
      websocket: true,
      preValidation: async (request) => {
        await requireDeviceToken(context, request);
      },
    },
    (socket, request) => {
      // Attach listeners synchronously before doing any asynchronous work so a
      // fast client cannot send a frame between upgrade and listener setup.
      const principal = requireDevicePrincipal(request);
      const initialNow = context.clock.now();
      const connection = context.agentConnections.register(
        principal.device.id,
        socket,
        initialNow,
      );
      const seenMessageIds = new Set<string>();
      let helloReceived = false;
      let lastActivityAt = initialNow;
      let closed = false;
      let processing = Promise.resolve();

      const heartbeatTimer = setInterval(() => {
        if (closed) {
          return;
        }
        const now = context.clock.now();
        if (now - lastActivityAt > HEARTBEAT_INTERVAL_MS * HEARTBEAT_MISS_LIMIT) {
          socket.close(4000, 'heartbeat timeout');
        }
      }, HEARTBEAT_INTERVAL_MS);
      heartbeatTimer.unref();

      const touchPresence = async (now: number): Promise<void> => {
        lastActivityAt = now;
        context.agentConnections.touch(connection.id, now);
        await context.repositories.devices.touchLastSeen(principal.device.id, now);
        await context.repositories.deviceTokens.touch(principal.token.id, now);
      };

      const processFrame = async (raw: unknown): Promise<void> => {
        if (closed) {
          return;
        }
        const bytes = rawFrame(raw);
        if (bytes === undefined) {
          socket.close(1008, 'invalid frame');
          return;
        }
        if (bytes.byteLength > MAX_FRAME_BYTES) {
          socket.close(1009, 'frame too large');
          return;
        }

        let decoded: unknown;
        try {
          decoded = JSON.parse(bytes.toString('utf8')) as unknown;
        } catch {
          socket.close(1008, 'invalid json');
          return;
        }

        const parsed = envelopeSchema.safeParse(decoded);
        const now = context.clock.now();
        if (!parsed.success) {
          sendError(socket, now, 'INVALID_MESSAGE', 'Ungueltiges Nachrichtenformat');
          return;
        }
        const message = parsed.data;

        if (message.version !== PROTOCOL_VERSION) {
          sendError(
            socket,
            now,
            'UNSUPPORTED',
            'Protokollversion wird nicht unterstuetzt',
            message.messageId,
          );
          return;
        }

        if (seenMessageIds.has(message.messageId)) {
          sendError(
            socket,
            now,
            'INVALID_MESSAGE',
            'messageId wurde bereits verwendet',
            message.messageId,
          );
          return;
        }
        if (seenMessageIds.size >= MAX_MESSAGE_IDS_PER_CONNECTION) {
          socket.close(1008, 'connection message limit');
          return;
        }
        seenMessageIds.add(message.messageId);

        const timestamp = Date.parse(message.timestamp);
        if (
          !message.timestamp.endsWith('Z') ||
          !Number.isFinite(timestamp) ||
          Math.abs(timestamp - now) > CLOCK_SKEW_MS
        ) {
          sendError(
            socket,
            now,
            'INVALID_MESSAGE',
            'Zeitstempel liegt ausserhalb des erlaubten Fensters',
            message.messageId,
          );
          return;
        }

        // Presence messages are deliberately non-privileged and therefore must
        // not pretend to belong to a Remote Session.
        if (message.sessionId !== null) {
          sendError(
            socket,
            now,
            'INVALID_MESSAGE',
            'Presence-Nachrichten duerfen keine sessionId tragen',
            message.messageId,
          );
          return;
        }

        if (!helloReceived && message.type !== 'agent.hello') {
          sendError(
            socket,
            now,
            'INVALID_MESSAGE',
            'agent.hello muss die erste Nachricht sein',
            message.messageId,
          );
          return;
        }

        switch (message.type) {
          case 'agent.hello': {
            if (helloReceived) {
              sendError(
                socket,
                now,
                'INVALID_MESSAGE',
                'agent.hello wurde bereits empfangen',
                message.messageId,
              );
              return;
            }
            const payload = helloPayloadSchema.safeParse(message.payload);
            if (!payload.success) {
              sendError(
                socket,
                now,
                'INVALID_MESSAGE',
                'Ungueltiger agent.hello Payload',
                message.messageId,
              );
              return;
            }
            helloReceived = true;
            context.agentConnections.setDeviceGrantedCapabilities(
              connection.id,
              payload.data.grantedCapabilities,
            );
            await touchPresence(now);
            const serverGranted = await serverGrantedCapabilities(context, principal.device.id);
            sendFrame(socket, {
              type: 'agent.hello.ack',
              now,
              payload: {
                serverTime: toIso(now),
                heartbeatIntervalMs: HEARTBEAT_INTERVAL_MS,
                serverGrantedCapabilities: serverGranted,
              },
            });
            return;
          }

          case 'agent.heartbeat': {
            const payload = heartbeatPayloadSchema.safeParse(message.payload);
            if (!payload.success) {
              sendError(
                socket,
                now,
                'INVALID_MESSAGE',
                'Ungueltiger agent.heartbeat Payload',
                message.messageId,
              );
              return;
            }
            await touchPresence(now);
            sendFrame(socket, {
              type: 'agent.heartbeat.ack',
              now,
              payload: { serverTime: toIso(now) },
            });
            return;
          }

          case 'capability.state': {
            const payload = capabilityStatePayloadSchema.safeParse(message.payload);
            if (!payload.success) {
              sendError(
                socket,
                now,
                'INVALID_MESSAGE',
                'Ungueltiger capability.state Payload',
                message.messageId,
              );
              return;
            }
            context.agentConnections.setDeviceGrantedCapabilities(
              connection.id,
              payload.data.grantedCapabilities,
            );
            await touchPresence(now);
            return;
          }

          default:
            sendError(
              socket,
              now,
              'UNSUPPORTED',
              'Nachrichtentyp wird nicht unterstuetzt',
              message.messageId,
            );
        }
      };

      socket.on('message', (raw: unknown) => {
        processing = processing
          .then(() => processFrame(raw))
          .catch(() => {
            if (!closed) {
              const now = context.clock.now();
              sendError(socket, now, 'INTERNAL', 'Interner Fehler bei der Nachricht');
              socket.close(1011, 'internal error');
            }
          });
      });

      socket.on('close', () => {
        closed = true;
        clearInterval(heartbeatTimer);
        context.agentConnections.unregister(connection.id);
      });

      socket.on('error', () => {
        // Do not log the socket error object: transport libraries can include
        // request details. The close handler performs deterministic cleanup.
        request.log.warn(
          { deviceId: principal.device.deviceId },
          'agent websocket transport error',
        );
      });
    },
  );
}
