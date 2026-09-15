import type { FastifyRequest } from 'fastify';

import { decideDeviceToken } from '../auth/deviceTokenPolicy.js';
import { SECRET_BYTES } from '../constants.js';
import { decodeBase64UrlStrict, sha256Hex } from '../crypto/tokens.js';
import type { AppContext } from '../context.js';
import type { DeviceRecord, DeviceTokenRecord } from '../db/repositories/types.js';
import { ProtocolError } from '../errors.js';
import { revokeDevice } from '../services/deviceRevocation.js';

export interface DevicePrincipal {
  readonly device: DeviceRecord;
  readonly token: DeviceTokenRecord;
  readonly authenticatedAt: number;
}

function bearerToken(request: FastifyRequest): string | undefined {
  const authorization = request.headers.authorization;
  if (authorization === undefined) {
    return undefined;
  }

  const match = /^Bearer\s+([A-Za-z0-9_-]+)$/i.exec(authorization.trim());
  return match?.[1];
}

/**
 * Resolves a device bearer token without ever logging or persisting the raw
 * secret. Successful authentication refreshes token usage and last-seen state.
 */
export async function requireDeviceToken(
  context: AppContext,
  request: FastifyRequest,
): Promise<DevicePrincipal> {
  const presented = bearerToken(request);
  if (presented === undefined) {
    throw new ProtocolError('UNAUTHORIZED', 'Kein gueltiges Geraete-Token');
  }

  const decoded = decodeBase64UrlStrict(presented);
  if (decoded === undefined || decoded.length !== SECRET_BYTES) {
    throw new ProtocolError('UNAUTHORIZED', 'Kein gueltiges Geraete-Token');
  }

  const now = context.clock.now();
  const token = await context.repositories.deviceTokens.findByTokenHash(sha256Hex(presented));
  if (token === undefined) {
    throw new ProtocolError('UNAUTHORIZED', 'Kein gueltiges Geraete-Token');
  }

  const successor =
    token.replacedBy === null
      ? undefined
      : await context.repositories.deviceTokens.findById(token.replacedBy);
  const decision = decideDeviceToken(token, successor, now);

  const device = await context.repositories.devices.findById(token.deviceId);
  if (device === undefined) {
    throw new ProtocolError('UNAUTHORIZED', 'Kein gueltiges Geraete-Token');
  }

  if (decision.kind === 'reuse_detected') {
    /*
     * Two parties hold this chain. Ending the pairing costs the owner a re-pairing; leaving it
     * open costs them a device whose token somebody else also has, with no way to tell from the
     * outside which requests were theirs. The audit entry is what turns this into something the
     * owner can see, so it is written before the answer goes out.
     */
    if (device.revokedAt === null) {
      await revokeDevice(context, { device, reason: 'token_reuse', now });
    }
    throw new ProtocolError('DEVICE_REVOKED', 'Geraete-Token wurde doppelt verwendet');
  }

  if (device.revokedAt !== null) {
    throw new ProtocolError('DEVICE_REVOKED', 'Geraet wurde widerrufen');
  }
  if (decision.kind === 'reject') {
    throw new ProtocolError('UNAUTHORIZED', 'Kein gueltiges Geraete-Token');
  }

  await context.repositories.deviceTokens.touch(token.id, now);
  await context.repositories.devices.touchLastSeen(device.id, now);

  const principal = { device, token, authenticatedAt: now } satisfies DevicePrincipal;
  request.devicePrincipal = principal;
  return principal;
}

export function requireDevicePrincipal(request: FastifyRequest): DevicePrincipal {
  const principal = request.devicePrincipal;
  if (principal === undefined) {
    throw new ProtocolError('UNAUTHORIZED', 'Kein gueltiges Geraete-Token');
  }
  return principal;
}
