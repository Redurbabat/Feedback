import { randomUUID } from 'node:crypto';

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  CLOCK_SKEW_MS,
  HEARTBEAT_INTERVAL_MS,
  HEARTBEAT_MISS_LIMIT,
  IMPLEMENTED_CAPABILITIES_V1,
  MAX_FRAME_BYTES,
  PROTOCOL_VERSION,
} from '../../constants.js';
import type { AppContext } from '../../context.js';
import type { ErrorCode } from '../../errors.js';
import { isTransferCancelReason } from '../../services/fileTransfers.js';
import { isScreenStopReason } from '../../services/screenStreams.js';
import {
  FILES_RESPONSE_TYPES,
  MAX_MESSAGE_IDS_PER_CONNECTION,
  capabilityStatePayloadSchema,
  decodeChunk,
  downloadCancelSchema,
  downloadChunkSchema,
  downloadCompleteSchema,
  envelopeSchema,
  heartbeatPayloadSchema,
  helloPayloadSchema,
  screenConsentSchema,
  screenFrameSchema,
  screenStartedSchema,
  screenStopSchema,
  systemInfoPayloadSchema,
} from '../../protocol/wire.js';
import { DEVICE_ID_PATTERN } from '../../crypto/deviceIdentity.js';
import { serverIdentityPayload } from '../../crypto/serverIdentity.js';
import { requireDevicePrincipal, requireDeviceToken } from '../deviceAuth.js';
import { parseOrThrow } from '../validation.js';

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
 * What a device may ask with, before it has proved anything.
 *
 * Only its own public identifier and a fresh nonce - nothing that would be worth intercepting,
 * because the point of the route is that the device does not yet know whom it is talking to.
 */
const serverIdentitySchema = z
  .object({
    deviceId: z.string().regex(DEVICE_ID_PATTERN, 'hat nicht das Format fb-<24 hex>'),
    nonce: z
      .string()
      .min(16)
      .max(64)
      .regex(/^[A-Za-z0-9_-]+$/, 'ist kein Base64Url'),
  })
  .strict();

