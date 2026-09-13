import { z } from 'zod';

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
  FEEDBACK_SESSION_TTL_MS: z.coerce
    .number()
    .int()
    .min(60_000)
    .max(30 * 24 * 60 * 60 * 1000)
    .default(12 * 60 * 60 * 1000),
  FEEDBACK_TRUST_PROXY: booleanFromEnv.default('false'),
  FEEDBACK_LOG_LEVEL: z
    .enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent'])
    .default('info'),
  FEEDBACK_BOOTSTRAP_EMAIL: z.string().email().optional(),
  FEEDBACK_BOOTSTRAP_PASSWORD: z.string().min(12).optional(),
});

export interface AppConfig {
  readonly nodeEnv: 'development' | 'test' | 'production';
  readonly isProduction: boolean;
  readonly host: string;
  readonly port: number;
  readonly databaseFile: string;
  readonly cookieSecret: string;
  readonly allowedOrigins: readonly string[];
  readonly sessionTtlMs: number;
  readonly trustProxy: boolean;
  readonly logLevel: 'fatal' | 'error' | 'warn' | 'info' | 'debug' | 'trace' | 'silent';
  readonly bootstrap:
    | { readonly email: string; readonly password: string }
    | undefined;
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
    sessionTtlMs: value.FEEDBACK_SESSION_TTL_MS,
    trustProxy: value.FEEDBACK_TRUST_PROXY,
    logLevel: value.FEEDBACK_LOG_LEVEL,
    bootstrap:
      bootstrapEmail !== undefined && bootstrapPassword !== undefined
        ? { email: bootstrapEmail, password: bootstrapPassword }
        : undefined,
  };
}
