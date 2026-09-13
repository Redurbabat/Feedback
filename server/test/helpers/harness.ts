import { generateKeyPairSync, randomBytes, sign as cryptoSign } from 'node:crypto';
import type { KeyObject } from 'node:crypto';

import type { FastifyInstance } from 'fastify';

import type { AppConfig } from '../../src/config.js';
import { CSRF_HEADER_NAME, SESSION_COOKIE_NAME } from '../../src/auth/sessionService.js';
import {
  canonicalPairingClaim,
  canonicalPairingStart,
  deriveDeviceId,
  formatFingerprint,
} from '../../src/crypto/deviceIdentity.js';
import { hashPassword } from '../../src/crypto/password.js';
import type { AppContext } from '../../src/context.js';
import { createAppContext } from '../../src/context.js';
import { openDatabase } from '../../src/db/client.js';
import type { DatabaseHandle } from '../../src/db/client.js';
import { runMigrations } from '../../src/db/migrate.js';
import { FILE_MAX_DOWNLOAD_BYTES } from '../../src/constants.js';
import { buildApp } from '../../src/http/app.js';
import { MutableClock } from '../../src/services/clock.js';

/**
 * Test harness: a full application on an in-memory database.
 *
 * Secrets used here are generated per run; no credential is hard coded.
 */

export const TEST_ORIGIN = 'http://localhost:5173';

export function testConfig(overrides: Partial<AppConfig> = {}): AppConfig {
  return {
    nodeEnv: 'test',
    isProduction: false,
    host: '127.0.0.1',
    port: 0,
    databaseFile: ':memory:',
    cookieSecret: randomBytes(32).toString('base64url'),
    allowedOrigins: [TEST_ORIGIN],
    sessionTtlMs: 12 * 60 * 60 * 1000,
    fileMaxDownloadBytes: FILE_MAX_DOWNLOAD_BYTES,
    trustProxy: false,
    logLevel: 'silent',
    bootstrap: undefined,
    ...overrides,
  };
}

export interface Harness {
  readonly app: FastifyInstance;
  readonly context: AppContext;
  readonly clock: MutableClock;
  readonly handle: DatabaseHandle;
  close(): Promise<void>;
}

export async function createHarness(configOverrides: Partial<AppConfig> = {}): Promise<Harness> {
  const config = testConfig(configOverrides);
  const handle = openDatabase(config.databaseFile);
  runMigrations(handle.sqlite);

  const clock = new MutableClock(Date.now());
  const context = createAppContext({ config, db: handle.db, clock });
  const app = await buildApp(context);
  await app.ready();

  return {
    app,
    context,
    clock,
    handle,
    async close(): Promise<void> {
      await app.close();
      handle.close();
    },
  };
}

/** Generates a password that satisfies the minimum length rule. */
export function randomPassword(): string {
  return `Tp-${randomBytes(18).toString('base64url')}`;
}

export async function createTestUser(
  harness: Harness,
  email: string,
  password: string,
): Promise<string> {
  const user = await harness.context.repositories.users.create({
    email,
    passwordHash: await hashPassword(password),
  });
  return user.id;
}

export interface LoggedIn {
  readonly cookie: string;
  readonly csrfToken: string;
  readonly userId: string;
}

function cookieHeader(name: string, value: string): string {
  return `${name}=${value}`;
}

export async function login(
  harness: Harness,
  email: string,
  password: string,
): Promise<LoggedIn> {
  const response = await harness.app.inject({
    method: 'POST',
    url: '/api/v1/auth/login',
    headers: { origin: TEST_ORIGIN, 'sec-fetch-site': 'same-site' },
    payload: { email, password },
  });
  if (response.statusCode !== 200) {
    throw new Error(`login failed with ${response.statusCode}: ${response.body}`);
  }
  const cookie = response.cookies.find((entry) => entry.name === SESSION_COOKIE_NAME);
  if (cookie === undefined) {
    throw new Error('login did not set a session cookie');
  }
  const body = response.json() as { csrfToken: string; user: { id: string } };
  return {
    cookie: cookieHeader(SESSION_COOKIE_NAME, cookie.value),
    csrfToken: body.csrfToken,
    userId: body.user.id,
  };
}