/** Agent REST + WebSocket endpoints for presence and privileged responses. */
export async function registerAgentRoutes(
  app: FastifyInstance,
  context: AppContext,
): Promise<void> {
  /*
   * The server proving itself, before the device says anything it cannot take back.
   *
   * Everywhere else in this protocol the device authenticates and the server does not: the
   * deviceToken travels as a Bearer header in the very first request, including the WebSocket
   * upgrade. That was survivable while "which server" meant "which address" - but an address can
   * change hands, through a slipped setup link (THREAT_MODEL 4.20) or a domain that lapses and is
   * registered by someone else (4.21), and the token would be handed to whoever answers.
   *
   * So this route is unauthenticated on purpose: it is the one the device calls FIRST, and it
   * carries no secret in either direction. The device sends only the id it already publishes and
   * a fresh nonce; the answer is a signature over both. A device that cannot match the signature
   * against the key it saw when it paired stops there and never sends its token.
   */
  app.post(
    '/agent/server-identity',
    { config: { rateLimit: 'apiDefault' } },
    async (request) => {
      const body = parseOrThrow(serverIdentitySchema, request.body);
      const issuedAt = context.clock.now();
      const publicKeyBase64 = context.serverIdentity.publicKeyBase64;
      const payload = serverIdentityPayload({
        deviceId: body.deviceId,
        nonceBase64Url: body.nonce,
        publicKeyBase64,
        issuedAtEpochMillis: issuedAt,
      });

      // Deliberately the same answer for a known and an unknown deviceId. This route says who the
      // server is, not who it knows - telling the two apart would turn it into an oracle for
      // whether a given device is paired here.
      return {
        version: PROTOCOL_VERSION,
        serverPublicKey: publicKeyBase64,
        issuedAt,
        signature: context.serverIdentity.sign(payload),
      };
    },
  );

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
      capabilities: { serverGranted: granted },
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

      const processSystemInfoResponse = async (
        message: z.infer<typeof envelopeSchema>,
        now: number,
      ): Promise<void> => {
        if (message.sessionId === null) {
          sendError(
            socket,
            now,
            'SESSION_EXPIRED',
            'system.info.response benoetigt eine Remote Session',
            message.messageId,
          );
          return;
        }

        const remote = await context.repositories.remoteSessions.findById(message.sessionId);
        if (
          remote === undefined ||
          remote.deviceId !== principal.device.id ||
          remote.revokedAt !== null ||
          remote.expiresAt <= now ||
          !remote.approvedCapabilities.includes('system.info')
        ) {
          sendError(
            socket,
            now,
            'SESSION_EXPIRED',
            'Remote Session ist nicht mehr gueltig',
            message.messageId,
          );
          return;
        }

        const serverGranted = await serverGrantedCapabilities(context, principal.device.id);
        const local = context.agentConnections.snapshot(connection.id);
        if (
          !serverGranted.includes('system.info') ||
          local === undefined ||
          !local.deviceGrantedCapabilities.includes('system.info')
        ) {
          sendError(
            socket,
            now,
            'CAPABILITY_DENIED',
            'system.info ist nicht wirksam freigegeben',
            message.messageId,
          );
          return;
        }

        const payload = systemInfoPayloadSchema.safeParse(message.payload);
        if (!payload.success) {
          sendError(
            socket,
            now,
            'INVALID_MESSAGE',
            'Ungueltiger system.info.response Payload',
            message.messageId,
          );
          return;
        }

        if (message.relatesTo === undefined || message.relatesTo === null) {
          // Without the request id there is nothing to hand this answer to. Correlating by
          // session would be wrong: a session can have several requests open at once.
          sendError(
            socket,
            now,
            'INVALID_MESSAGE',
            'Antwort ohne relatesTo laesst sich keiner Anfrage zuordnen',
            message.messageId,
          );
          return;
        }

        await touchPresence(now);
        const accepted = context.agentConnections.resolveResponse(
          principal.device.id,
          message.relatesTo,
          payload.data,
        );
        if (!accepted) {
          sendError(
            socket,
            now,
            'SESSION_EXPIRED',
            'Antwort ist zu spaet oder gehoert zu keiner offenen Anfrage',
            message.messageId,
          );
        }
      };

      /**
       * Handles every `files.*` frame from the device.
       *
       * The capability is re-checked per frame, not per session. A transfer that is
       * already running must stop the moment either side withdraws files.read -
       * waiting for the five minute session to expire would keep delivering bytes
       * the owner already said no to.
       */
      const processFilesMessage = async (
        message: z.infer<typeof envelopeSchema>,
        now: number,
      ): Promise<void> => {
        if (message.sessionId === null) {
          sendError(
            socket,
            now,
            'SESSION_EXPIRED',
            'files-Nachrichten benoetigen eine Remote Session',
            message.messageId,
          );
          return;
        }

        const remote = await context.repositories.remoteSessions.findById(message.sessionId);
        if (
          remote === undefined ||
          remote.deviceId !== principal.device.id ||
          remote.revokedAt !== null ||
          remote.expiresAt <= now ||
          !remote.approvedCapabilities.includes('files.read')
        ) {
          context.fileTransfers.cancelForSession(
            message.sessionId,
            'session_expired',
            'remote session is no longer valid',
          );
          sendError(
            socket,
            now,
            'SESSION_EXPIRED',
            'Remote Session ist nicht mehr gueltig',
            message.messageId,
          );
          return;
        }

        const serverGranted = await serverGrantedCapabilities(context, principal.device.id);
        const local = context.agentConnections.snapshot(connection.id);
        if (
          !serverGranted.includes('files.read') ||
          local === undefined ||
          !local.deviceGrantedCapabilities.includes('files.read')
        ) {
          context.fileTransfers.cancelForSession(
            message.sessionId,
            'capability_revoked',
            'files.read is no longer effective',
          );
          sendError(
            socket,
            now,
            'CAPABILITY_DENIED',
            'files.read ist nicht wirksam freigegeben',
            message.messageId,
          );
          return;
        }

        await touchPresence(now);

        if (FILES_RESPONSE_TYPES.has(message.type)) {
          if (message.relatesTo === undefined || message.relatesTo === null) {
            sendError(
              socket,
              now,
              'INVALID_MESSAGE',
              'Antwort ohne relatesTo laesst sich keiner Anfrage zuordnen',
              message.messageId,
            );
            return;
          }
          const accepted = context.agentConnections.resolveResponse(
            principal.device.id,
            message.relatesTo,
            message.payload,
          );
          if (!accepted) {
            sendError(
              socket,
              now,
              'SESSION_EXPIRED',
              'Antwort ist zu spaet oder gehoert zu keiner offenen Anfrage',
              message.messageId,
            );
          }
          return;
        }

        if (message.type === 'files.download.chunk') {
          const parsed = downloadChunkSchema.safeParse(message.payload);
          if (!parsed.success) {
            sendError(
              socket,
              now,
              'INVALID_MESSAGE',
              'Ungueltiger files.download.chunk Payload',
              message.messageId,
            );
            return;
          }
          const data = decodeChunk(parsed.data.data);
          if (data === undefined) {
            sendError(
              socket,
              now,
              'INVALID_MESSAGE',
              'Chunk ist kein gueltiges base64',
              message.messageId,
            );
            return;
          }
          const accepted = context.fileTransfers.handleChunk({
            deviceId: principal.device.id,
            transferId: parsed.data.transferId,
            sequence: parsed.data.sequence,
            data,
            last: parsed.data.last,
            now,
          });
          if (!accepted) {
            sendError(
              socket,
              now,
              'INVALID_MESSAGE',
              'Chunk gehoert zu keinem offenen Transfer oder verletzt die Reihenfolge',
              message.messageId,
            );
          }
          return;
        }

        if (message.type === 'files.download.complete') {
          const parsed = downloadCompleteSchema.safeParse(message.payload);
          if (!parsed.success) {
            sendError(
              socket,
              now,
              'INVALID_MESSAGE',
              'Ungueltiger files.download.complete Payload',
              message.messageId,
            );
            return;
          }
          const accepted = context.fileTransfers.handleComplete({
            deviceId: principal.device.id,
            transferId: parsed.data.transferId,
            totalBytes: parsed.data.totalBytes,
            sha256: parsed.data.sha256,
          });
          if (!accepted) {
            sendError(
              socket,
              now,
              'INVALID_MESSAGE',
              'Abschluss passt nicht zum empfangenen Inhalt',
              message.messageId,
            );
          }
          return;
        }

        if (message.type === 'files.download.cancel') {
          const parsed = downloadCancelSchema.safeParse(message.payload);
          if (!parsed.success || !isTransferCancelReason(parsed.data.reason)) {
            sendError(
              socket,
              now,
              'INVALID_MESSAGE',
              'Ungueltiger files.download.cancel Payload',
              message.messageId,
            );
            return;
          }
          context.fileTransfers.cancelFromDevice(
            principal.device.id,
            parsed.data.transferId,
            parsed.data.reason,
          );
          return;
        }

        sendError(socket, now, 'UNSUPPORTED', 'Unbekannter files-Nachrichtentyp', message.messageId);
      };

      /**
       * Handles every `screen.*` frame from the device.
       *
       * The capability is re-checked per frame for the same reason as with files: a
       * stream that is already running has to stop the moment either side withdraws
       * screen.view. Waiting for the ten minute session to expire would keep showing
       * a picture the owner already said no to - and here the picture is the whole
       * display, not one shared folder.
       */
      const processScreenMessage = async (
        message: z.infer<typeof envelopeSchema>,
        now: number,
      ): Promise<void> => {
        if (message.sessionId === null) {
          sendError(
            socket,
            now,
            'SESSION_EXPIRED',
            'screen-Nachrichten benoetigen eine Remote Session',
            message.messageId,
          );
          return;
        }

        const remote = await context.repositories.remoteSessions.findById(message.sessionId);
        if (
          remote === undefined ||
          remote.deviceId !== principal.device.id ||
          remote.revokedAt !== null ||
          remote.expiresAt <= now ||
          !remote.approvedCapabilities.includes('screen.view')
        ) {
          context.screenStreams.stopForSession(
            message.sessionId,
            'session_expired',
            'remote session is no longer valid',
          );
          sendError(
            socket,
            now,
            'SESSION_EXPIRED',
            'Remote Session ist nicht mehr gueltig',
            message.messageId,
          );
          return;
        }

        const serverGranted = await serverGrantedCapabilities(context, principal.device.id);
        const local = context.agentConnections.snapshot(connection.id);
        if (
          !serverGranted.includes('screen.view') ||
          local === undefined ||
          !local.deviceGrantedCapabilities.includes('screen.view')
        ) {
          context.screenStreams.stopForSession(
            message.sessionId,
            'capability_revoked',
            'screen.view is no longer effective',
          );
          sendError(
            socket,
            now,
            'CAPABILITY_DENIED',
            'screen.view ist nicht wirksam freigegeben',
            message.messageId,
          );
          return;
        }

        // Deliberately not on every frame: at fifteen frames a second a presence write
        // per frame would be a database write per frame. The acks already prove the
        // stream is alive, and the idle sweep is what notices when it is not.
        if (message.type !== 'screen.frame') {
          await touchPresence(now);
        }

        if (message.type === 'screen.consent') {
          const parsed = screenConsentSchema.safeParse(message.payload);
          if (!parsed.success) {
            sendError(
              socket,
              now,
              'INVALID_MESSAGE',
              'Ungueltiger screen.consent Payload',
              message.messageId,
            );
            return;
          }
          const accepted = context.screenStreams.handleConsent(
            principal.device.id,
            parsed.data.streamId,
            parsed.data.state,
            now,
          );
          if (!accepted) {
            sendError(
              socket,
              now,
              'SESSION_EXPIRED',
              'Zustimmung gehoert zu keinem offenen Strom',
              message.messageId,
            );
          }
          return;
        }

        if (message.type === 'screen.started') {
          const parsed = screenStartedSchema.safeParse(message.payload);
          if (!parsed.success) {
            sendError(
              socket,
              now,
              'INVALID_MESSAGE',
              'Ungueltiger screen.started Payload',
              message.messageId,
            );
            return;
          }
          const { streamId, ...info } = parsed.data;
          if (decodeChunk(info.config) === undefined) {
            sendError(
              socket,
              now,
              'INVALID_MESSAGE',
              'Encoder-Konfiguration ist kein gueltiges base64',
              message.messageId,
            );
            return;
          }
          const accepted = context.screenStreams.handleStarted(
            principal.device.id,
            streamId,
            info,
            now,
          );
          if (!accepted) {
            sendError(
              socket,
              now,
              'SESSION_EXPIRED',
              'screen.started gehoert zu keinem offenen Strom',
              message.messageId,
            );
          }
          return;
        }

        if (message.type === 'screen.frame') {
          const parsed = screenFrameSchema.safeParse(message.payload);
          if (!parsed.success) {
            sendError(
              socket,
              now,
              'INVALID_MESSAGE',
              'Ungueltiger screen.frame Payload',
              message.messageId,
            );
            return;
          }
          const data = decodeChunk(parsed.data.data);
          if (data === undefined) {
            sendError(
              socket,
              now,
              'INVALID_MESSAGE',
              'Frame-Chunk ist kein gueltiges base64',
              message.messageId,
            );
            return;
          }
          const accepted = context.screenStreams.handleFrame({
            deviceId: principal.device.id,
            streamId: parsed.data.streamId,
            sequence: parsed.data.sequence,
            chunkIndex: parsed.data.chunkIndex,
            chunkCount: parsed.data.chunkCount,
            keyFrame: parsed.data.keyFrame,
            timestampUs: parsed.data.timestampUs,
            data,
            now,
          });
          if (!accepted) {
            sendError(
              socket,
              now,
              'INVALID_MESSAGE',
              'Frame gehoert zu keinem offenen Strom oder verletzt die Reihenfolge',
              message.messageId,
            );
          }
          return;
        }

        if (message.type === 'screen.stop') {
          const parsed = screenStopSchema.safeParse(message.payload);
          if (!parsed.success || !isScreenStopReason(parsed.data.reason)) {
            sendError(
              socket,
              now,
              'INVALID_MESSAGE',
              'Ungueltiger screen.stop Payload',
              message.messageId,
            );
            return;
          }
          context.screenStreams.stopFromDevice(
            principal.device.id,
            parsed.data.streamId,
            parsed.data.reason,
          );
          return;
        }

        sendError(
          socket,
          now,
          'UNSUPPORTED',
          'Unbekannter screen-Nachrichtentyp',
          message.messageId,
        );
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

        if (message.type === 'system.info.response') {
          await processSystemInfoResponse(message, now);
          return;
        }

        if (message.type.startsWith('files.')) {
          await processFilesMessage(message, now);
          return;
        }

        if (message.type.startsWith('screen.')) {
          await processScreenMessage(message, now);
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
        // Only once the device has no connection left: another socket of the same
        // device could still be carrying the transfer.
        if (!context.agentConnections.isConnected(principal.device.id)) {
          context.fileTransfers.cancelForDevice(
            principal.device.id,
            'read_error',
            'agent connection closed',
          );
          // The device is required to stop its own MediaProjection on connection loss
          // (protocol 8.5.9). Ending the viewer's stream here is the other half: the
          // browser must not keep a half-open picture that will never update again.
          context.screenStreams.stopForDevice(
            principal.device.id,
            'connection_lost',
            'agent connection closed',
          );
        }
      });

      socket.on('error', () => {
        request.log.warn(
          { deviceId: principal.device.deviceId },
          'agent websocket transport error',
        );
      });
    },
  );
}
