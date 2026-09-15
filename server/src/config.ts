import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { z } from 'zod';

import { FILE_MAX_DOWNLOAD_BYTES } from './constants.js';

/**
 * Configuration is read from the environment and validated with zod.
 *
 * Hard rule: there is no default for any secret. A missing required secret
 * aborts the start with an explicit message instead of falling back to a
 * built-in value.
 */

const booleanFromEnv = z
  .enum(['true', 'false', '1', '0'])
  .transform((value) => value === 'true' || value === '1');

const originSchema = z
  .string()
  .trim()
  .refine((value) => {
    try {
      const url = new URL(value);
      return (
        (url.protocol === 'http:' || url.protocol === 'https:') &&
        url.pathname === '/' &&
        url.search === '' &&
        url.hash === ''
      );
    } catch {
      return false;
    }
  }, 'muss eine Origin der Form https://host[:port] sein');

/** Longest host name DNS carries in its textual form, and the longest single label in it. */
const MAX_HOST_LENGTH = 253;
const MAX_LABEL_LENGTH = 63;

/**
 * A name that can be registered and can hold a certificate - the same rule the app applies in
 * `ServerEndpoint.isRegistrableName`.
 *
 * An IPv4 literal falls out of the all-digit last label rather than needing a rule of its own; an
 * IPv6 literal arrives in brackets and fails the label check. `URL` has already lower cased the
 * hostname and punycoded an internationalised one, so `xn--` stays visible as `xn--`.
 */
function isRegistrableHost(hostname: string): boolean {
  if (hostname.length > MAX_HOST_LENGTH || hostname.endsWith('.') || hostname.startsWith('[')) {
    return false;
  }
  const labels = hostname.split('.');
  if (labels.length < 2 || /^\d+$/u.test(labels[labels.length - 1] ?? '')) {
    return false;
  }
  return labels.every(
    (label) =>
      label.length > 0 &&
      label.length <= MAX_LABEL_LENGTH &&
      /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/u.test(label),
  );
}

/**
 * An origin this server may print as its own address, in the written form the app compares
 * against - or `undefined` when it is not such an address.
 *
 * Deliberately stricter than {@link originSchema}, because the two answer different questions.
 * An allowed origin is a browser origin of the Control Center, and during development that is
 * legitimately `http://localhost:5173`. An address on the setup page is something a visitor is
 * asked to hold against what their app knows, so it has to survive the check the app itself runs
 * (`ServerEndpoint.parse`): https, a registrable host name, no path, no credentials. An address
 * that fails there can never match any link, and printing it would send the visitor looking for
 * a mismatch that is ours rather than theirs.
 *
 * `URL.origin` is the normalisation, and it is the same one both ends already use: lower case,
 * and without a port 443 that was spelled out - RFC 6454 drops it, so the app does too.
 */
export function anchorOrigin(value: string): string | undefined {
  let url: URL;
  try {
    url = new URL(value.trim());
  } catch {
    return undefined;
  }
  if (
    url.protocol !== 'https:' ||
    url.pathname !== '/' ||
    url.search !== '' ||
    url.hash !== '' ||
    url.username !== '' ||
    url.password !== '' ||
    !isRegistrableHost(url.hostname)
  ) {
    return undefined;
  }
  return url.origin;
}

const publicOriginSchema = z.string().transform((value, ctx) => {
  const origin = anchorOrigin(value);
  if (origin === undefined) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message:
        'muss eine https-Origin mit registrierbarem Hostnamen sein, z. B. ' +
        'https://feedback.example.com - ohne Pfad, ohne Zugangsdaten. Genau das verlangt die App ' +
        'von einer Serveradresse; was sie ablehnt, darf die Einrichtungsseite nicht anbieten.',
    });
    return z.NEVER;
  }
  return origin;
});

/**
 * SHA-256 fingerprint of the Android signing certificate, in exactly the notation Google's
 * Digital Asset Links verifier expects: 32 uppercase hex pairs joined by colons.
 *
 * Strict on purpose, because a typo here has nowhere else to surface. The file would still be
 * served, Android would still fetch it, and the verification would simply not match - no request
 * fails, no log line appears, the App Link just stays unverified and the setup link opens a
 * browser page forever. Refusing to start is the only feedback that reaches a human.
 */
const androidCertSha256Schema = z
  .string()
  .trim()
  .regex(
    /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/,
    'muss aus 32 Hex-Paaren in Grossbuchstaben bestehen, durch Doppelpunkte getrennt ' +
      '(die Ausgabe von keytool -list -v)',
  );

/**
 * Android application id. Not a free text field: together with the fingerprint it is the whole
 * claim the asset link file makes, so a value that Android cannot read is a claim nobody verifies.
 */
