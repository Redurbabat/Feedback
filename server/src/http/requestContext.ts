import type { FastifyRequest } from 'fastify';

import type { ResolvedSession } from '../auth/sessionService.js';
import type { AppContext } from '../context.js';
import type { DevicePrincipal } from './deviceAuth.js';
import type { RateLimitName } from './rateLimit.js';

declare module 'fastify' {
  interface FastifyInstance {
    /** Application services shared by all routes. */
    appContext: AppContext;
  }

  interface FastifyRequest {
    /** Set once a valid Control Center session cookie was resolved. */
    principal?: ResolvedSession;
    /** Set during WebSocket pre-validation after device-token authentication. */
    devicePrincipal?: DevicePrincipal;
  }

  interface FastifyContextConfig {
    /** Selects the rate limit bucket of PROTOCOL.md section 11 for the route. */
    rateLimit?: RateLimitName;
    /**
     * Marks a publicly advertised route outside `/api/v1` that is rate limited as well.
     *
     * Declared on the route instead of matched against the URL in the hook, so a query string,
     * a trailing slash or a different spelling cannot walk past a string comparison.
     */
    publicSetup?: boolean;
  }
}

export function routeRateLimitName(request: FastifyRequest): RateLimitName {
  return request.routeOptions.config.rateLimit ?? 'apiDefault';
}
