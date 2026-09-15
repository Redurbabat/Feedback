import { randomUUID } from 'node:crypto';

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  AGENT_REQUEST_TIMEOUT_MS,
  HEARTBEAT_INTERVAL_MS,
  HEARTBEAT_MISS_LIMIT,
  IMPLEMENTED_CAPABILITIES_V1,
  REMOTE_SESSION_TTL_MS,
} from '../../constants.js';
import type { AppContext } from '../../context.js';
import type { DeviceCapabilityRecord, DeviceRecord } from '../../db/repositories/types.js';
import { ProtocolError } from '../../errors.js';
import {
  AgentCapabilityUnavailableError,
  AgentOfflineError,
  AgentRequestTimeoutError,
} from '../../services/agentConnections.js';
import { revokeDevice } from '../../services/deviceRevocation.js';
import {
  agentFrame,
  grantedCapabilities,
  requireBrowserSession,
  requireOwnedDevice,
} from '../deviceAccess.js';
import { requireCsrf } from '../guards.js';
import { assertBrowserContext } from '../security.js';
import { parseOrThrow } from '../validation.js';

const paramsSchema = z.object({ id: z.string().uuid() }).strict();
const auditQuerySchema = z
  .object({
    limit: z.coerce.number().int().min(1).max(200).default(100),
  })
  .strict();
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
const capabilityUpdateSchema = z
  .object({
    grantedCapabilities: z.array(capabilitySchema).max(8),
  })
  .strict();

function toIso(value: number | null): string | null {
  return value === null ? null : new Date(value).toISOString();
}

function isOnline(device: DeviceRecord, now: number, activelyConnected: boolean): boolean {
  if (device.revokedAt !== null) {
    return false;
  }
  if (activelyConnected) {
    return true;
  }
  if (device.lastSeenAt === null) {
    return false;
  }
  return now - device.lastSeenAt <= HEARTBEAT_INTERVAL_MS * HEARTBEAT_MISS_LIMIT;
}

function toDeviceView(
  context: AppContext,
  device: DeviceRecord,
  capabilities: readonly DeviceCapabilityRecord[],
  now: number,
): Record<string, unknown> {
  return {
    id: device.id,
    deviceId: device.deviceId,
    fingerprint: device.fingerprint,
    name: device.name,
    platform: device.platform,
    osVersion: device.osVersion,
    sdkInt: device.sdkInt,
    appVersion: device.appVersion,
    pairedAt: new Date(device.createdAt).toISOString(),
    updatedAt: new Date(device.updatedAt).toISOString(),
    lastSeenAt: toIso(device.lastSeenAt),
    revokedAt: toIso(device.revokedAt),
    online: isOnline(device, now, context.agentConnections.isConnected(device.id)),
    serverGrantedCapabilities: grantedCapabilities(capabilities),
  };
}

function parseAuditDetail(value: string | null): unknown {
  if (value === null) {
    return null;
  }
  try {
    return JSON.parse(value) as unknown;
  } catch {
    return null;
  }
}

