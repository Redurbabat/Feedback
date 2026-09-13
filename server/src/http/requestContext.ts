import type { FastifyRequest } from 'fastify';

import type { ResolvedSession } from '../auth/sessionService.js';
import type { AppContext } from '../context.js';
import type { RateLimitName } from './rateLimit.js';

declare module 'fastify' {
  interface FastifyInstance {
    /** Application services shared by all routes. */
    appContext: AppContext;
  }

  interface FastifyRequest {
    /** Set once a valid Control Center session cookie was resolved. */
    principal?: ResolvedSession;
  }

  interface FastifyContextConfig {
    /** Selects the rate limit bucket of PROTOCOL.md section 11 for the route. */
    rateLimit?: RateLimitName;
  }
}

export function routeRateLimitName(request: FastifyRequest): RateLimitName {
  return request.routeOptions.config.rateLimit ?? 'apiDefault';
}
