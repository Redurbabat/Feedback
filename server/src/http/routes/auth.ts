import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import { SESSION_COOKIE_NAME } from '../../auth/sessionService.js';
import type { AppContext } from '../../context.js';
import { normalizeEmail } from '../../db/repositories/index.js';
import { ProtocolError } from '../../errors.js';
import { consumeRateLimit, requireCsrf, requirePrincipal, requireSession } from '../guards.js';
import { assertBrowserContext } from '../security.js';
import { parseOrThrow } from '../validation.js';

/**
 * Control Center authentication (PROTOCOL.md section 6.2).
 *
 * The session cookie is HttpOnly + SameSite=Strict (Secure in production), the
 * session itself lives in the database and is rotated on every login.
 */

const loginSchema = z
  .object({
    email: z.string().trim().min(3).max(254).email(),
    password: z.string().min(1).max(1024),
  })
  .strict();

interface PublicUser {
  readonly id: string;
  readonly email: string;
  readonly displayName: string | null;
}

function publicUser(user: { id: string; email: string; displayName: string | null }): PublicUser {
  return { id: user.id, email: user.email, displayName: user.displayName };
}

export async function registerAuthRoutes(
  app: FastifyInstance,
  context: AppContext,
): Promise<void> {
  app.post('/auth/login', { config: { rateLimit: 'authLogin' } }, async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    const body = parseOrThrow(loginSchema, request.body);

    // Second bucket keyed by the account, so a distributed attack against one
    // account is limited even when the source addresses change.
    consumeRateLimit(context, 'authLogin', 'account', normalizeEmail(body.email));

    const provider = context.authProviders.require('password');
    const outcome = await provider.authenticate({
      method: 'password',
      email: body.email,
      password: body.password,
    });

    if (!outcome.ok) {
      await context.audit.record({
        eventType: 'auth.login.failure',
        result: 'failure',
        detail: { reason: outcome.reason },
      });
      // Identical answer for unknown account, wrong password and disabled
      // account: the response must not disclose which one applies.
      throw new ProtocolError('UNAUTHORIZED', 'E-Mail-Adresse oder Passwort ist falsch');
    }

    // Session rotation: an existing session of this browser is revoked and
    // replaced by a fresh one.
    const existingToken = request.cookies[SESSION_COOKIE_NAME];
    const existing =
      existingToken === undefined ? undefined : await context.sessions.resolve(existingToken);

    const established = await context.sessions.establish(outcome.user.id, existing?.session.id);

    reply.setCookie(
      SESSION_COOKIE_NAME,
      established.token,
      context.sessions.cookieOptions(established.session.expiresAt),
    );

    await context.audit.record({
      eventType: 'auth.login.success',
      result: 'success',
      userId: outcome.user.id,
      sessionId: established.session.id,
      detail: { rotated: existing !== undefined },
    });

    return {
      user: publicUser(outcome.user),
      csrfToken: established.csrfToken,
      expiresAt: new Date(established.session.expiresAt).toISOString(),
    };
  });

  app.post('/auth/logout', async (request, reply) => {
    assertBrowserContext(request, context.config.allowedOrigins);
    await requireSession(context, request, reply);
    requireCsrf(context, request);

    const principal = requirePrincipal(request);
    await context.sessions.revoke(principal.session.id);
    reply.clearCookie(SESSION_COOKIE_NAME, context.sessions.cookieOptions());

    await context.audit.record({
      eventType: 'auth.logout',
      result: 'success',
      userId: principal.user.id,
      sessionId: principal.session.id,
    });

    return { status: 'ok' };
  });

  app.get('/auth/session', async (request, reply) => {
    await requireSession(context, request, reply);
    const principal = requirePrincipal(request);
    return {
      user: publicUser(principal.user),
      csrfToken: principal.csrfToken,
      expiresAt: new Date(principal.session.expiresAt).toISOString(),
    };
  });
}
