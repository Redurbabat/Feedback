import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import { DEVICE_NAME_MAX, PROTOCOL_VERSION, PUBLIC_KEY_MAX_BASE64 } from '../../constants.js';
import type { AppContext } from '../../context.js';
import { DEVICE_ID_PATTERN, FINGERPRINT_PATTERN } from '../../crypto/deviceIdentity.js';
import { ProtocolError } from '../../errors.js';
import type { PairingProof } from '../../services/pairingService.js';
import { consumeRateLimit, requireCsrf, requirePrincipal, requireSession } from '../guards.js';
import { assertBrowserContext } from '../security.js';
import { parseOrThrow } from '../validation.js';

/**
 * Pairing endpoints (PROTOCOL.md sections 5 and 6).
 *
 * Device side (`/start`, `/status`, `/claim`) is not a browser context: those
 * routes authenticate via signature and device secret, never via cookies.
 * Control Center side (`/lookup`, `/approve`, `/reject`) requires a session,
 * a CSRF token, a trusted origin and the same proof as the lookup.
 */

const BASE64URL = /^[A-Za-z0-9_-]+$/;
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;

/**
 * Printable single-line text. Control characters (line feed included) are
 * rejected before a value can ever reach a canonical signature payload.
 */
function safeText(max: number) {
  return z
    .string()
    .min(1)
    .max(max)
    .refine((value) => !/\p{Cc}/u.test(value), {
      message: 'enthaelt unzulaessige Steuerzeichen',
    });
}

const deviceSchema = z
  .object({
    deviceId: z.string().regex(DEVICE_ID_PATTERN, 'hat nicht das Format fb-<24 hex>'),
    publicKey: z.string().min(1).max(PUBLIC_KEY_MAX_BASE64).regex(BASE64, 'ist kein Base64'),
    fingerprint: z.string().regex(FINGERPRINT_PATTERN, 'hat nicht das erwartete Format'),
    deviceName: safeText(DEVICE_NAME_MAX),
    // Deny-by-default: v1 only knows the Android agent.
    platform: z.literal('android'),
    osVersion: safeText(32),
    sdkInt: z.number().int().min(1).max(10_000),
    appVersion: safeText(32),
  })
  .strict();

const startSchema = z
  .object({
    version: z.number().int(),
    device: deviceSchema,
    nonce: z.string().min(16).max(64).regex(BASE64URL, 'ist kein Base64Url'),
    issuedAt: z.number().int().min(0).max(4_102_444_800_000),
    signature: z.string().min(16).max(512).regex(BASE64URL, 'ist kein Base64Url'),
  })
  .strict();

const proofSchema = z
  .object({
    ticket: z.string().min(16).max(128).regex(BASE64URL, 'ist kein Base64Url').optional(),
    displayCode: z.string().regex(/^[0-9]{6}$/, 'muss aus sechs Ziffern bestehen').optional(),
  })
  .strict()
  .refine(
    (value) => (value.ticket === undefined) !== (value.displayCode === undefined),
    'Genau eines von ticket oder displayCode ist erforderlich',
  );

const statusSchema = z
  .object({
    deviceSecret: z.string().min(16).max(128).regex(BASE64URL, 'ist kein Base64Url'),
  })
  .strict();

const claimSchema = z
  .object({
    deviceSecret: z.string().min(16).max(128).regex(BASE64URL, 'ist kein Base64Url'),
    issuedAt: z.number().int().min(0).max(4_102_444_800_000),
    signature: z.string().min(16).max(512).regex(BASE64URL, 'ist kein Base64Url'),
  })
  .strict();

const paramsSchema = z.object({ pairingId: z.string().uuid() }).strict();

function assertVersion(value: unknown): void {
  if (typeof value !== 'object' || value === null) {
    return;
  }
  const version = (value as Record<string, unknown>)['version'];
  if (version !== undefined && version !== PROTOCOL_VERSION) {
    throw new ProtocolError('UNSUPPORTED', 'Protokollversion wird nicht unterstuetzt');
  }
}

function toProof(value: {
  ticket?: string | undefined;
  displayCode?: string | undefined;
}): PairingProof {
  return { ticket: value.ticket, displayCode: value.displayCode };
}

export async function registerPairingRoutes(
  app: FastifyInstance,
  context: AppContext,
): Promise<void> {
  app.post('/pairing/start', { config: { rateLimit: 'pairingStart' } }, async (request, reply) => {
    assertVersion(request.body);
    const body = parseOrThrow(startSchema, request.body);

    // Second bucket per claimed device identity. The signature is verified
    // afterwards, so this only narrows the abuse surface, it never replaces
    // the cryptographic check.
    consumeRateLimit(context, 'pairingStart', 'device', body.device.deviceId);

    const result = await context.pairing.start({
      device: body.device,
      nonce: body.nonce,
      issuedAt: body.issuedAt,
      signature: body.signature,
    });

    return reply.code(201).send(result);
  });

  app.post(
    '/pairing/lookup',
    { config: { rateLimit: 'pairingLookup' } },
    async (request, reply) => {
      assertBrowserContext(request, context.config.allowedOrigins);
      await requireSession(context, request, reply);
      requireCsrf(context, request);
      const principal = requirePrincipal(request);
      consumeRateLimit(context, 'pairingLookup', 'user', principal.user.id);

      const body = parseOrThrow(proofSchema, request.body);
      return context.pairing.lookup(toProof(body), { userId: principal.user.id });
    },
  );

  app.post('/pairing/:pairingId/approve', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    await requireSession(context, request, reply);
    requireCsrf(context, request);
    const principal = requirePrincipal(request);
    consumeRateLimit(context, 'pairingLookup', 'user', principal.user.id);

    const params = parseOrThrow(paramsSchema, request.params);
    const body = parseOrThrow(proofSchema, request.body);
    return context.pairing.approve(params.pairingId, toProof(body), {
      userId: principal.user.id,
    });
  });

  app.post('/pairing/:pairingId/reject', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    await requireSession(context, request, reply);
    requireCsrf(context, request);
    const principal = requirePrincipal(request);
    consumeRateLimit(context, 'pairingLookup', 'user', principal.user.id);

    const params = parseOrThrow(paramsSchema, request.params);
    const body = parseOrThrow(proofSchema, request.body);
    return context.pairing.reject(params.pairingId, toProof(body), {
      userId: principal.user.id,
    });
  });

  app.post(
    '/pairing/:pairingId/status',
    { config: { rateLimit: 'pairingStatus' } },
    async (request) => {
      const params = parseOrThrow(paramsSchema, request.params);
      consumeRateLimit(context, 'pairingStatus', 'pairing', params.pairingId);
      const body = parseOrThrow(statusSchema, request.body);
      return context.pairing.status(params.pairingId, body.deviceSecret);
    },
  );

  app.post(
    '/pairing/:pairingId/claim',
    { config: { rateLimit: 'pairingClaim' } },
    async (request) => {
      const params = parseOrThrow(paramsSchema, request.params);
      consumeRateLimit(context, 'pairingClaim', 'pairing', params.pairingId);
      const body = parseOrThrow(claimSchema, request.body);
      return context.pairing.claim(params.pairingId, {
        deviceSecret: body.deviceSecret,
        issuedAt: body.issuedAt,
        signature: body.signature,
      });
    },
  );
}
