import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import { SESSION_COOKIE_NAME } from '../../auth/sessionService.js';
import { CONTROL_ELEVATION_TTL_MS } from '../../constants.js';
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

const reauthenticateSchema = z
  .object({
    password: z.string().min(1).max(512),
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

  /*
   * The second step in front of granting a capability (THREAT_MODEL 4.5).
   *
   * On the device a grant asks for the app lock again; in the Control Center it asked for nothing,
   * so a taken-over browser window could grant everything server-side with one click. This is the
   * mirror of that prompt: the password, once, for a short window.
   *
   * Rate limited under the same bucket as the login and keyed by the account as well, because it
   * is the same thing being guessed.
   */
  app.post(
    '/auth/reauthenticate',
    { config: { rateLimit: 'authLogin' } },
    async (request, reply) => {
      assertBrowserContext(request, context.config.allowedOrigins);
      await requireSession(context, request, reply);
      requireCsrf(context, request);
      const principal = requirePrincipal(request);
      const body = parseOrThrow(reauthenticateSchema, request.body);

      consumeRateLimit(context, 'authLogin', 'account', normalizeEmail(principal.user.email));

      const outcome = await context.authProviders.require('password').authenticate({
        method: 'password',
        email: principal.user.email,
        password: body.password,
      });
      if (!outcome.ok || outcome.user.id !== principal.user.id) {
        await context.audit.record({
          eventType: 'auth.reauthenticate.failure',
          result: 'failure',
          userId: principal.user.id,
          sessionId: principal.session.id,
        });
        /*
         * REAUTH_REQUIRED, not UNAUTHORIZED. The session is still perfectly valid - only the
         * confirmation did not happen. Answering 401 here would be a statement about the session,
         * and the Control Center would have to log the owner out over a typo.
         */
        throw new ProtocolError('REAUTH_REQUIRED', 'Passwort ist falsch');
      }

      await context.sessions.elevate(principal.session.id);
      await context.audit.record({
        eventType: 'auth.reauthenticate',
        result: 'success',
        userId: principal.user.id,
        sessionId: principal.session.id,
      });

      return {
        elevatedUntil: new Date(context.clock.now() + CONTROL_ELEVATION_TTL_MS).toISOString(),
      };
    },
  );

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
