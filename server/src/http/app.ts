import { randomUUID } from 'node:crypto';

import cookie from '@fastify/cookie';
import Fastify from 'fastify';
import type { FastifyInstance } from 'fastify';

import { MAX_JSON_BODY_BYTES } from '../constants.js';
import type { AppContext } from '../context.js';
import { ProtocolError } from '../errors.js';
import { errorBody, sendProtocolError, toProtocolError } from './errorHandler.js';
import { RATE_LIMITS } from './rateLimit.js';
import { registerAgentRoutes } from './routes/agent.js';
import { registerAuthRoutes } from './routes/auth.js';
import { registerDeviceRoutes } from './routes/devices.js';
import { registerPairingRoutes } from './routes/pairing.js';
import { routeRateLimitName } from './requestContext.js';
import { applyCorsHeaders, applySecurityHeaders, isAllowedOrigin } from './security.js';

const API_PREFIX = '/api/v1';

/**
 * Builds the Fastify application.
 *
 * Cross cutting concerns wired here: request id, redacted structured logging,
 * body limit, security headers, narrow CORS, per-IP rate limiting and the
 * single error serializer of PROTOCOL.md section 10.
 */
export async function buildApp(context: AppContext): Promise<FastifyInstance> {
  const { config } = context;

  const app = Fastify({
    genReqId: () => randomUUID(),
    bodyLimit: MAX_JSON_BODY_BYTES,
    trustProxy: config.trustProxy,
    logger: {
      level: config.logLevel,
      // Defence in depth: even if a serializer is changed later, these paths
      // never reach the log. PROTOCOL.md section 12.
      redact: {
        paths: [
          'req.headers.authorization',
          'req.headers.cookie',
          'req.headers["x-feedback-csrf"]',
          'res.headers["set-cookie"]',
          'req.body',
          'body',
        ],
        censor: '[redacted]',
      },
      serializers: {
        req(request) {
          return {
            requestId: request.id,
            method: request.method,
            // Query strings are dropped so no value can leak through the URL.
            url: request.url.split('?')[0],
            remoteAddress: request.ip,
          };
        },
        res(reply) {
          return { statusCode: reply.statusCode };
        },
      },
    },
  });

  context.errors.setHandler((error, message) => {
    app.log.error({ err: error }, message);
  });

  await app.register(cookie);

  app.decorate('appContext', context);

  app.addHook('onRequest', async (request, reply) => {
    reply.header('X-Request-Id', String(request.id));
    applySecurityHeaders(reply, config.isProduction);
    applyCorsHeaders(request, reply, config.allowedOrigins);
  });

  // CORS preflight for the Control Center. Only configured origins are served;
  // anything else gets a plain 403 without CORS headers.
  app.addHook('onRequest', async (request, reply) => {
    if (request.method !== 'OPTIONS' || !request.url.startsWith(API_PREFIX)) {
      return;
    }
    const origin = request.headers.origin;
    if (!isAllowedOrigin(Array.isArray(origin) ? origin[0] : origin, config.allowedOrigins)) {
      await reply.code(403).send(errorBody('FORBIDDEN', 'Ungueltige Herkunft der Anfrage'));
      return;
    }
    await reply.code(204).send();
  });

  // Per-IP rate limit using the bucket configured on the route.
  app.addHook('onRequest', async (request) => {
    if (!request.url.startsWith(API_PREFIX)) {
      return;
    }
    const name = routeRateLimitName(request);
    const decision = context.rateLimiter.consume(`ip:${name}:${request.ip}`, RATE_LIMITS[name]);
    if (!decision.allowed) {
      throw new ProtocolError('RATE_LIMITED', 'Zu viele Anfragen, bitte spaeter erneut versuchen', {
        retryAfterSeconds: decision.retryAfterSeconds,
        logDetail: { limit: name, scope: 'ip' },
      });
    }
  });

  app.setErrorHandler((error, request, reply) => {
    return sendProtocolError(request, reply, toProtocolError(error));
  });

  app.setNotFoundHandler((request, reply) => {
    return sendProtocolError(
      request,
      reply,
      new ProtocolError('NOT_FOUND', 'Ressource nicht gefunden'),
    );
  });

  app.get('/health', async () => ({ status: 'ok' }));

  await app.register(
    async (instance) => {
      await registerAuthRoutes(instance, context);
      await registerPairingRoutes(instance, context);
      await registerAgentRoutes(instance, context);
      await registerDeviceRoutes(instance, context);
    },
    { prefix: API_PREFIX },
  );

  return app;
}
