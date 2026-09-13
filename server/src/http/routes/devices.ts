import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';

import {
  HEARTBEAT_INTERVAL_MS,
  HEARTBEAT_MISS_LIMIT,
  IMPLEMENTED_CAPABILITIES_V1,
} from '../../constants.js';
import type { AppContext } from '../../context.js';
import type { DeviceCapabilityRecord, DeviceRecord } from '../../db/repositories/types.js';
import { ProtocolError } from '../../errors.js';
import { requireCsrf, requirePrincipal, requireSession } from '../guards.js';
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

function isOnline(device: DeviceRecord, now: number): boolean {
  if (device.revokedAt !== null || device.lastSeenAt === null) {
    return false;
  }
  return now - device.lastSeenAt <= HEARTBEAT_INTERVAL_MS * HEARTBEAT_MISS_LIMIT;
}

function grantedCapabilities(entries: readonly DeviceCapabilityRecord[]): readonly string[] {
  return entries
    .filter((entry) => entry.granted)
    .map((entry) => entry.capability)
    .filter((capability) =>
      (IMPLEMENTED_CAPABILITIES_V1 as readonly string[]).includes(capability),
    );
}

function toDeviceView(
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
    online: isOnline(device, now),
    serverGrantedCapabilities: grantedCapabilities(capabilities),
  };
}

async function requireOwnedDevice(
  context: AppContext,
  ownerId: string,
  id: string,
): Promise<DeviceRecord> {
  const device = await context.repositories.devices.findById(id);
  if (device === undefined || device.ownerId !== ownerId) {
    throw new ProtocolError('NOT_FOUND', 'Geraet nicht gefunden');
  }
  return device;
}

async function requireBrowserSession(
  context: AppContext,
  request: FastifyRequest,
  reply: FastifyReply,
): Promise<NonNullable<FastifyRequest['principal']>> {
  await requireSession(context, request, reply);
  return requirePrincipal(request);
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
    return toDeviceView(device, capabilities, context.clock.now());
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
    return {
      deviceId: device.id,
      serverGrantedCapabilities: grantedCapabilities(updated),
    };
  });

  app.post('/devices/:id/revoke', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    const principal = await requireBrowserSession(context, request, reply);
    requireCsrf(context, request);
    const params = parseOrThrow(paramsSchema, request.params);
    const device = await requireOwnedDevice(context, principal.user.id, params.id);
    const now = context.clock.now();

    await context.repositories.devices.revoke(device.id, now);
    await context.repositories.deviceTokens.revokeAllForDevice(device.id, now);
    await context.repositories.remoteSessions.revokeAllForDevice(device.id, now);
    await context.repositories.deviceCapabilities.clearForDevice(device.id);

    await context.audit.record({
      eventType: 'device.revoke',
      result: 'success',
      userId: principal.user.id,
      deviceId: device.deviceId,
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
