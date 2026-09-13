import { randomUUID } from 'node:crypto';
import { PassThrough } from 'node:stream';

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  AGENT_REQUEST_TIMEOUT_MS,
  FILES_SESSION_TTL_MS,
  FILE_MAX_LIST_ENTRIES,
  FILE_NAME_MAX,
} from '../../constants.js';
import type { AppContext } from '../../context.js';
import type { DeviceRecord, RemoteSessionRecord } from '../../db/repositories/types.js';
import { ProtocolError } from '../../errors.js';
import {
  AgentCapabilityUnavailableError,
  AgentOfflineError,
  AgentRequestTimeoutError,
} from '../../services/agentConnections.js';
import { TooManyTransfersError, type TransferChannel } from '../../services/fileTransfers.js';
import {
  agentFrame,
  grantedCapabilities,
  requireBrowserSession,
  requireOwnedDevice,
} from '../deviceAccess.js';
import { consumeRateLimit, requireCsrf } from '../guards.js';
import { assertBrowserContext } from '../security.js';
import { parseOrThrow } from '../validation.js';

const CAPABILITY = 'files.read';

const paramsSchema = z.object({ id: z.string().uuid() }).strict();
const opaqueIdSchema = z
  .string()
  .min(1)
  .max(128)
  .regex(/^[A-Za-z0-9_-]+$/u, 'muss eine opake base64url-Kennung sein');

function hasControlCharacter(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code < 32 || code === 127) {
      return true;
    }
  }
  return false;
}

/**
 * A display name must not carry a path component. Protocol section 8.3.3 says the
 * receiver rejects such an entry rather than repairing it: a name that needs
 * repairing is the usual route to a traversal on this side.
 */
const entryNameSchema = z
  .string()
  .min(1)
  .max(FILE_NAME_MAX)
  .refine(
    (value) =>
      value !== '.' &&
      value !== '..' &&
      !value.includes('/') &&
      !value.includes('\\') &&
      !hasControlCharacter(value),
    'darf keinen Pfadanteil und keine Steuerzeichen enthalten',
  );

const shareSchema = z
  .object({
    shareId: opaqueIdSchema,
    displayName: entryNameSchema,
    kind: z.enum(['tree', 'file']),
    addedAt: z.string().min(20).max(40),
  })
  .strict();

const fileEntrySchema = z
  .object({
    id: opaqueIdSchema,
    name: entryNameSchema,
    mimeType: z.string().max(255).nullish(),
    size: z.number().int().min(0).nullable(),
    modifiedAt: z.string().min(20).max(40).nullish(),
    kind: z.enum(['file', 'directory']),
  })
  .strict();

const sharesResponseSchema = z.object({ shares: z.array(shareSchema).max(64) }).strict();
const listResponseSchema = z
  .object({
    shareId: opaqueIdSchema,
    entries: z.array(fileEntrySchema).max(FILE_MAX_LIST_ENTRIES),
    nextCursor: z.string().min(1).max(256).nullish(),
  })
  .strict();
const metadataResponseSchema = z.object({ entry: fileEntrySchema }).strict();

const sessionBodySchema = z.object({ sessionId: z.string().uuid() }).strict();
const sharesQuerySchema = z.object({ sessionId: z.string().uuid() }).strict();
const entriesQuerySchema = z
  .object({
    sessionId: z.string().uuid(),
    shareId: opaqueIdSchema,
    directoryId: opaqueIdSchema.optional(),
    cursor: z.string().min(1).max(256).optional(),
    limit: z.coerce.number().int().min(1).max(FILE_MAX_LIST_ENTRIES).optional(),
  })
  .strict();
const contentQuerySchema = z
  .object({
    sessionId: z.string().uuid(),
    shareId: opaqueIdSchema,
    fileId: opaqueIdSchema,
  })
  .strict();

interface FilesAccess {
  readonly device: DeviceRecord;
  readonly session: RemoteSessionRecord;
  readonly userId: string;
}

