import { index, integer, sqliteTable, text, uniqueIndex } from 'drizzle-orm/sqlite-core';

/**
 * Drizzle schema. Mirrors the SQL files under `server/drizzle/` one to one;
 * those files remain the source of truth for the actual database, the schema
 * here provides typed access.
 *
 * All timestamps are epoch milliseconds. Secrets exist only as SHA-256 hex.
 */

export const users = sqliteTable(
  'users',
  {
    id: text('id').primaryKey().notNull(),
    email: text('email').notNull(),
    emailNormalized: text('email_normalized').notNull(),
    passwordHash: text('password_hash').notNull(),
    displayName: text('display_name'),
    createdAt: integer('created_at').notNull(),
    updatedAt: integer('updated_at').notNull(),
    disabledAt: integer('disabled_at'),
  },
  (table) => [uniqueIndex('users_email_normalized_unique').on(table.emailNormalized)],
);

export const authSessions = sqliteTable(
  'auth_sessions',
  {
    id: text('id').primaryKey().notNull(),
    userId: text('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    tokenHash: text('token_hash').notNull(),
    createdAt: integer('created_at').notNull(),
    expiresAt: integer('expires_at').notNull(),
    lastSeenAt: integer('last_seen_at').notNull(),
    revokedAt: integer('revoked_at'),
    rotatedFrom: text('rotated_from'),
  },
  (table) => [
    uniqueIndex('auth_sessions_token_hash_unique').on(table.tokenHash),
    index('auth_sessions_user_idx').on(table.userId),
    index('auth_sessions_expires_idx').on(table.expiresAt),
  ],
);

export const devices = sqliteTable(
  'devices',
  {
    id: text('id').primaryKey().notNull(),
    ownerId: text('owner_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    deviceId: text('device_id').notNull(),
    publicKey: text('public_key').notNull(),
    fingerprint: text('fingerprint').notNull(),
    name: text('name').notNull(),
    platform: text('platform').notNull(),
    osVersion: text('os_version').notNull(),
    sdkInt: integer('sdk_int'),
    appVersion: text('app_version').notNull(),
    createdAt: integer('created_at').notNull(),
    updatedAt: integer('updated_at').notNull(),
    lastSeenAt: integer('last_seen_at'),
    revokedAt: integer('revoked_at'),
  },
  (table) => [
    uniqueIndex('devices_device_id_unique').on(table.deviceId),
    index('devices_owner_idx').on(table.ownerId),
  ],
);

export const deviceTokens = sqliteTable(
  'device_tokens',
  {
    id: text('id').primaryKey().notNull(),
    deviceId: text('device_id')
      .notNull()
      .references(() => devices.id, { onDelete: 'cascade' }),
    tokenHash: text('token_hash').notNull(),
    createdAt: integer('created_at').notNull(),
    expiresAt: integer('expires_at'),
    lastUsedAt: integer('last_used_at'),
    revokedAt: integer('revoked_at'),
  },
  (table) => [
    uniqueIndex('device_tokens_token_hash_unique').on(table.tokenHash),
    index('device_tokens_device_idx').on(table.deviceId),
  ],
);

export const pairingSessions = sqliteTable(
  'pairing_sessions',
  {
    id: text('id').primaryKey().notNull(),
    ticketHash: text('ticket_hash').notNull(),
    displayCodeHash: text('display_code_hash').notNull(),
    deviceId: text('device_id').notNull(),
    publicKey: text('public_key').notNull(),
    fingerprint: text('fingerprint').notNull(),
    metadata: text('metadata').notNull(),
    deviceSecretHash: text('device_secret_hash').notNull(),
    status: text('status').notNull(),
    lookupAttempts: integer('lookup_attempts').notNull().default(0),
    createdAt: integer('created_at').notNull(),
    expiresAt: integer('expires_at').notNull(),
    approvedAt: integer('approved_at'),
    rejectedAt: integer('rejected_at'),
    consumedAt: integer('consumed_at'),
    ownerId: text('owner_id').references(() => users.id, { onDelete: 'set null' }),
  },
  (table) => [
    uniqueIndex('pairing_sessions_ticket_hash_unique').on(table.ticketHash),
    index('pairing_sessions_display_code_idx').on(table.displayCodeHash),
    index('pairing_sessions_status_idx').on(table.status, table.expiresAt),
    index('pairing_sessions_device_idx').on(table.deviceId),
  ],
);

export const pairingNonces = sqliteTable(
  'pairing_nonces',
  {
    id: text('id').primaryKey().notNull(),
    deviceId: text('device_id').notNull(),
    nonceHash: text('nonce_hash').notNull(),
    createdAt: integer('created_at').notNull(),
    expiresAt: integer('expires_at').notNull(),
  },
  (table) => [
    uniqueIndex('pairing_nonces_device_nonce_unique').on(table.deviceId, table.nonceHash),
    index('pairing_nonces_expires_idx').on(table.expiresAt),
  ],
);

export const deviceCapabilities = sqliteTable(
  'device_capabilities',
  {
    id: text('id').primaryKey().notNull(),
    deviceId: text('device_id')
      .notNull()
      .references(() => devices.id, { onDelete: 'cascade' }),
    capability: text('capability').notNull(),
    granted: integer('granted').notNull().default(0),
    grantedBy: text('granted_by').references(() => users.id, { onDelete: 'set null' }),
    createdAt: integer('created_at').notNull(),
    updatedAt: integer('updated_at').notNull(),
  },
  (table) => [
    uniqueIndex('device_capabilities_device_capability_unique').on(
      table.deviceId,
      table.capability,
    ),
  ],
);

export const remoteSessions = sqliteTable(
  'remote_sessions',
  {
    id: text('id').primaryKey().notNull(),
    ownerId: text('owner_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    deviceId: text('device_id')
      .notNull()
      .references(() => devices.id, { onDelete: 'cascade' }),
    createdAt: integer('created_at').notNull(),
    expiresAt: integer('expires_at').notNull(),
    revokedAt: integer('revoked_at'),
    requestedCapabilities: text('requested_capabilities').notNull(),
    approvedCapabilities: text('approved_capabilities').notNull(),
  },
  (table) => [
    index('remote_sessions_device_idx').on(table.deviceId),
    index('remote_sessions_owner_idx').on(table.ownerId),
  ],
);

export const auditEvents = sqliteTable(
  'audit_events',
  {
    id: text('id').primaryKey().notNull(),
    userId: text('user_id'),
    deviceId: text('device_id'),
    sessionId: text('session_id'),
    eventType: text('event_type').notNull(),
    result: text('result').notNull(),
    detail: text('detail'),
    createdAt: integer('created_at').notNull(),
  },
  (table) => [
    index('audit_events_created_idx').on(table.createdAt),
    index('audit_events_device_idx').on(table.deviceId, table.createdAt),
    index('audit_events_user_idx').on(table.userId, table.createdAt),
  ],
);