const androidPackageSchema = z
  .string()
  .trim()
  .regex(
    /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/,
    'muss ein Android-Paketname sein, z. B. com.beispiel.app',
  );

const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  FEEDBACK_HOST: z.string().min(1).default('127.0.0.1'),
  FEEDBACK_PORT: z.coerce.number().int().min(1).max(65_535).default(8080),
  FEEDBACK_DATABASE_FILE: z.string().min(1).default('./data/feedback.db'),
  FEEDBACK_COOKIE_SECRET: z
    .string({ required_error: 'FEEDBACK_COOKIE_SECRET fehlt' })
    .min(32, 'FEEDBACK_COOKIE_SECRET muss mindestens 32 Zeichen lang sein'),
  FEEDBACK_ALLOWED_ORIGINS: z
    .string({ required_error: 'FEEDBACK_ALLOWED_ORIGINS fehlt' })
    .min(1)
    .transform((value) =>
      value
        .split(',')
        .map((entry) => entry.trim())
        .filter((entry) => entry.length > 0),
    )
    .pipe(z.array(originSchema).min(1, 'FEEDBACK_ALLOWED_ORIGINS enthaelt keine gueltige Origin')),
  /**
   * Optional: the public address of *this* server, for the setup page at `/pair`.
   *
   * Not derivable from anything else here. `FEEDBACK_ALLOWED_ORIGINS` names the Control Center,
   * which BETRIEB.md 3.1 explicitly allows to live on a different host than the API - so it
   * answers a different question and must not stand in for this one. Unset, the page names no
   * address at all rather than a guessed one; see `advertisedOrigin` in http/routes/setup.ts.
   */
  FEEDBACK_PUBLIC_ORIGIN: publicOriginSchema.optional(),
  FEEDBACK_SESSION_TTL_MS: z.coerce
    .number()
    .int()
    .min(60_000)
    .max(30 * 24 * 60 * 60 * 1000)
    .default(12 * 60 * 60 * 1000),
  /**
   * Upper bound for one download, capped by the protocol value. A deployment may
   * lower it; it may not raise it above what the protocol fixes, and the device
   * enforces its own limit as well - the smaller of the two wins.
   */
  FEEDBACK_FILE_MAX_DOWNLOAD_BYTES: z.coerce
    .number()
    .int()
    .min(1)
    .max(FILE_MAX_DOWNLOAD_BYTES)
    .default(FILE_MAX_DOWNLOAD_BYTES),
  /**
   * Optional: serve the built control center from this directory.
   *
   * Set it and the browser sees a single origin - which means the HttpOnly session cookie just
   * works, there is no CORS, and one tunnel is enough for a first test. Leave it unset and the
   * server is an API only, which is what a deployment with its own reverse proxy wants.
   */
  FEEDBACK_STATIC_DIR: z.string().min(1).optional(),
  FEEDBACK_TRUST_PROXY: booleanFromEnv.default('false'),
  FEEDBACK_LOG_LEVEL: z
    .enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent'])
    .default('info'),
  FEEDBACK_BOOTSTRAP_EMAIL: z.string().email().optional(),
  FEEDBACK_BOOTSTRAP_PASSWORD: z.string().min(12).optional(),
  /**
   * Optional: fingerprint of the Android signing key this deployment vouches for.
   *
   * Set it and `/.well-known/assetlinks.json` is served, which is what lets the setup link open
   * the app instead of a browser page. Unset and that path answers 404 - a deployment that
   * cannot name a key makes no claim about one.
   */
  FEEDBACK_ANDROID_CERT_SHA256: androidCertSha256Schema.optional(),
  FEEDBACK_ANDROID_PACKAGE: androidPackageSchema.default('com.redurbabat.feedback'),
});

export interface AppConfig {
  readonly nodeEnv: 'development' | 'test' | 'production';
  readonly isProduction: boolean;
  readonly host: string;
  readonly port: number;
  readonly databaseFile: string;
  readonly cookieSecret: string;
  readonly allowedOrigins: readonly string[];
  /**
   * This server's own public address, normalised, or undefined when it was not configured.
   *
   * Only ever a statement the operator made on purpose. Nothing infers it from a request, and
   * nothing infers it from the Control Center origins.
   */
  readonly publicOrigin: string | undefined;
  readonly sessionTtlMs: number;
  readonly fileMaxDownloadBytes: number;
  /** Built control center to serve from the API origin, or undefined for API only. */
  readonly staticDir: string | undefined;
  readonly trustProxy: boolean;
  readonly logLevel: 'fatal' | 'error' | 'warn' | 'info' | 'debug' | 'trace' | 'silent';
  readonly bootstrap:
    | { readonly email: string; readonly password: string }
    | undefined;
  /**
   * The Android app this deployment vouches for in `/.well-known/assetlinks.json`.
   *
   * `certSha256` is one value and not a list, although the file format allows several. A list
   * invites appending on a key rotation, and an appended old fingerprint leaves a key that may
   * be lost, leaked or simply forgotten with a verified claim on this domain - every device that
   * installs an app signed with it treats that app as this server's app. Replacing the single
   * configured value is the only way to rotate, and that is the point.
   *
   * `undefined` means no claim is published at all.
   */
  readonly android: {
    readonly packageName: string;
    readonly certSha256: string | undefined;
  };
}

