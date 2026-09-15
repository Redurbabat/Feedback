import type { FastifyReply, FastifyRequest } from 'fastify';

import { ProtocolError } from '../errors.js';

/**
 * Browser-facing hardening: response headers, a narrow CORS allowance for the
 * configured Control Center origins and the Origin / Sec-Fetch-Site check that
 * accompanies the CSRF token on state changing requests.
 */

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

export const CORS_ALLOWED_HEADERS = 'content-type, x-feedback-csrf';
export const CORS_ALLOWED_METHODS = 'GET, POST, PUT, DELETE, OPTIONS';

/**
 * For API answers. They are JSON read by a script that is already running, so nothing at all may
 * be loaded from them.
 */
export const API_CSP = "default-src 'none'; frame-ancestors 'none'";

/**
 * For the Control Center, when the server delivers it itself (`FEEDBACK_STATIC_DIR`).
 *
 * The API policy cannot be used here: a document under `default-src 'none'` may not load its own
 * script or its own stylesheet, so the page arrives and stays blank - with the reason only visible
 * in the browser console. Verified in Chromium against the built bundle, because an HTTP level
 * test sees a 200 with the right content type and cannot see a policy violation at all.
 *
 * Everything stays `'self'`: no inline script, no inline style, no foreign origin. `connect-src`
 * covers the API calls and the `screen.view` event stream, `data:` under `img-src` covers a QR
 * code drawn into the page. `base-uri` and `form-action` are closed because this page has neither.
 */
export const DOCUMENT_CSP = [
  "default-src 'none'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data:",
  "connect-src 'self'",
  "font-src 'self'",
  "base-uri 'none'",
  "form-action 'none'",
  "frame-ancestors 'none'",
].join('; ');

export function applySecurityHeaders(
  reply: FastifyReply,
  isProduction: boolean,
  servesDocument = false,
): void {
  reply.header('X-Content-Type-Options', 'nosniff');
  reply.header('X-Frame-Options', 'DENY');
  reply.header('Referrer-Policy', 'no-referrer');
  reply.header('Cross-Origin-Opener-Policy', 'same-origin');
  reply.header('Cross-Origin-Resource-Policy', 'same-origin');
  reply.header('Content-Security-Policy', servesDocument ? DOCUMENT_CSP : API_CSP);
  reply.header('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
  reply.header('Cache-Control', 'no-store');
  if (isProduction) {
    reply.header('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
  }
}

export function isAllowedOrigin(origin: string | undefined, allowed: readonly string[]): boolean {
  return origin !== undefined && allowed.includes(origin);
}

function headerValue(request: FastifyRequest, name: string): string | undefined {
  const value = request.headers[name];
  if (Array.isArray(value)) {
    return value[0];
  }
  return value;
}

/** Adds CORS headers for configured origins only. Never uses a wildcard. */
export function applyCorsHeaders(
  request: FastifyRequest,
  reply: FastifyReply,
  allowedOrigins: readonly string[],
): void {
  const origin = headerValue(request, 'origin');
  reply.header('Vary', 'Origin');
  if (!isAllowedOrigin(origin, allowedOrigins)) {
    return;
  }
  reply.header('Access-Control-Allow-Origin', origin as string);
  reply.header('Access-Control-Allow-Credentials', 'true');
  reply.header('Access-Control-Allow-Headers', CORS_ALLOWED_HEADERS);
  reply.header('Access-Control-Allow-Methods', CORS_ALLOWED_METHODS);
  reply.header('Access-Control-Max-Age', '600');
}

/**
 * Requires a trustworthy browser context for every state changing request of
 * the Control Center API:
 *  - `Origin` must be present and configured,
 *  - `Sec-Fetch-Site` must not indicate a cross-site request.
 *
 * Device endpoints (pairing start/status/claim) are not browser endpoints and
 * do not use this guard; they are protected by signature and device secret.
 */
export function assertBrowserContext(
  request: FastifyRequest,
  allowedOrigins: readonly string[],
): void {
  if (SAFE_METHODS.has(request.method)) {
    return;
  }

  const origin = headerValue(request, 'origin');
  if (!isAllowedOrigin(origin, allowedOrigins)) {
    throw new ProtocolError('FORBIDDEN', 'Ungueltige Herkunft der Anfrage', {
      logDetail: { reason: 'origin_not_allowed', originPresent: origin !== undefined },
    });
  }

  const site = headerValue(request, 'sec-fetch-site');
  if (site !== undefined && site !== 'same-origin' && site !== 'same-site') {
    throw new ProtocolError('FORBIDDEN', 'Ungueltige Herkunft der Anfrage', {
      logDetail: { reason: 'sec_fetch_site', site },
    });
  }
}
