import { randomUUID } from 'node:crypto';

import path from 'node:path';

import cookie from '@fastify/cookie';
import fastifyStatic from '@fastify/static';
import websocket from '@fastify/websocket';
import Fastify from 'fastify';
import type { FastifyInstance } from 'fastify';

import { MAX_FRAME_BYTES, MAX_JSON_BODY_BYTES } from '../constants.js';
import type { AppContext } from '../context.js';
import { ProtocolError } from '../errors.js';
import { errorBody, sendProtocolError, toProtocolError } from './errorHandler.js';
import { RATE_LIMITS } from './rateLimit.js';
import { registerAgentRoutes } from './routes/agent.js';
import { registerAuthRoutes } from './routes/auth.js';
import { registerDeviceRoutes } from './routes/devices.js';
import { registerFileRoutes } from './routes/files.js';
import { registerScreenRoutes } from './routes/screen.js';
import { registerPairingRoutes } from './routes/pairing.js';
import { registerSetupRoutes } from './routes/setup.js';
import { routeRateLimitName } from './requestContext.js';
import { applyCorsHeaders, applySecurityHeaders, isAllowedOrigin } from './security.js';

const API_PREFIX = '/api/v1';

/**
 * Optionally serves the built control center from the same origin as the API.
 *
 * One origin is the difference between "works" and "works after you configure a reverse proxy":
 * the HttpOnly session cookie needs no SameSite exception, there is no CORS preflight, and a
 * single tunnel is enough for a first test against a real phone.
 *
 * The plugin only serves files that exist; the API keeps its own prefix, and the shared
 * not-found handler below decides what an unknown path means. Anything under `/api/v1` answers
 * in the protocol's error shape rather than with an HTML page.
 */
async function registerControlCenter(app: FastifyInstance, context: AppContext): Promise<void> {
  const root = context.config.staticDir;
  if (root === undefined) {
    return;
  }

  await app.register(fastifyStatic, { root: path.resolve(root), index: ['index.html'] });
}

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
  // Register before routes so upgrade handling and route hooks are installed
  // for every WebSocket endpoint. `ws` enforces the protocol frame limit too.
  await app.register(websocket, {
    options: {
      maxPayload: MAX_FRAME_BYTES,
      perMessageDeflate: false,
    },
  });

  app.decorate('appContext', context);

  app.addHook('onRequest', async (request, reply) => {
    reply.header('X-Request-Id', String(request.id));
    // Only a response that a browser renders as a document gets the wider policy, and only when
    // this server delivers the Control Center itself. An API only deployment keeps
    // `default-src 'none'` on every path it answers.
    const servesDocument = config.staticDir !== undefined && !request.url.startsWith(API_PREFIX);
    applySecurityHeaders(reply, config.isProduction, servesDocument);
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

  // Per-IP rate limit using the bucket configured on the route. This covers
  // the WebSocket upgrade as well; per-frame validation happens in agent.ts.
  //
  // The API is matched by prefix because an unknown path below it must be limited too. The
  // public setup routes (`/pair`, `/.well-known/assetlinks.json`) carry a marker instead: they
  // are advertised in public and need the same ceiling, but they get their own key scope so a
  // flood against the setup page cannot spend the API budget of an IP the owner shares with it.
  app.addHook('onRequest', async (request) => {
    const isApi = request.url.startsWith(API_PREFIX);
    if (!isApi && request.routeOptions.config.publicSetup !== true) {
      return;
    }
    const name = routeRateLimitName(request);
    const scope = isApi ? 'ip' : 'ip-setup';
    const decision = context.rateLimiter.consume(
      `${scope}:${name}:${request.ip}`,
      RATE_LIMITS[name],
    );
    if (!decision.allowed) {
      throw new ProtocolError('RATE_LIMITED', 'Zu viele Anfragen, bitte spaeter erneut versuchen', {
        retryAfterSeconds: decision.retryAfterSeconds,
        logDetail: { limit: name, scope },
      });
    }
  });

  app.setErrorHandler((error, request, reply) => {
    return sendProtocolError(request, reply, toProtocolError(error));
  });

  // Before the static plugin and before the not-found handler, both on purpose: with
  // FEEDBACK_STATIC_DIR set, `GET /pair` reaches the SPA fallback below and a visitor without
  // the app gets the whole Control Center bundle instead of the one page that tells them what
  // to install. `/.well-known/assetlinks.json` would end the same way - as an HTML page under a
  // name Android reads as JSON.
  await registerSetupRoutes(app, context);

  app.setNotFoundHandler((request, reply) => {
    // The control center is a single page app, so a deep link has to reach index.html rather
    // than a file that does not exist. Everything under the API prefix, and anything that is not
    // a plain GET, keeps the protocol error shape - an HTML page there would break every client
    // that reads section 10.
    if (
      config.staticDir !== undefined &&
      request.method === 'GET' &&
      !request.url.startsWith(API_PREFIX)
    ) {
      return reply.sendFile('index.html');
    }
    return sendProtocolError(
      request,
      reply,
      new ProtocolError('NOT_FOUND', 'Ressource nicht gefunden'),
    );
  });

  app.get('/health', async () => ({ status: 'ok' }));

  await registerControlCenter(app, context);

  await app.register(
    async (instance) => {
      await registerAuthRoutes(instance, context);
      await registerPairingRoutes(instance, context);
      await registerAgentRoutes(instance, context);
      await registerDeviceRoutes(instance, context);
      await registerFileRoutes(instance, context);
      await registerScreenRoutes(instance, context);
    },
    { prefix: API_PREFIX },
  );

  return app;
}