/** Maps an agent failure onto the protocol error the caller should see. */
function agentError(error: unknown): never {
  if (error instanceof AgentCapabilityUnavailableError) {
    throw new ProtocolError('CAPABILITY_DENIED', 'files.read ist auf dem Geraet nicht freigegeben');
  }
  if (error instanceof AgentOfflineError) {
    throw new ProtocolError('SESSION_EXPIRED', 'Geraet ist nicht mehr verbunden');
  }
  if (error instanceof AgentRequestTimeoutError) {
    throw new ProtocolError('SESSION_EXPIRED', 'Geraet hat nicht rechtzeitig geantwortet');
  }
  throw error;
}

/**
 * Ends a files session and everything running under it: transfers stop, waiting
 * requests fail, and the session row is revoked.
 */
export async function closeFilesSession(
  context: AppContext,
  sessionId: string,
  reason: 'session_expired' | 'capability_revoked' | 'device_revoked',
  message: string,
): Promise<void> {
  context.fileTransfers.cancelForSession(sessionId, reason, message);
  context.agentConnections.rejectPendingForSession(sessionId, new AgentOfflineError());
  await context.repositories.remoteSessions.revoke(sessionId, context.clock.now());
}

export async function registerFileRoutes(
  app: FastifyInstance,
  context: AppContext,
): Promise<void> {
  /**
   * Re-checks everything on every single request rather than trusting the session.
   *
   * A files session lives five minutes and carries many requests. Withdrawing the
   * capability on either side, or revoking the device, has to stop the next request
   * immediately - not whenever the session happens to expire.
   */
  async function requireFilesAccess(
    device: DeviceRecord,
    userId: string,
    sessionId: string,
  ): Promise<FilesAccess> {
    if (device.revokedAt !== null) {
      throw new ProtocolError('DEVICE_REVOKED', 'Geraet wurde widerrufen');
    }

    const now = context.clock.now();
    const session = await context.repositories.remoteSessions.findById(sessionId);
    if (
      session === undefined ||
      session.deviceId !== device.id ||
      session.ownerId !== userId ||
      session.revokedAt !== null ||
      session.expiresAt <= now ||
      !session.approvedCapabilities.includes(CAPABILITY)
    ) {
      throw new ProtocolError('SESSION_EXPIRED', 'Dateisitzung ist nicht mehr gueltig');
    }

    await requireLiveCapability(device);
    return { device, session, userId };
  }

  /** Both sides must grant files.read, and the device must actually be connected. */
  async function requireLiveCapability(device: DeviceRecord): Promise<void> {
    const serverGranted = grantedCapabilities(
      await context.repositories.deviceCapabilities.listForDevice(device.id),
    );
    if (!serverGranted.includes(CAPABILITY)) {
      throw new ProtocolError('CAPABILITY_DENIED', 'files.read ist serverseitig nicht freigegeben');
    }

    const connections = context.agentConnections.snapshotsForDevice(device.id);
    if (connections.length === 0) {
      throw new ProtocolError('SESSION_EXPIRED', 'Geraet ist nicht verbunden');
    }
    if (!connections.some((entry) => entry.deviceGrantedCapabilities.includes(CAPABILITY))) {
      throw new ProtocolError(
        'CAPABILITY_DENIED',
        'files.read ist auf dem Geraet nicht freigegeben',
      );
    }
  }

  /** One request/response round trip inside a files session. */
  async function ask(
    access: FilesAccess,
    type: string,
    payload: Record<string, unknown>,
  ): Promise<unknown> {
    const messageId = randomUUID();
    try {
      return await context.agentConnections.requestDevice({
        deviceId: access.device.id,
        sessionId: access.session.id,
        messageId,
        requiredCapability: CAPABILITY,
        frame: agentFrame(type, payload, context.clock.now(), access.session.id, messageId),
        timeoutMs: AGENT_REQUEST_TIMEOUT_MS,
      });
    } catch (error) {
      agentError(error);
    }
  }

  // --------------------------------------------------------------- sessions ---

  app.post('/devices/:id/files/session', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    const principal = await requireBrowserSession(context, request, reply);
    requireCsrf(context, request);
    const params = parseOrThrow(paramsSchema, request.params);
    consumeRateLimit(context, 'filesSession', 'user', principal.user.id);

    const device = await requireOwnedDevice(context, principal.user.id, params.id);
    if (device.revokedAt !== null) {
      throw new ProtocolError('DEVICE_REVOKED', 'Geraet wurde widerrufen');
    }
    await requireLiveCapability(device);

    const now = context.clock.now();
    const session = await context.repositories.remoteSessions.create({
      ownerId: principal.user.id,
      deviceId: device.id,
      // Longer than a one-shot session: browsing is a conversation, and a listing
      // that expires halfway through is worse than useless.
      expiresAt: now + FILES_SESSION_TTL_MS,
      requestedCapabilities: [CAPABILITY],
      approvedCapabilities: [CAPABILITY],
    });

    await context.audit.record({
      eventType: 'files.session.open',
      result: 'success',
      userId: principal.user.id,
      deviceId: device.deviceId,
      sessionId: session.id,
    });

    return {
      sessionId: session.id,
      expiresAt: new Date(session.expiresAt).toISOString(),
      maxDownloadBytes: context.config.fileMaxDownloadBytes,
    };
  });

  app.delete('/devices/:id/files/session', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    const principal = await requireBrowserSession(context, request, reply);
    requireCsrf(context, request);
    const params = parseOrThrow(paramsSchema, request.params);
    const body = parseOrThrow(sessionBodySchema, request.body);

    const device = await requireOwnedDevice(context, principal.user.id, params.id);
    const session = await context.repositories.remoteSessions.findById(body.sessionId);
    if (
      session === undefined ||
      session.deviceId !== device.id ||
      session.ownerId !== principal.user.id
    ) {
      throw new ProtocolError('NOT_FOUND', 'Dateisitzung nicht gefunden');
    }

    await closeFilesSession(context, session.id, 'session_expired', 'Sitzung beendet');
    return { sessionId: session.id, closed: true };
  });

  // ----------------------------------------------------------------- browse ---

  app.get('/devices/:id/files/shares', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    const principal = await requireBrowserSession(context, request, reply);
    const params = parseOrThrow(paramsSchema, request.params);
    const query = parseOrThrow(sharesQuerySchema, request.query);

    const device = await requireOwnedDevice(context, principal.user.id, params.id);
    const access = await requireFilesAccess(device, principal.user.id, query.sessionId);

    const payload = await ask(access, 'files.shares.request', {});
    const parsed = sharesResponseSchema.safeParse(payload);
    if (!parsed.success) {
      throw new ProtocolError(
        'INVALID_MESSAGE',
        'Das Geraet hat eine ungueltige Freigabeliste geliefert',
      );
    }
    return parsed.data;
  });

  app.get('/devices/:id/files/entries', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    const principal = await requireBrowserSession(context, request, reply);
    const params = parseOrThrow(paramsSchema, request.params);
    const query = parseOrThrow(entriesQuerySchema, request.query);

    const device = await requireOwnedDevice(context, principal.user.id, params.id);
    const access = await requireFilesAccess(device, principal.user.id, query.sessionId);

    const payload = await ask(access, 'files.list.request', {
      shareId: query.shareId,
      ...(query.directoryId === undefined ? {} : { directoryId: query.directoryId }),
      ...(query.cursor === undefined ? {} : { cursor: query.cursor }),
      ...(query.limit === undefined ? {} : { limit: query.limit }),
    });
    const parsed = listResponseSchema.safeParse(payload);
    if (!parsed.success) {
      throw new ProtocolError(
        'INVALID_MESSAGE',
        'Das Geraet hat eine ungueltige Auflistung geliefert',
      );
    }
    if (parsed.data.shareId !== query.shareId) {
      // An answer about a different share than the one asked about is not an answer.
      throw new ProtocolError('INVALID_MESSAGE', 'Die Auflistung gehoert zu einem anderen Bereich');
    }
    return parsed.data;
  });

  // --------------------------------------------------------------- download ---

  app.get('/devices/:id/files/content', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    const principal = await requireBrowserSession(context, request, reply);
    const params = parseOrThrow(paramsSchema, request.params);
    const query = parseOrThrow(contentQuerySchema, request.query);
    consumeRateLimit(context, 'filesContent', 'user', principal.user.id);

    const device = await requireOwnedDevice(context, principal.user.id, params.id);
    const access = await requireFilesAccess(device, principal.user.id, query.sessionId);

    // Ask for the metadata first: it decides whether the transfer may start at all,
    // and it is what lets the response carry an honest length and file name.
    const metadataPayload = await ask(access, 'files.metadata.request', {
      shareId: query.shareId,
      fileId: query.fileId,
    });
    const metadata = metadataResponseSchema.safeParse(metadataPayload);
    if (!metadata.success) {
      throw new ProtocolError('INVALID_MESSAGE', 'Das Geraet hat ungueltige Metadaten geliefert');
    }
    const entry = metadata.data.entry;
    if (entry.kind !== 'file') {
      throw new ProtocolError('UNSUPPORTED', 'Nur Dateien koennen heruntergeladen werden');
    }
    if (entry.size !== null && entry.size > context.config.fileMaxDownloadBytes) {
      throw new ProtocolError('UNSUPPORTED', 'Die Datei ueberschreitet das erlaubte Download-Limit');
    }

    const transferId = randomUUID();
    const stream = new PassThrough();
    let settled = false;

    const channel: TransferChannel = {
      sendToDevice(type, payload) {
        context.agentConnections.sendToDevice(
          device.id,
          agentFrame(type, payload, context.clock.now(), access.session.id),
        );
      },
      write(chunk) {
        stream.write(chunk);
      },
      finish() {
        settled = true;
        stream.end();
        void context.audit.record({
          eventType: 'file.transfer.completed',
          result: 'success',
          userId: principal.user.id,
          deviceId: device.deviceId,
          sessionId: access.session.id,
        });
      },
      fail(reason, message) {
        settled = true;
        // Destroying the stream is the point: a partial body must not arrive looking
        // like a complete, shorter file.
        stream.destroy(new Error(`transfer ${reason}: ${message}`));
        void context.audit.record({
          eventType: 'file.transfer.cancelled',
          result: 'failure',
          userId: principal.user.id,
          deviceId: device.deviceId,
          sessionId: access.session.id,
          detail: { reason },
        });
      },
    };

    try {
      context.fileTransfers.begin({
        transferId,
        deviceId: device.id,
        sessionId: access.session.id,
        maxBytes: context.config.fileMaxDownloadBytes,
        now: context.clock.now(),
        channel,
      });
    } catch (error) {
      if (error instanceof TooManyTransfersError) {
        throw new ProtocolError(
          'RATE_LIMITED',
          'Zu viele gleichzeitige Downloads fuer dieses Geraet',
        );
      }
      throw error;
    }

    // If the browser goes away, the device should stop reading rather than finish a
    // transfer nobody is waiting for.
    request.raw.on('close', () => {
      if (!settled) {
        context.fileTransfers.cancel(transferId, 'client_cancelled', 'client disconnected');
      }
    });

    const sent = context.agentConnections.sendToDevice(
      device.id,
      agentFrame(
        'files.download.start',
        { transferId, shareId: query.shareId, fileId: query.fileId },
        context.clock.now(),
        access.session.id,
      ),
    );
    if (sent === 0) {
      context.fileTransfers.cancel(transferId, 'read_error', 'device connection went away');
      throw new ProtocolError('SESSION_EXPIRED', 'Geraet ist nicht mehr verbunden');
    }

    await context.audit.record({
      eventType: 'file.transfer.started',
      result: 'success',
      userId: principal.user.id,
      deviceId: device.deviceId,
      sessionId: access.session.id,
    });

    reply.header('Content-Type', entry.mimeType ?? 'application/octet-stream');
    if (entry.size !== null) {
      reply.header('Content-Length', String(entry.size));
    }
    // RFC 5987. The name is already known to carry no path component; encoding it
    // keeps a quote or a non-ASCII character from breaking out of the header.
    reply.header(
      'Content-Disposition',
      `attachment; filename*=UTF-8''${encodeURIComponent(entry.name)}`,
    );
    reply.header('X-Content-Type-Options', 'nosniff');
    return reply.send(stream);
  });
}
