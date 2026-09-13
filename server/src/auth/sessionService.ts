import { createHmac } from 'node:crypto';

import type {
  AuthSessionRecord,
  AuthSessionRepository,
  UserRecord,
  UserRepository,
} from '../db/repositories/types.js';
import { randomToken, sha256Hex, timingSafeEqualString } from '../crypto/tokens.js';
import type { Clock } from '../services/clock.js';

/**
 * Server-side Control Center sessions.
 *
 * - The cookie carries a 32 byte random token; only its SHA-256 is stored.
 * - Cookie flags: HttpOnly, SameSite=Strict, Path=/, Secure in production.
 * - Logging in always creates a fresh session id and revokes the previous one
 *   (session rotation / fixation defence).
 * - The CSRF token is derived per session via HMAC over the server secret, so
 *   no additional secret has to be persisted. It is handed to the client by
 *   `GET /auth/session` and must be echoed in `X-Feedback-CSRF` (double submit).
 */

export const SESSION_COOKIE_NAME = 'feedback_session';
export const CSRF_HEADER_NAME = 'x-feedback-csrf';

const CSRF_CONTEXT = 'feedback-csrf-v1';

export interface SessionCookieOptions {
  readonly httpOnly: true;
  readonly sameSite: 'strict';
  readonly secure: boolean;
  readonly path: string;
  readonly maxAge?: number;
}

export interface EstablishedSession {
  readonly session: AuthSessionRecord;
  readonly token: string;
  readonly csrfToken: string;
}

export interface ResolvedSession {
  readonly session: AuthSessionRecord;
  readonly user: UserRecord;
  readonly csrfToken: string;
}

export interface SessionServiceDeps {
  readonly authSessions: AuthSessionRepository;
  readonly users: UserRepository;
  readonly clock: Clock;
  readonly cookieSecret: string;
  readonly sessionTtlMs: number;
  readonly isProduction: boolean;
}

export class SessionService {
  constructor(private readonly deps: SessionServiceDeps) {}

  /** Creates a new session and revokes `rotatedFrom` if one was supplied. */
  async establish(userId: string, rotatedFrom?: string): Promise<EstablishedSession> {
    const now = this.deps.clock.now();
    if (rotatedFrom !== undefined) {
      await this.deps.authSessions.revoke(rotatedFrom, now);
    }
    const token = randomToken(32);
    const session = await this.deps.authSessions.create({
      userId,
      tokenHash: sha256Hex(token),
      expiresAt: now + this.deps.sessionTtlMs,
      rotatedFrom,
    });
    return { session, token, csrfToken: this.csrfTokenFor(session) };
  }

  /**
   * Resolves a cookie token to an active session. Returns undefined for
   * unknown, revoked, expired or disabled principals.
   */
  async resolve(token: string): Promise<ResolvedSession | undefined> {
    if (token.length === 0 || token.length > 128) {
      return undefined;
    }
    const session = await this.deps.authSessions.findByTokenHash(sha256Hex(token));
    if (session === undefined) {
      return undefined;
    }
    const now = this.deps.clock.now();
    if (session.revokedAt !== null || session.expiresAt <= now) {
      return undefined;
    }
    const user = await this.deps.users.findById(session.userId);
    if (user === undefined || user.disabledAt !== null) {
      return undefined;
    }
    await this.deps.authSessions.touch(session.id, now);
    return { session, user, csrfToken: this.csrfTokenFor(session) };
  }

  async revoke(sessionId: string): Promise<void> {
    await this.deps.authSessions.revoke(sessionId, this.deps.clock.now());
  }

  async revokeAllForUser(userId: string): Promise<void> {
    await this.deps.authSessions.revokeAllForUser(userId, this.deps.clock.now());
  }

  csrfTokenFor(session: Pick<AuthSessionRecord, 'id' | 'createdAt'>): string {
    return createHmac('sha256', this.deps.cookieSecret)
      .update(`${CSRF_CONTEXT}:${session.id}:${session.createdAt}`, 'utf8')
      .digest('base64url');
  }

  verifyCsrf(session: Pick<AuthSessionRecord, 'id' | 'createdAt'>, presented: string): boolean {
    if (presented.length === 0) {
      return false;
    }
    return timingSafeEqualString(this.csrfTokenFor(session), presented);
  }

  cookieOptions(expiresAt?: number): SessionCookieOptions {
    const base = {
      httpOnly: true,
      sameSite: 'strict',
      secure: this.deps.isProduction,
      path: '/',
    } as const;
    if (expiresAt === undefined) {
      return base;
    }
    const maxAge = Math.max(0, Math.floor((expiresAt - this.deps.clock.now()) / 1000));
    return { ...base, maxAge };
  }

  async pruneExpired(): Promise<number> {
    return this.deps.authSessions.deleteExpired(this.deps.clock.now());
  }
}
