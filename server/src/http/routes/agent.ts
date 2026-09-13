import type { FastifyInstance } from 'fastify';

import { IMPLEMENTED_CAPABILITIES_V1 } from '../../constants.js';
import type { AppContext } from '../../context.js';
import { requireDeviceToken } from '../deviceAuth.js';

function toIso(value: number): string {
  return new Date(value).toISOString();
}

export async function registerAgentRoutes(
  app: FastifyInstance,
  context: AppContext,
): Promise<void> {
  app.get('/agent/me', async (request) => {
    const principal = await requireDeviceToken(context, request);
    const capabilities = await context.repositories.deviceCapabilities.listForDevice(
      principal.device.id,
    );
    const granted = capabilities
      .filter((entry) => entry.granted)
      .map((entry) => entry.capability)
      .filter((capability) =>
        (IMPLEMENTED_CAPABILITIES_V1 as readonly string[]).includes(capability),
      );

    return {
      version: 1,
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
}
