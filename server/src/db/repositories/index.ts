import type { Db } from '../client.js';
import { SqliteAuditRepository } from './sqliteAudit.js';
import { SqliteAuthSessionRepository } from './sqliteAuthSessions.js';
import {
  SqliteDeviceCapabilityRepository,
  SqliteRemoteSessionRepository,
} from './sqliteCapabilities.js';
import { SqliteDeviceRepository, SqliteDeviceTokenRepository } from './sqliteDevices.js';
import { SqlitePairingNonceRepository, SqlitePairingSessionRepository } from './sqlitePairing.js';
import { SqliteUserRepository } from './sqliteUsers.js';
import type { Repositories } from './types.js';

export * from './types.js';
export { normalizeEmail } from './sqliteUsers.js';

/** Builds the SQLite-backed implementation of the repository layer. */
export function createSqliteRepositories(db: Db): Repositories {
  return {
    users: new SqliteUserRepository(db),
    authSessions: new SqliteAuthSessionRepository(db),
    devices: new SqliteDeviceRepository(db),
    deviceTokens: new SqliteDeviceTokenRepository(db),
    pairingSessions: new SqlitePairingSessionRepository(db),
    pairingNonces: new SqlitePairingNonceRepository(db),
    deviceCapabilities: new SqliteDeviceCapabilityRepository(db),
    remoteSessions: new SqliteRemoteSessionRepository(db),
    audit: new SqliteAuditRepository(db),
  };
}
