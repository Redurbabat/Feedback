-- Feedback control plane - initial schema (protocol v1).
--
-- All timestamps are epoch milliseconds (INTEGER).
-- Secrets are stored exclusively as SHA-256 hex digests.

CREATE TABLE users (
  id              TEXT PRIMARY KEY NOT NULL,
  email           TEXT NOT NULL,
  email_normalized TEXT NOT NULL,
  password_hash   TEXT NOT NULL,
  display_name    TEXT,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL,
  disabled_at     INTEGER
);

CREATE UNIQUE INDEX users_email_normalized_unique ON users (email_normalized);

CREATE TABLE auth_sessions (
  id            TEXT PRIMARY KEY NOT NULL,
  user_id       TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  token_hash    TEXT NOT NULL,
  created_at    INTEGER NOT NULL,
  expires_at    INTEGER NOT NULL,
  last_seen_at  INTEGER NOT NULL,
  revoked_at    INTEGER,
  rotated_from  TEXT
);

CREATE UNIQUE INDEX auth_sessions_token_hash_unique ON auth_sessions (token_hash);
CREATE INDEX auth_sessions_user_idx ON auth_sessions (user_id);
CREATE INDEX auth_sessions_expires_idx ON auth_sessions (expires_at);

CREATE TABLE devices (
  id           TEXT PRIMARY KEY NOT NULL,
  owner_id     TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  device_id    TEXT NOT NULL,
  public_key   TEXT NOT NULL,
  fingerprint  TEXT NOT NULL,
  name         TEXT NOT NULL,
  platform     TEXT NOT NULL,
  os_version   TEXT NOT NULL,
  sdk_int      INTEGER,
  app_version  TEXT NOT NULL,
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL,
  last_seen_at INTEGER,
  revoked_at   INTEGER
);

CREATE UNIQUE INDEX devices_device_id_unique ON devices (device_id);
CREATE INDEX devices_owner_idx ON devices (owner_id);

CREATE TABLE device_tokens (
  id           TEXT PRIMARY KEY NOT NULL,
  device_id    TEXT NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
  token_hash   TEXT NOT NULL,
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER,
  last_used_at INTEGER,
  revoked_at   INTEGER
);

CREATE UNIQUE INDEX device_tokens_token_hash_unique ON device_tokens (token_hash);
CREATE INDEX device_tokens_device_idx ON device_tokens (device_id);

CREATE TABLE pairing_sessions (
  id                TEXT PRIMARY KEY NOT NULL,
  ticket_hash       TEXT NOT NULL,
  display_code_hash TEXT NOT NULL,
  device_id         TEXT NOT NULL,
  public_key        TEXT NOT NULL,
  fingerprint       TEXT NOT NULL,
  metadata          TEXT NOT NULL,
  device_secret_hash TEXT NOT NULL,
  status            TEXT NOT NULL,
  lookup_attempts   INTEGER NOT NULL DEFAULT 0,
  created_at        INTEGER NOT NULL,
  expires_at        INTEGER NOT NULL,
  approved_at       INTEGER,
  rejected_at       INTEGER,
  consumed_at       INTEGER,
  owner_id          TEXT REFERENCES users (id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX pairing_sessions_ticket_hash_unique ON pairing_sessions (ticket_hash);
CREATE INDEX pairing_sessions_display_code_idx ON pairing_sessions (display_code_hash);
CREATE INDEX pairing_sessions_status_idx ON pairing_sessions (status, expires_at);
CREATE INDEX pairing_sessions_device_idx ON pairing_sessions (device_id);

CREATE TABLE pairing_nonces (
  id         TEXT PRIMARY KEY NOT NULL,
  device_id  TEXT NOT NULL,
  nonce_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX pairing_nonces_device_nonce_unique ON pairing_nonces (device_id, nonce_hash);
CREATE INDEX pairing_nonces_expires_idx ON pairing_nonces (expires_at);

CREATE TABLE device_capabilities (
  id          TEXT PRIMARY KEY NOT NULL,
  device_id   TEXT NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
  capability  TEXT NOT NULL,
  granted     INTEGER NOT NULL DEFAULT 0,
  granted_by  TEXT REFERENCES users (id) ON DELETE SET NULL,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);

CREATE UNIQUE INDEX device_capabilities_device_capability_unique
  ON device_capabilities (device_id, capability);

CREATE TABLE remote_sessions (
  id                      TEXT PRIMARY KEY NOT NULL,
  owner_id                TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  device_id               TEXT NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
  created_at              INTEGER NOT NULL,
  expires_at              INTEGER NOT NULL,
  revoked_at              INTEGER,
  requested_capabilities  TEXT NOT NULL,
  approved_capabilities   TEXT NOT NULL
);

CREATE INDEX remote_sessions_device_idx ON remote_sessions (device_id);
CREATE INDEX remote_sessions_owner_idx ON remote_sessions (owner_id);

CREATE TABLE audit_events (
  id         TEXT PRIMARY KEY NOT NULL,
  user_id    TEXT,
  device_id  TEXT,
  session_id TEXT,
  event_type TEXT NOT NULL,
  result     TEXT NOT NULL,
  detail     TEXT,
  created_at INTEGER NOT NULL
);

CREATE INDEX audit_events_created_idx ON audit_events (created_at);
CREATE INDEX audit_events_device_idx ON audit_events (device_id, created_at);
CREATE INDEX audit_events_user_idx ON audit_events (user_id, created_at);
