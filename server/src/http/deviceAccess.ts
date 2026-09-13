import { randomUUID } from 'node:crypto';

import type { FastifyReply, FastifyRequest } from 'fastify';

import { IMPLEMENTED_CAPABILITIES_V1, PROTOCOL_VERSION } from '../constants.js';
import type { AppContext } from '../context.js';
import type { DeviceCapabilityRecord, DeviceRecord } from '../db/repositories/types.js';
import { ProtocolError } from '../errors.js';
import { requirePrincipal, requireSession } from './guards.js';

/**
 * Shared device access checks.
 *
 * These live in one place on purpose: an ownership check that exists twice is an
 * ownership check that will differ once.
 */

/**
 * Server side grants, narrowed to what v1 can actually serve.
 *
 * The filter against IMPLEMENTED_CAPABILITIES_V1 is the single switch that decides
 * whether a capability is live: an owner may grant `files.read` in the control
 * center, but until the server lists it as implemented nothing here reports it as
 * granted, so no route can act on it.
 */
export function grantedCapabilities(
  entries: readonly DeviceCapabilityRecord[],
): readonly string[] {
  return entries
    .filter((entry) => entry.granted)
    .map((entry) => entry.capability)
    .filter((capability) =>
      (IMPLEMENTED_CAPABILITIES_V1 as readonly string[]).includes(capability),
    );
}

/**
 * Resolves a device the caller owns.
 *
 * A device belonging to somebody else is reported as NOT_FOUND rather than
 * FORBIDDEN: the difference between the two answers would tell a caller that the id
 * exists.
 */
export async function requireOwnedDevice(
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

export async function requireBrowserSession(
  context: AppContext,
  request: FastifyRequest,
  reply: FastifyReply,
): Promise<NonNullable<FastifyRequest['principal']>> {
  await requireSession(context, request, reply);
  return requirePrincipal(request);
}

/**
 * Builds one request frame. The caller keeps the `messageId`, because that - not the
 * session - is what the answer is correlated on (protocol section 7).
 */
export function agentFrame(
  type: string,
  payload: Record<string, unknown>,
  now: number,
  sessionId: string | null = null,
  // One-way notifications still need an id, but nobody waits for them, so a fresh
  // one is fine. A request whose answer is awaited passes its own.
  messageId: string = randomUUID(),
): string {
  return JSON.stringify({
    version: PROTOCOL_VERSION,
    type,
    messageId,
    sessionId,
    timestamp: new Date(now).toISOString(),
    payload,
  });
}

/** Fails the request unless the device is still paired and not revoked. */
export function assertDeviceUsable(device: DeviceRecord): void {
  if (device.revokedAt !== null) {
    throw new ProtocolError('DEVICE_REVOKED', 'Geraet wurde widerrufen');
  }
}
