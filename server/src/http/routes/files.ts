import { randomUUID } from 'node:crypto';
import { PassThrough } from 'node:stream';

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  AGENT_REQUEST_TIMEOUT_MS,
  FILES_SESSION_TTL_MS,
  FILE_MAX_LIST_ENTRIES,
  FILE_NAME_MAX,
  SHARE_CAPABILITIES_V1,
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

/**
 * The capabilities that can govern a shared area. A session may carry several; which one
 * governs a given area is decided on the device, because only it knows where the area
 * came from.
 */
const SHARE_CAPABILITIES: readonly string[] = SHARE_CAPABILITIES_V1;

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
    kind: z.enum(['tree', 'file', 'collection']),
    /** Exactly one capability governs an area (section 8.3.1). */
    capability: z.enum(['files.read', 'media.photos.read', 'media.videos.read']),
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
const openSessionBodySchema = z
  .object({
    /** Omitted means: everything the owner currently has effective. */
    capabilities: z
      .array(z.enum(['files.read', 'media.photos.read', 'media.videos.read']))
      .min(1)
      .optional(),
  })
  .strict();
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
      !session.approvedCapabilities.some((capability) => SHARE_CAPABILITIES.includes(capability))
    ) {
      throw new ProtocolError('SESSION_EXPIRED', 'Dateisitzung ist nicht mehr gueltig');
    }

    // A session can outlive a grant. If every capability it was opened for has been
    // withdrawn since, it is finished: the device would refuse every area anyway, and
    // saying so here saves a pointless round trip.
    const effective = await effectiveShareCapabilities(device);
    if (!session.approvedCapabilities.some((capability) => effective.includes(capability))) {
      throw new ProtocolError(
        'CAPABILITY_DENIED',
        'Keine der Freigaben dieser Sitzung ist noch wirksam',
      );
    }

    return { device, session, userId };
  }

  /**
   * The share capabilities granted on both sides right now.
   *
   * Each is evaluated on its own: there is no hierarchy, so media.photos.read never opens
   * a files.read area, and files.read never opens a media one.
   */
  async function effectiveShareCapabilities(device: DeviceRecord): Promise<readonly string[]> {
    const serverGranted = grantedCapabilities(
      await context.repositories.deviceCapabilities.listForDevice(device.id),
    );
    const grantedHere = SHARE_CAPABILITIES.filter((capability) =>
      serverGranted.includes(capability),
    );
    // Checked in this order on purpose: "nothing is released" and "the device is not
    // reachable" are different problems, and the caller can only act on the right one.
    if (grantedHere.length === 0) {
      throw new ProtocolError(
        'CAPABILITY_DENIED',
        'Kein Lesezugriff ist serverseitig freigegeben',
      );
    }

    const connections = context.agentConnections.snapshotsForDevice(device.id);
    if (connections.length === 0) {
      throw new ProtocolError('SESSION_EXPIRED', 'Geraet ist nicht verbunden');
    }

    const effective = grantedHere.filter((capability) =>
      connections.some((entry) => entry.deviceGrantedCapabilities.includes(capability)),
    );
    if (effective.length === 0) {
      throw new ProtocolError(
        'CAPABILITY_DENIED',
        'Kein Lesezugriff ist auf dem Geraet freigegeben',
      );
    }
    return effective;
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
        requiredCapabilities: access.session.approvedCapabilities,
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

    const body = parseOrThrow(openSessionBodySchema, request.body ?? {});

    const device = await requireOwnedDevice(context, principal.user.id, params.id);
    if (device.revokedAt !== null) {
      throw new ProtocolError('DEVICE_REVOKED', 'Geraet wurde widerrufen');
    }

    const effective = await effectiveShareCapabilities(device);
    // Asking for nothing in particular means "whatever is effective"; asking for
    // something specific never widens beyond that.
    const requested = body.capabilities ?? effective;
    const approved = requested.filter((capability) => effective.includes(capability));
    if (approved.length === 0) {
      throw new ProtocolError(
        'CAPABILITY_DENIED',
        'Keine der angefragten Freigaben ist auf beiden Seiten wirksam',
      );
    }

    const now = context.clock.now();
    const session = await context.repositories.remoteSessions.create({
      ownerId: principal.user.id,
      deviceId: device.id,
      // Longer than a one-shot session: browsing is a conversation, and a listing
      // that expires halfway through is worse than useless.
      expiresAt: now + FILES_SESSION_TTL_MS,
      requestedCapabilities: requested,
      approvedCapabilities: approved,
    });

    await context.audit.record({
      eventType: 'files.session.open',
      result: 'success',
      userId: principal.user.id,
      deviceId: device.deviceId,
      sessionId: session.id,
      detail: { capabilities: approved.join(',') },
    });

    return {
      sessionId: session.id,
      expiresAt: new Date(session.expiresAt).toISOString(),
      capabilities: approved,
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