/** Headers of an authenticated, state changing Control Center request. */
export function controlHeaders(session: LoggedIn): Record<string, string> {
  return {
    origin: TEST_ORIGIN,
    'sec-fetch-site': 'same-site',
    cookie: session.cookie,
    [CSRF_HEADER_NAME]: session.csrfToken,
  };
}

export interface TestDevice {
  readonly privateKey: KeyObject;
  readonly publicKeyBase64: string;
  readonly deviceId: string;
  readonly fingerprint: string;
  sign(payload: string): string;
}

export function createTestDevice(curve = 'prime256v1'): TestDevice {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: curve });
  const spki = publicKey.export({ format: 'der', type: 'spki' });
  return {
    privateKey,
    publicKeyBase64: spki.toString('base64'),
    deviceId: deriveDeviceId(spki),
    fingerprint: formatFingerprint(spki),
    sign(payload: string): string {
      return cryptoSign('sha256', Buffer.from(payload, 'utf8'), {
        key: privateKey,
        dsaEncoding: 'der',
      }).toString('base64url');
    },
  };
}

export interface DeviceMetadata {
  readonly deviceName: string;
  readonly platform: 'android';
  readonly osVersion: string;
  readonly sdkInt: number;
  readonly appVersion: string;
}

export const DEFAULT_METADATA: DeviceMetadata = {
  deviceName: 'Testgeraet',
  platform: 'android',
  osVersion: '16',
  sdkInt: 36,
  appVersion: '0.2.0',
};

export interface StartBodyOverrides {
  readonly nonce?: string;
  readonly issuedAt?: number;
  readonly signature?: string;
  readonly deviceId?: string;
  readonly fingerprint?: string;
  readonly metadata?: Partial<DeviceMetadata>;
  readonly version?: number;
}

export function buildStartBody(
  device: TestDevice,
  now: number,
  overrides: StartBodyOverrides = {},
): Record<string, unknown> {
  const metadata: DeviceMetadata = { ...DEFAULT_METADATA, ...overrides.metadata };
  const nonce = overrides.nonce ?? randomBytes(18).toString('base64url');
  const issuedAt = overrides.issuedAt ?? now;
  const deviceId = overrides.deviceId ?? device.deviceId;
  const fingerprint = overrides.fingerprint ?? device.fingerprint;

  const canonical = canonicalPairingStart({
    deviceId,
    publicKeyBase64: device.publicKeyBase64,
    fingerprint,
    deviceName: metadata.deviceName,
    platform: metadata.platform,
    osVersion: metadata.osVersion,
    sdkInt: metadata.sdkInt,
    appVersion: metadata.appVersion,
    nonce,
    issuedAt,
  });

  return {
    version: overrides.version ?? 1,
    device: {
      deviceId,
      publicKey: device.publicKeyBase64,
      fingerprint,
      deviceName: metadata.deviceName,
      platform: metadata.platform,
      osVersion: metadata.osVersion,
      sdkInt: metadata.sdkInt,
      appVersion: metadata.appVersion,
    },
    nonce,
    issuedAt,
    signature: overrides.signature ?? device.sign(canonical),
  };
}

export function buildClaimBody(
  device: TestDevice,
  pairingId: string,
  deviceSecret: string,
  issuedAt: number,
  overrides: { signature?: string; deviceId?: string } = {},
): Record<string, unknown> {
  const canonical = canonicalPairingClaim({
    pairingId,
    deviceId: overrides.deviceId ?? device.deviceId,
    deviceSecret,
    issuedAt,
  });
  return {
    deviceSecret,
    issuedAt,
    signature: overrides.signature ?? device.sign(canonical),
  };
}

export interface StartedPairing {
  readonly pairingId: string;
  readonly ticket: string;
  readonly deviceSecret: string;
  readonly displayCode: string;
  readonly expiresAt: string;
}

export async function startPairing(
  harness: Harness,
  device: TestDevice,
  overrides: StartBodyOverrides = {},
): Promise<StartedPairing> {
  const response = await harness.app.inject({
    method: 'POST',
    url: '/api/v1/pairing/start',
    payload: buildStartBody(device, harness.clock.now(), overrides),
  });
  if (response.statusCode !== 201) {
    throw new Error(`pairing start failed with ${response.statusCode}: ${response.body}`);
  }
  return response.json() as StartedPairing;
}