export async function registerDeviceRoutes(
  app: FastifyInstance,
  context: AppContext,
): Promise<void> {
  app.get('/devices', async (request, reply) => {
    const principal = await requireBrowserSession(context, request, reply);
    const devices = await context.repositories.devices.listByOwner(principal.user.id);
    const now = context.clock.now();
    const views = await Promise.all(
      devices.map(async (device) =>
        toDeviceView(
          context,
          device,
          await context.repositories.deviceCapabilities.listForDevice(device.id),
          now,
        ),
      ),
    );
    return { devices: views, serverTime: new Date(now).toISOString() };
  });

  app.get('/devices/:id', async (request, reply) => {
    const principal = await requireBrowserSession(context, request, reply);
    const params = parseOrThrow(paramsSchema, request.params);
    const device = await requireOwnedDevice(context, principal.user.id, params.id);
    const capabilities = await context.repositories.deviceCapabilities.listForDevice(device.id);
    return toDeviceView(context, device, capabilities, context.clock.now());
  });

  app.put('/devices/:id/capabilities', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    const principal = await requireBrowserSession(context, request, reply);
    requireCsrf(context, request);
    const params = parseOrThrow(paramsSchema, request.params);
    const body = parseOrThrow(capabilityUpdateSchema, request.body);
    const device = await requireOwnedDevice(context, principal.user.id, params.id);

    const requested = new Set(body.grantedCapabilities);
    for (const capability of requested) {
      if (!(IMPLEMENTED_CAPABILITIES_V1 as readonly string[]).includes(capability)) {
        throw new ProtocolError('UNSUPPORTED', `Capability ${capability} ist noch nicht implementiert`);
      }
    }

    /*
     * The second step, and only in one direction (THREAT_MODEL 4.5).
     *
     * On the device a grant asks for the app lock again. Here it asked for nothing, so a
     * taken-over browser window could grant everything with one click. Now it needs the password
     * confirmed within CONTROL_ELEVATION_TTL_MS.
     *
     * Taking a capability away never does. Making a revocation harder than a grant would mean
     * that the moment the owner most wants to act - something is wrong, close it now - is the
     * moment they have to go looking for a password. The asymmetry is the point.
     */
    const before = await context.repositories.deviceCapabilities.listForDevice(device.id);
    const alreadyGranted = new Set(grantedCapabilities(before));
    const added = [...requested].filter((capability) => !alreadyGranted.has(capability));
    if (added.length > 0 && !context.sessions.isElevated(principal.session)) {
      throw new ProtocolError(
        'REAUTH_REQUIRED',
        'Zum Freigeben einer Faehigkeit bitte das Passwort bestaetigen',
        { logDetail: { added: added.length } },
      );
    }

    for (const capability of IMPLEMENTED_CAPABILITIES_V1) {
      await context.repositories.deviceCapabilities.setGranted({
        deviceId: device.id,
        capability,
        granted: requested.has(capability),
        grantedBy: principal.user.id,
      });
    }

    await context.audit.record({
      eventType: 'device.capabilities.update',
      result: 'success',
      userId: principal.user.id,
      deviceId: device.deviceId,
      detail: { grantedCount: requested.size },
    });

    const updated = await context.repositories.deviceCapabilities.listForDevice(device.id);
    const serverGranted = grantedCapabilities(updated);
    const now = context.clock.now();
    context.agentConnections.sendToDevice(
      device.id,
      agentFrame('capability.update', { serverGrantedCapabilities: serverGranted }, now),
    );

    return {
      deviceId: device.id,
      serverGrantedCapabilities: serverGranted,
    };
  });

  app.post('/devices/:id/system-info', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    const principal = await requireBrowserSession(context, request, reply);
    requireCsrf(context, request);
    const params = parseOrThrow(paramsSchema, request.params);
    const device = await requireOwnedDevice(context, principal.user.id, params.id);
    if (device.revokedAt !== null) {
      throw new ProtocolError('DEVICE_REVOKED', 'Geraet wurde widerrufen');
    }

    const serverCapabilities = grantedCapabilities(
      await context.repositories.deviceCapabilities.listForDevice(device.id),
    );
    if (!serverCapabilities.includes('system.info')) {
      throw new ProtocolError('CAPABILITY_DENIED', 'system.info ist serverseitig nicht freigegeben');
    }

    const connections = context.agentConnections.snapshotsForDevice(device.id);
    if (connections.length === 0) {
      throw new ProtocolError('SESSION_EXPIRED', 'Geraet ist nicht verbunden');
    }
    if (!connections.some((entry) => entry.deviceGrantedCapabilities.includes('system.info'))) {
      throw new ProtocolError('CAPABILITY_DENIED', 'system.info ist auf dem Geraet nicht freigegeben');
    }

    const startedAt = context.clock.now();
    const remote = await context.repositories.remoteSessions.create({
      ownerId: principal.user.id,
      deviceId: device.id,
      expiresAt: startedAt + REMOTE_SESSION_TTL_MS,
      requestedCapabilities: ['system.info'],
      approvedCapabilities: ['system.info'],
    });

    const messageId = randomUUID();

    try {
      const payload = await context.agentConnections.requestDevice({
        deviceId: device.id,
        sessionId: remote.id,
        messageId,
        requiredCapabilities: ['system.info'],
        frame: agentFrame('system.info.request', {}, startedAt, remote.id, messageId),
        timeoutMs: AGENT_REQUEST_TIMEOUT_MS,
      });

      await context.audit.record({
        eventType: 'system.info.request',
        result: 'success',
        userId: principal.user.id,
        deviceId: device.deviceId,
        sessionId: remote.id,
      });

      return {
        sessionId: remote.id,
        systemInfo: payload,
        serverTime: new Date(context.clock.now()).toISOString(),
      };
    } catch (error) {
      const denied = error instanceof AgentCapabilityUnavailableError;
      await context.audit.record({
        eventType: 'system.info.request',
        result: denied ? 'denied' : 'failure',
        userId: principal.user.id,
        deviceId: device.deviceId,
        sessionId: remote.id,
      });

      if (error instanceof AgentCapabilityUnavailableError) {
        throw new ProtocolError('CAPABILITY_DENIED', 'system.info ist auf dem Geraet nicht freigegeben');
      }
      if (error instanceof AgentOfflineError) {
        throw new ProtocolError('SESSION_EXPIRED', 'Geraet ist nicht mehr verbunden');
      }
      if (error instanceof AgentRequestTimeoutError) {
        throw new ProtocolError('SESSION_EXPIRED', 'Geraet hat nicht rechtzeitig geantwortet');
      }
      throw error;
    } finally {
      await context.repositories.remoteSessions.revoke(remote.id, context.clock.now());
    }
  });

  app.post('/devices/:id/revoke', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    const principal = await requireBrowserSession(context, request, reply);
    requireCsrf(context, request);
    const params = parseOrThrow(paramsSchema, request.params);
    const device = await requireOwnedDevice(context, principal.user.id, params.id);
    const now = context.clock.now();

    await revokeDevice(context, {
      device,
      reason: 'revoked_by_owner',
      now,
      userId: principal.user.id,
    });

    return {
      status: 'revoked',
      deviceId: device.id,
      revokedAt: new Date(now).toISOString(),
    };
  });

  app.get('/devices/:id/audit', async (request, reply) => {
    const principal = await requireBrowserSession(context, request, reply);
    const params = parseOrThrow(paramsSchema, request.params);
    const query = parseOrThrow(auditQuerySchema, request.query);
    const device = await requireOwnedDevice(context, principal.user.id, params.id);
    const events = await context.repositories.audit.listForDevice(device.deviceId, query.limit);

    return {
      events: events.map((event) => ({
        id: event.id,
        sessionId: event.sessionId,
        eventType: event.eventType,
        result: event.result,
        detail: parseAuditDetail(event.detail),
        createdAt: new Date(event.createdAt).toISOString(),
      })),
    };
  });
}
