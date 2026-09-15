import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';

import type { AppContext } from '../../context.js';
import { ProtocolError } from '../../errors.js';

/**
 * Public setup surface: the page a visitor lands on and the file Android reads before it treats
 * a link to this domain as belonging to the app.
 *
 * Both live outside `/api/v1` because both are opened by hand or by the operating system, not by
 * a client that speaks the protocol. Both are anonymous: no session, no cookie, and - the part
 * that matters - no statement about the pairing state of this deployment. A page that says how
 * many devices are registered, or whose owner is signed in, hands that information to everyone
 * who can read the domain name, and the domain name is printed on a link that gets forwarded.
 *
 * Neither endpoint is a trust anchor. The app never takes a server address from a link; it only
 * accepts a link whose origin is character-for-character the one the build already knows. That
 * is why this page can be served to anyone without thinking about who is asking.
 */

/**
 * Deliberately without any CSS.
 *
 * `DOCUMENT_CSP` has no `'unsafe-inline'`, so a `<style>` element in the head is dropped by the
 * browser; and on an API only deployment (`FEEDBACK_STATIC_DIR` unset) this page is served under
 * `API_CSP`, where `default-src 'none'` blocks a separate stylesheet route as well. Styling this
 * page would therefore mean widening a security policy - for four paragraphs that a browser's
 * default stylesheet already renders readably. The viewport meta element is not CSS and is
 * enough to keep it legible on a phone.
 */
function renderSetupPage(serverOrigin: string): string {
  const origin = escapeHtml(serverOrigin);
  return `<!doctype html>
<html lang="de">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Feedback einrichten</title>
</head>
<body>
<h1>Feedback</h1>
<p>Feedback verwaltet eigene oder ausdruecklich freigegebene Geraete. Die Kopplung laeuft
vollstaendig in der App: sie erzeugt ein Schluesselpaar im sicheren Speicher des Geraets, und der
Besitzer bestaetigt jede Kopplung im Control Center.</p>
<h2>Warum Sie diese Seite sehen</h2>
<p>Auf diesem Geraet hat keine Feedback-App auf den Link reagiert. Entweder ist die App nicht
installiert, oder der Link ist fuer diese Installation noch nicht verifiziert.</p>
<ol>
<li>Feedback auf dem Geraet installieren.</li>
<li>Den Link auf dem Geraet erneut oeffnen. Den Rest uebernimmt die App.</li>
</ol>
<h2>Server</h2>
<p>Diese Seite gehoert zu: <strong>${origin}</strong></p>
<p>Die App uebernimmt aus einem Link niemals eine Serveradresse. Sie akzeptiert ihn nur, wenn er
auf genau den Server zeigt, den sie ohnehin schon kennt. Passt die Adresse oben nicht zu Ihrem
Server, gehoert der Link nicht zu Ihnen.</p>
</body>
</html>
`;
}

/**
 * Exactly the shape Google's verifier reads. Written as an object and serialized, so a stray
 * character cannot turn the claim into something no one notices.
 */
function renderAssetLinks(packageName: string, certSha256: string): string {
  return JSON.stringify([
    {
      relation: ['delegate_permission/common.handle_all_urls'],
      target: {
        namespace: 'android_app',
        package_name: packageName,
        // One fingerprint, because the configuration holds one value. See AppConfig.android:
        // the file format would allow a list, and a list is what turns a key rotation into an
        // append that leaves the old key a verified claim on this domain.
        sha256_cert_fingerprints: [certSha256],
      },
    },
  ]);
}

const HTML_ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
};

function escapeHtml(value: string): string {
  return value.replace(/[&<>"]/g, (character) => HTML_ESCAPES[character] ?? character);
}

function originOf(value: string): string | undefined {
  try {
    return new URL(value).origin;
  } catch {
    return undefined;
  }
}

/**
 * The address the page shows.
 *
 * Always one of the configured origins, never the `Host` header itself. `Host` is chosen by
 * whoever sends the request, and the one piece of hard information on this page - "this is the
 * server you are talking to" - must not be a value the visitor supplied. The header only selects
 * among the configured origins when a deployment has more than one; anything else falls back to
 * the first, which is the origin the deployment advertises.
 */
function advertisedOrigin(request: FastifyRequest, allowedOrigins: readonly string[]): string {
  const configured = allowedOrigins.map((entry) => originOf(entry) ?? entry);
  const host = request.headers.host;
  if (typeof host === 'string') {
    const candidate = originOf(`${request.protocol}://${host}`);
    if (candidate !== undefined && configured.includes(candidate)) {
      return candidate;
    }
  }
  // FEEDBACK_ALLOWED_ORIGINS is validated to hold at least one entry, so the first one exists.
  return configured[0] ?? '';
}

/**
 * `apiDefault` bucket, own key scope.
 *
 * These paths are advertised in public - printed, forwarded, scanned - so they need the same
 * per-IP ceiling as the API. They get their own scope so that flooding the setup page cannot
 * spend the API budget of whoever shares that address: on a phone network the owner and a
 * stranger routinely arrive from the same IP.
 */
const SETUP_ROUTE_CONFIG = { rateLimit: 'apiDefault', publicSetup: true } as const;

export async function registerSetupRoutes(
  app: FastifyInstance,
  context: AppContext,
): Promise<void> {
  const { config } = context;

  async function sendSetupPage(request: FastifyRequest, reply: FastifyReply): Promise<void> {
    reply.header('Content-Type', 'text/html; charset=utf-8');
    await reply.send(renderSetupPage(advertisedOrigin(request, config.allowedOrigins)));
  }

  // Both spellings are registered: Fastify matches a path literally, and a link someone typed
  // or copied with a trailing slash is the same link.
  app.get('/pair', { config: SETUP_ROUTE_CONFIG }, sendSetupPage);
  app.get('/pair/', { config: SETUP_ROUTE_CONFIG }, sendSetupPage);

  app.get(
    '/.well-known/assetlinks.json',
    { config: SETUP_ROUTE_CONFIG },
    async (_request, reply) => {
      const certSha256 = config.android.certSha256;
      if (certSha256 === undefined) {
        // Answered by this route rather than left to the not-found handler: with
        // FEEDBACK_STATIC_DIR set that handler serves the Control Center bundle for any GET, and
        // an HTML page under this name is a broken asset link that is very hard to see.
        throw new ProtocolError('NOT_FOUND', 'Ressource nicht gefunden');
      }
      reply.header('Content-Type', 'application/json');
      // Sent as a buffer, not as a string: Fastify appends `; charset=utf-8` to the content type
      // of a string payload, and the media type of this file is the one thing about it that is
      // prescribed from outside. Verified against the running server, not only in a test.
      await reply.send(
        Buffer.from(renderAssetLinks(config.android.packageName, certSha256), 'utf8'),
      );
    },
  );
}
