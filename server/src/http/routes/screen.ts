import { randomUUID } from 'node:crypto';
import { PassThrough } from 'node:stream';

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  SCREEN_MAX_BITRATE_KBPS,
  SCREEN_MAX_DIMENSION,
  SCREEN_MAX_FPS,
  SCREEN_SESSION_TTL_MS,
} from '../../constants.js';
import type { AppContext } from '../../context.js';
import type { DeviceRecord } from '../../db/repositories/types.js';
import { ProtocolError } from '../../errors.js';
import { AgentOfflineError } from '../../services/agentConnections.js';
import {
  TooManyStreamsError,
  type ScreenStopReason,
  type ScreenStreamChannel,
} from '../../services/screenStreams.js';
import {
  agentFrame,
  grantedCapabilities,
  requireBrowserSession,
  requireOwnedDevice,
} from '../deviceAccess.js';
import { consumeRateLimit, requireCsrf } from '../guards.js';
import { assertBrowserContext } from '../security.js';
import { parseOrThrow } from '../validation.js';

const paramsSchema = z.object({ id: z.string().uuid() }).strict();
const sessionBodySchema = z.object({ sessionId: z.string().uuid() }).strict();
const streamQuerySchema = z.object({ sessionId: z.string().uuid() }).strict();

/** Comfortably below the idle timeout of a typical reverse proxy. */
const SSE_KEEPALIVE_INTERVAL_MS = 15_000;

/**
 * Ends a screen session and the stream running under it.
 *
 * Exported because the same two lines have to happen from the device side too
 * (capability withdrawn, device revoked), and a stop that exists twice is a stop
 * that will differ once.
 */
export async function closeScreenSession(
  context: AppContext,
  sessionId: string,
  reason: ScreenStopReason,
  message: string,
): Promise<void> {
  context.screenStreams.stopForSession(sessionId, reason, message);
  context.agentConnections.rejectPendingForSession(sessionId, new AgentOfflineError());
  await context.repositories.remoteSessions.revoke(sessionId, context.clock.now());
}

