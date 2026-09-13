import type { FastifyReply, FastifyRequest } from 'fastify';

import { CSRF_HEADER_NAME, SESSION_COOKIE_NAME } from '../auth/sessionService.js';
import type { AppContext } from '../context.js';
import { ProtocolError } from '../errors.js';
import type { RateLimitName } from './rateLimit.js';
import { RATE_LIMITS } from './rateLimit.js';

/**
 * Route guards: rate limiting per principal, session resolution and the CSRF
 * double submit check.
 */

export function consumeRateLimit(
  context: AppContext,
  name: RateLimitName,
  keyScope: string,
  keyValue: string,
): void {
  const decision = context.rateLimiter.consume(
    `${keyScope}:${name}:${keyValue}`,
    RATE_LIMITS[name],
  );
  if (!decision.allowed) {
    throw new ProtocolError('RATE_LIMITED', 'Zu viele Anfragen, bitte spaeter erneut versuchen', {
      retryAfterSeconds: decision.retryAfterSeconds,
      logDetail: { limit: name, scope: keyScope },
    });
  }
}

function sessionCookie(request: FastifyRequest): string | undefined {
  const raw = request.cookies[SESSION_COOKIE_NAME];
  return raw === undefined || raw.length === 0 ? undefined : raw;
}

/**
 * Requires a valid Control Center session. On success `request.principal` is
 * populated; otherwise UNAUTHORIZED is raised and a stale cookie is cleared.
 */
export async function requireSession(
  context: AppContext,
  request: FastifyRequest,
  reply: FastifyReply,
): Promise<void> {
  const token = sessionCookie(request);
  if (token === undefined) {
    throw new ProtocolError('UNAUTHORIZED', 'Keine gueltige Anmeldung');
  }
  const resolved = await context.sessions.resolve(token);
  if (resolved === undefined) {
    reply.clearCookie(SESSION_COOKIE_NAME, context.sessions.cookieOptions());
    throw new ProtocolError('UNAUTHORIZED', 'Keine gueltige Anmeldung');
  }
  request.principal = resolved;
}

export function requirePrincipal(request: FastifyRequest): NonNullable<FastifyRequest['principal']> {
  const principal = request.principal;
  if (principal === undefined) {
    throw new ProtocolError('UNAUTHORIZED', 'Keine gueltige Anmeldung');
  }
  return principal;
}

/** Double submit check for state changing requests of an authenticated user. */
export function requireCsrf(context: AppContext, request: FastifyRequest): void {
  const principal = requirePrincipal(request);
  const header = request.headers[CSRF_HEADER_NAME];
  const presented = Array.isArray(header) ? header[0] : header;
  if (presented === undefined || !context.sessions.verifyCsrf(principal.session, presented)) {
    throw new ProtocolError('FORBIDDEN', 'CSRF-Token fehlt oder ist ungueltig', {
      logDetail: { reason: 'csrf_invalid', present: presented !== undefined },
    });
  }
}
