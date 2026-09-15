import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';

import type { AppConfig } from '../../config.js';
import { anchorOrigin } from '../../config.js';
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
 * page would therefore mean widening a security policy - for a handful of paragraphs that a
 * browser's default stylesheet already renders readably. The viewport meta element is not CSS
 * and is enough to keep it legible on a phone.
 */
function renderSetupPage(serverOrigin: string | undefined): string {
  // Two versions of one section, never a half sentence with an empty value in it: an address is
  // the thing the visitor is asked to decide by, so the page either states one or says that it
  // cannot.
  const server =
    serverOrigin === undefined
      ? `<p>Diese Seite nennt bewusst keine Adresse. Dieser Server ist ohne
<code>FEEDBACK_PUBLIC_ORIGIN</code> konfiguriert und kann seine eigene oeffentliche Adresse
deshalb nicht sicher angeben - und eine geratene waere genau die Angabe, auf die Sie sich hier
nicht verlassen duerfen.</p>
<p>Die App uebernimmt aus einem Link niemals eine Serveradresse. Sie akzeptiert ihn nur, wenn er
auf genau den Server zeigt, den sie ohnehin schon kennt. Ein Link, der nicht zu Ihrem Server
gehoert, wird deshalb abgelehnt - auch dann, wenn diese Seite ihn nicht benennen kann.</p>`
      : `<p>Diese Seite gehoert zu: <strong>${escapeHtml(serverOrigin)}</strong></p>
<p>Die App uebernimmt aus einem Link niemals eine Serveradresse. Sie akzeptiert ihn nur, wenn er
auf genau den Server zeigt, den sie ohnehin schon kennt. Passt die Adresse oben nicht zu Ihrem
Server, gehoert der Link nicht zu Ihnen.</p>`;
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
${server}
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

/**
 * The address the page names as its own - or `undefined` when this deployment has not been given
 * one it can stand behind.
 *
 * Never the `Host` header: that value is chosen by whoever sends the request, and the one hard
 * statement on this page must not come from the visitor. "Some configured origin" is not good
 * enough either, which is what this used to be. `FEEDBACK_ALLOWED_ORIGINS` names the Control
 * Center, and BETRIEB.md 3.1 explicitly allows that to sit on a different host than the API; on
 * such a deployment the page printed a domain that is not this server at all - under a sentence
 * telling the visitor to reject the link if the domain looks wrong. It also printed whatever
 * scheme was configured, so the shipped `.env.example` produced `http://localhost:5173`: an
 * address the app rejects outright, offered as the thing to compare against.
 *
 * So: `FEEDBACK_PUBLIC_ORIGIN` is the only value that names this server on purpose, and it is
 * checked against the app's own rule before it is ever printed ({@link anchorOrigin}).
 *
 * Without it, one case remains in which the answer is known rather than guessed. With
 * `FEEDBACK_STATIC_DIR` the browser loads the Control Center from this very origin, so this
 * origin has to be among the allowed ones - otherwise the Control Center's own login would fail
 * its Origin check and the deployment would not work at all. If exactly one allowed origin can
 * serve as an anchor, that one is this server.
 *
 * Everything else names nothing. On a page whose whole purpose is that the visitor may rely on
 * the address, a guess presented as a fact is worse than an honest blank.
 */
function advertisedOrigin(config: AppConfig): string | undefined {
  if (config.publicOrigin !== undefined) {
    // Checked here as well, not merely trusted from the configuration: this function is what
    // promises the page never names an address the app would refuse, and a promise that holds
    // only as long as every builder of an AppConfig remembers the rule is not one. It also
    // normalises - a port 443 written out is dropped, because the comparison the visitor is
    // asked to make is character equality.
    return anchorOrigin(config.publicOrigin);
  }
  if (config.staticDir === undefined) {
    return undefined;
  }
  const candidates = new Set<string>();
  for (const entry of config.allowedOrigins) {
    const origin = anchorOrigin(entry);
    if (origin !== undefined) {
      candidates.add(origin);
    }
  }
  const [only] = candidates;
  return candidates.size === 1 ? only : undefined;
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

  // Rendered once: the page depends only on the configuration, so nothing a request carries can
  // change a byte of it - which is also what makes it safe to serve to anyone.
  const page = renderSetupPage(advertisedOrigin(config));

  async function sendSetupPage(_request: FastifyRequest, reply: FastifyReply): Promise<void> {
    reply.header('Content-Type', 'text/html; charset=utf-8');
    await reply.send(page);
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