export async function registerScreenRoutes(
  app: FastifyInstance,
  context: AppContext,
): Promise<void> {
  /**
   * `screen.view` granted on both sides, right now.
   *
   * Checked in this order on purpose: "nothing is released" and "the device is not
   * reachable" are different problems, and only the first is something the owner can
   * act on from the control center.
   */
  function assertServerGrant(granted: readonly string[]): void {
    if (!granted.includes('screen.view')) {
      throw new ProtocolError(
        'CAPABILITY_DENIED',
        'screen.view ist serverseitig nicht freigegeben',
      );
    }
  }

  async function requireEffectiveScreenView(device: DeviceRecord): Promise<void> {
    assertServerGrant(
      grantedCapabilities(
        await context.repositories.deviceCapabilities.listForDevice(device.id),
      ),
    );

    const connections = context.agentConnections.snapshotsForDevice(device.id);
    if (connections.length === 0) {
      throw new ProtocolError('SESSION_EXPIRED', 'Geraet ist nicht verbunden');
    }
    if (!connections.some((entry) => entry.deviceGrantedCapabilities.includes('screen.view'))) {
      throw new ProtocolError(
        'CAPABILITY_DENIED',
        'screen.view ist auf dem Geraet nicht freigegeben',
      );
    }
  }

  /**
   * Re-checks everything, rather than trusting the session row.
   *
   * A screen session lives ten minutes. Withdrawing the capability on either side has
   * to stop the next request immediately - waiting for the session to expire would
   * keep showing a picture the owner already said no to.
   */
  async function requireScreenSession(
    device: DeviceRecord,
    userId: string,
    sessionId: string,
  ): Promise<void> {
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
      !session.approvedCapabilities.includes('screen.view')
    ) {
      throw new ProtocolError('SESSION_EXPIRED', 'Bildschirmsitzung ist nicht mehr gueltig');
    }
    await requireEffectiveScreenView(device);
  }

  // --------------------------------------------------------------- sessions ---

  /**
   * Opens the session only. Nothing is asked of the device yet and nothing is
   * captured: the owner is asked when a viewer actually attaches, so a session that
   * nobody opens never puts a consent dialog on the phone.
   */
  app.post('/devices/:id/screen/session', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    const principal = await requireBrowserSession(context, request, reply);
    requireCsrf(context, request);
    const params = parseOrThrow(paramsSchema, request.params);
    consumeRateLimit(context, 'screenSession', 'user', principal.user.id);

    const device = await requireOwnedDevice(context, principal.user.id, params.id);
    if (device.revokedAt !== null) {
      throw new ProtocolError('DEVICE_REVOKED', 'Geraet wurde widerrufen');
    }
    await requireEffectiveScreenView(device);

    const now = context.clock.now();
    const session = await context.repositories.remoteSessions.create({
      ownerId: principal.user.id,
      deviceId: device.id,
      expiresAt: now + SCREEN_SESSION_TTL_MS,
      requestedCapabilities: ['screen.view'],
      approvedCapabilities: ['screen.view'],
    });

    await context.audit.record({
      eventType: 'screen.session.open',
      result: 'success',
      userId: principal.user.id,
      deviceId: device.deviceId,
      sessionId: session.id,
    });

    return {
      sessionId: session.id,
      expiresAt: new Date(session.expiresAt).toISOString(),
      maxWidth: SCREEN_MAX_DIMENSION,
      maxHeight: SCREEN_MAX_DIMENSION,
      maxFps: SCREEN_MAX_FPS,
      maxBitrateKbps: SCREEN_MAX_BITRATE_KBPS,
    };
  });

  app.delete('/devices/:id/screen/session', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    const principal = await requireBrowserSession(context, request, reply);
    requireCsrf(context, request);
    const params = parseOrThrow(paramsSchema, request.params);
    const body = parseOrThrow(sessionBodySchema, request.body ?? {});
    const device = await requireOwnedDevice(context, principal.user.id, params.id);

    const session = await context.repositories.remoteSessions.findById(body.sessionId);
    if (session === undefined || session.deviceId !== device.id || session.ownerId !== principal.user.id) {
      throw new ProtocolError('NOT_FOUND', 'Sitzung nicht gefunden');
    }

    await closeScreenSession(
      context,
      session.id,
      'client_cancelled',
      'Sitzung wurde im Control Center beendet',
    );
    return { status: 'closed', sessionId: session.id };
  });

  app.post('/devices/:id/screen/keyframe', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    const principal = await requireBrowserSession(context, request, reply);
    requireCsrf(context, request);
    const params = parseOrThrow(paramsSchema, request.params);
    const body = parseOrThrow(sessionBodySchema, request.body ?? {});
    consumeRateLimit(context, 'screenKeyframe', 'user', principal.user.id);

    const device = await requireOwnedDevice(context, principal.user.id, params.id);
    await requireScreenSession(device, principal.user.id, body.sessionId);

    const requested = context.screenStreams.requestKeyframeForSession(body.sessionId);
    if (!requested) {
      throw new ProtocolError('SESSION_EXPIRED', 'Es laeuft kein Bildstrom in dieser Sitzung');
    }
    return { status: 'requested', sessionId: body.sessionId };
  });

  // ----------------------------------------------------------------- stream ---

  /**
   * The picture itself, as Server-Sent Events.
   *
   * A `GET` rather than a second WebSocket: the browser only needs the downward
   * direction, and this way the cookie session and the origin check are the ones that
   * already guard every other route instead of a second authentication path
   * (ADR-004).
   */
  app.get('/devices/:id/screen/stream', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    const principal = await requireBrowserSession(context, request, reply);
    const params = parseOrThrow(paramsSchema, request.params);
    const query = parseOrThrow(streamQuerySchema, request.query);

    const device = await requireOwnedDevice(context, principal.user.id, params.id);
    await requireScreenSession(device, principal.user.id, query.sessionId);

    const streamId = randomUUID();
    const sse = new PassThrough();
    let ended = false;

    const emit = (event: string, data: Record<string, unknown>): void => {
      if (ended) {
        return;
      }
      // SSE frames are line based, so the payload must not contain a raw newline.
      // JSON.stringify escapes them, base64 never produces one.
      sse.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    };

    /**
     * An SSE comment every few seconds.
     *
     * While the owner is being asked, nothing else travels this connection for up to
     * a minute, and an idle proxy closes it long before that. A comment line is
     * ignored by the client and costs six bytes.
     */
    const keepAlive = setInterval(() => {
      if (!ended) {
        sse.write(': ping\n\n');
      }
    }, SSE_KEEPALIVE_INTERVAL_MS);
    keepAlive.unref();

    const channel: ScreenStreamChannel = {
      sendToDevice(type, payload) {
        context.agentConnections.sendToDevice(
          device.id,
          agentFrame(type, payload, context.clock.now(), query.sessionId),
        );
      },
      status(state) {
        emit('status', { state });
      },
      started(info) {
        emit('config', { ...info });
        void context.audit.record({
          eventType: 'screen.stream.started',
          result: 'success',
          userId: principal.user.id,
          deviceId: device.deviceId,
          sessionId: query.sessionId,
          detail: { width: info.width, height: info.height, fps: info.fps },
        });
      },
      frame(frame) {
        emit('frame', {
          sequence: frame.sequence,
          keyFrame: frame.keyFrame,
          timestampUs: frame.timestampUs,
          data: frame.data.toString('base64'),
        });
      },
      end(reason, message) {
        emit('end', { reason, message });
        ended = true;
        clearInterval(keepAlive);
        sse.end();
        void context.audit.record({
          eventType: 'screen.stream.ended',
          result: reason === 'owner_stopped' || reason === 'client_cancelled' ? 'success' : 'failure',
          userId: principal.user.id,
          deviceId: device.deviceId,
          sessionId: query.sessionId,
          detail: { reason },
        });
      },
    };

    try {
      context.screenStreams.begin({
        streamId,
        deviceId: device.id,
        sessionId: query.sessionId,
        now: context.clock.now(),
        channel,
      });
    } catch (error) {
      // The stream never existed, so nothing will ever call `end` to clean up after it.
      clearInterval(keepAlive);
      ended = true;
      sse.destroy();
      if (error instanceof TooManyStreamsError) {
        throw new ProtocolError(
          'RATE_LIMITED',
          'Fuer dieses Geraet laeuft bereits eine Bildschirmuebertragung',
        );
      }
      throw error;
    }

    // If the viewer goes away the device must stop capturing. Not stopping here is
    // exactly the "it keeps filming while nobody watches" case (THREAT_MODEL 4.17).
    request.raw.on('close', () => {
      clearInterval(keepAlive);
      if (!ended) {
        ended = true;
        context.screenStreams.stop(streamId, 'client_cancelled', 'Betrachter hat getrennt');
      }
    });

    const sent = context.agentConnections.sendToDevice(
      device.id,
      agentFrame(
        'screen.start',
        {
          streamId,
          maxWidth: SCREEN_MAX_DIMENSION,
          maxHeight: SCREEN_MAX_DIMENSION,
          maxFps: SCREEN_MAX_FPS,
          maxBitrateKbps: SCREEN_MAX_BITRATE_KBPS,
        },
        context.clock.now(),
        query.sessionId,
      ),
    );
    if (sent === 0) {
      context.screenStreams.stop(streamId, 'connection_lost', 'Geraeteverbindung ist weg');
      throw new ProtocolError('SESSION_EXPIRED', 'Geraet ist nicht mehr verbunden');
    }

    reply.header('Content-Type', 'text/event-stream; charset=utf-8');
    // no-transform keeps a proxy from buffering the stream into uselessness; the
    // X-Accel header does the same for nginx specifically.
    reply.header('Cache-Control', 'no-cache, no-transform');
    reply.header('Connection', 'keep-alive');
    reply.header('X-Accel-Buffering', 'no');
    reply.header('X-Content-Type-Options', 'nosniff');
    emit('open', { streamId, state: 'awaiting_consent' });
    return reply.send(sse);
  });
}