export class ConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ConfigError';
  }
}

/**
 * Parses and validates the environment. Throws {@link ConfigError} with a
 * readable, secret-free summary when the configuration is incomplete.
 */
/**
 * Reads `server/.env` into the environment, if there is one.
 *
 * `.env.example` and BETRIEB.md both describe a `.env` file, but nothing ever read it: every
 * documented first run died on "FEEDBACK_COOKIE_SECRET fehlt" until the values were exported by
 * hand. Found by writing the first-run script and running it.
 *
 * `process.loadEnvFile` is built into Node 22 - no dependency - and it does not overwrite
 * variables that are already set. That order is the right one: a systemd unit or a container
 * environment must win over a file left lying around from an earlier test.
 *
 * Called from the entry points, never from library code, so a test never picks up a developer's
 * local file.
 */
export function loadEnvFile(): void {
  const here = path.dirname(fileURLToPath(import.meta.url));
  // src/config.ts in development, dist/config.js after a build: the package root is one up.
  const candidates = [path.join(process.cwd(), '.env'), path.resolve(here, '..', '.env')];
  for (const candidate of candidates) {
    if (existsSync(candidate)) {
      process.loadEnvFile(candidate);
      return;
    }
  }
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const candidate: Record<string, unknown> = {};
  for (const key of Object.keys(envSchema.shape)) {
    const value = env[key];
    // Treat empty strings like "not set" so that a commented-out .env entry
    // does not turn into an invalid value.
    if (value !== undefined && value !== '') {
      candidate[key] = value;
    }
  }

  const parsed = envSchema.safeParse(candidate);
  if (!parsed.success) {
    const details = parsed.error.issues
      .map((issue) => `${issue.path.join('.') || '(env)'}: ${issue.message}`)
      .join('\n  ');
    throw new ConfigError(
      `Ungueltige Serverkonfiguration:\n  ${details}\n` +
        'Siehe server/.env.example. Fuer Geheimnisse gibt es bewusst keine Standardwerte.',
    );
  }

  const value = parsed.data;

  const bootstrapEmail = value.FEEDBACK_BOOTSTRAP_EMAIL;
  const bootstrapPassword = value.FEEDBACK_BOOTSTRAP_PASSWORD;
  if ((bootstrapEmail === undefined) !== (bootstrapPassword === undefined)) {
    throw new ConfigError(
      'FEEDBACK_BOOTSTRAP_EMAIL und FEEDBACK_BOOTSTRAP_PASSWORD muessen gemeinsam gesetzt werden ' +
        '(Passwort mindestens 12 Zeichen) oder beide leer bleiben.',
    );
  }

  return {
    nodeEnv: value.NODE_ENV,
    isProduction: value.NODE_ENV === 'production',
    host: value.FEEDBACK_HOST,
    port: value.FEEDBACK_PORT,
    databaseFile: value.FEEDBACK_DATABASE_FILE,
    cookieSecret: value.FEEDBACK_COOKIE_SECRET,
    allowedOrigins: value.FEEDBACK_ALLOWED_ORIGINS,
    publicOrigin: value.FEEDBACK_PUBLIC_ORIGIN,
    sessionTtlMs: value.FEEDBACK_SESSION_TTL_MS,
    fileMaxDownloadBytes: value.FEEDBACK_FILE_MAX_DOWNLOAD_BYTES,
    staticDir: value.FEEDBACK_STATIC_DIR,
    trustProxy: value.FEEDBACK_TRUST_PROXY,
    logLevel: value.FEEDBACK_LOG_LEVEL,
    bootstrap:
      bootstrapEmail !== undefined && bootstrapPassword !== undefined
        ? { email: bootstrapEmail, password: bootstrapPassword }
        : undefined,
    android: {
      packageName: value.FEEDBACK_ANDROID_PACKAGE,
      certSha256: value.FEEDBACK_ANDROID_CERT_SHA256,
    },
  };
}
