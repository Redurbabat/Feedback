/**
 * Repository layer.
 *
 * Every data access of the application goes through these interfaces. The MVP
 * ships a SQLite implementation; a PostgreSQL implementation can be added
 * without touching services, routes or tests of the domain logic.
 *
 * All timestamps are epoch milliseconds. Secrets appear only as SHA-256 hex.
 */

export type PairingStatus = 'pending' | 'approved' | 'rejected' | 'expired' | 'consumed';

export interface UserRecord {
  readonly id: string;
  readonly email: string;
  readonly emailNormalized: string;
  readonly passwordHash: string;
  readonly displayName: string | null;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly disabledAt: number | null;
}

export interface CreateUserInput {
  readonly email: string;
  readonly passwordHash: string;
  readonly displayName?: string | undefined;
}

export interface UserRepository {
  findById(id: string): Promise<UserRecord | undefined>;
  findByEmail(email: string): Promise<UserRecord | undefined>;
  create(input: CreateUserInput): Promise<UserRecord>;
  updatePasswordHash(id: string, passwordHash: string): Promise<void>;
  count(): Promise<number>;
}

export interface AuthSessionRecord {
  readonly id: string;
  readonly userId: string;
  readonly tokenHash: string;
  readonly createdAt: number;
  readonly expiresAt: number;
  readonly lastSeenAt: number;
  readonly revokedAt: number | null;
  readonly rotatedFrom: string | null;
}

export interface CreateAuthSessionInput {
  readonly userId: string;
  readonly tokenHash: string;
  readonly expiresAt: number;
  readonly rotatedFrom?: string | undefined;
}

export interface AuthSessionRepository {
  create(input: CreateAuthSessionInput): Promise<AuthSessionRecord>;
  findByTokenHash(tokenHash: string): Promise<AuthSessionRecord | undefined>;
  touch(id: string, at: number): Promise<void>;
  revoke(id: string, at: number): Promise<void>;
  revokeAllForUser(userId: string, at: number): Promise<void>;
  deleteExpired(before: number): Promise<number>;
}

export interface DeviceRecord {
  readonly id: string;
  readonly ownerId: string;
  readonly deviceId: string;
  readonly publicKey: string;
  readonly fingerprint: string;
  readonly name: string;
  readonly platform: string;
  readonly osVersion: string;
  readonly sdkInt: number | null;
  readonly appVersion: string;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly lastSeenAt: number | null;
  readonly revokedAt: number | null;
}

export interface UpsertDeviceInput {
  readonly ownerId: string;
  readonly deviceId: string;
  readonly publicKey: string;
  readonly fingerprint: string;
  readonly name: string;
  readonly platform: string;
  readonly osVersion: string;
  readonly sdkInt: number | null;
  readonly appVersion: string;
}

export interface DeviceRepository {
  findById(id: string): Promise<DeviceRecord | undefined>;
  findByDeviceId(deviceId: string): Promise<DeviceRecord | undefined>;
  listByOwner(ownerId: string): Promise<readonly DeviceRecord[]>;
  /**
   * Registers a device or refreshes an existing registration. Re-pairing clears
   * `revokedAt` (PROTOCOL.md section 5.4). The caller is responsible for
   * rejecting a changed public key beforehand.
   */
  upsert(input: UpsertDeviceInput): Promise<DeviceRecord>;
  revoke(id: string, at: number): Promise<void>;
  touchLastSeen(id: string, at: number): Promise<void>;
}

export interface DeviceTokenRecord {
  readonly id: string;
  readonly deviceId: string;
  readonly tokenHash: string;
  readonly createdAt: number;
  readonly expiresAt: number | null;
  readonly lastUsedAt: number | null;
  readonly revokedAt: number | null;
}

export interface DeviceTokenRepository {
  create(input: {
    deviceId: string;
    tokenHash: string;
    expiresAt?: number | undefined;
  }): Promise<DeviceTokenRecord>;
  findActiveByTokenHash(tokenHash: string, now: number): Promise<DeviceTokenRecord | undefined>;
  revokeAllForDevice(deviceId: string, at: number): Promise<number>;
  touch(id: string, at: number): Promise<void>;
}

export interface PairingSessionRecord {
  readonly id: string;
  readonly ticketHash: string;
  readonly displayCodeHash: string;
  readonly deviceId: string;
  readonly publicKey: string;
  readonly fingerprint: string;
  readonly metadata: string;
  readonly deviceSecretHash: string;
  readonly status: PairingStatus;
  readonly lookupAttempts: number;
  readonly createdAt: number;
  readonly expiresAt: number;
  readonly approvedAt: number | null;
  readonly rejectedAt: number | null;
  readonly consumedAt: number | null;
  readonly ownerId: string | null;
}

export interface CreatePairingSessionInput {
  readonly ticketHash: string;
  readonly displayCodeHash: string;
  readonly deviceId: string;
  readonly publicKey: string;
  readonly fingerprint: string;
  readonly metadata: string;
  readonly deviceSecretHash: string;
  readonly expiresAt: number;
}

export interface PairingSessionRepository {
  create(input: CreatePairingSessionInput): Promise<PairingSessionRecord>;
  findById(id: string): Promise<PairingSessionRecord | undefined>;
  findByTicketHash(ticketHash: string): Promise<PairingSessionRecord | undefined>;
  /**
   * Resolves a pending, unexpired session by display code hash. Returns
   * undefined when the code is ambiguous, so a collision can never authorise
   * the wrong device.
   */
  findPendingByDisplayCodeHash(
    displayCodeHash: string,
    now: number,
  ): Promise<PairingSessionRecord | undefined>;
  countPendingByDisplayCodeHash(displayCodeHash: string, now: number): Promise<number>;
  /** Increments the per-session attempt counter and returns the new value. */
  incrementLookupAttempts(id: string): Promise<number>;
  approve(id: string, ownerId: string, at: number): Promise<void>;
  reject(id: string, ownerId: string, at: number): Promise<void>;
  markExpired(id: string): Promise<void>;
  markConsumed(id: string, at: number): Promise<void>;
  expireStale(now: number): Promise<number>;
}

export interface PairingNonceRepository {
  /**
   * Stores a nonce for a device. Returns false when the nonce was already used
   * inside the retention window (replay).
   */
  tryInsert(deviceId: string, nonceHash: string, expiresAt: number): Promise<boolean>;
  deleteExpired(before: number): Promise<number>;
}

export interface DeviceCapabilityRecord {
  readonly id: string;
  readonly deviceId: string;
  readonly capability: string;
  readonly granted: boolean;
  readonly grantedBy: string | null;
  readonly createdAt: number;
  readonly updatedAt: number;
}

export interface DeviceCapabilityRepository {
  listForDevice(deviceId: string): Promise<readonly DeviceCapabilityRecord[]>;
  setGranted(input: {
    deviceId: string;
    capability: string;
    granted: boolean;
    grantedBy: string | null;
  }): Promise<DeviceCapabilityRecord>;
  clearForDevice(deviceId: string): Promise<number>;
}

export interface RemoteSessionRecord {
  readonly id: string;
  readonly ownerId: string;
  readonly deviceId: string;
  readonly createdAt: number;
  readonly expiresAt: number;
  readonly revokedAt: number | null;
  readonly requestedCapabilities: readonly string[];
  readonly approvedCapabilities: readonly string[];
}

export interface RemoteSessionRepository {
  create(input: {
    ownerId: string;
    deviceId: string;
    expiresAt: number;
    requestedCapabilities: readonly string[];
    approvedCapabilities: readonly string[];
  }): Promise<RemoteSessionRecord>;
  findById(id: string): Promise<RemoteSessionRecord | undefined>;
  revoke(id: string, at: number): Promise<void>;
  revokeAllForDevice(deviceId: string, at: number): Promise<number>;
}

export type AuditResult = 'success' | 'failure' | 'denied';

export interface AuditEventRecord {
  readonly id: string;
  readonly userId: string | null;
  readonly deviceId: string | null;
  readonly sessionId: string | null;
  readonly eventType: string;
  readonly result: AuditResult;
  readonly detail: string | null;
  readonly createdAt: number;
}

export interface RecordAuditInput {
  readonly eventType: string;
  readonly result: AuditResult;
  readonly userId?: string | null | undefined;
  readonly deviceId?: string | null | undefined;
  readonly sessionId?: string | null | undefined;
  readonly detail?: Record<string, string | number | boolean> | undefined;
}

export interface AuditRepository {
  record(input: RecordAuditInput): Promise<AuditEventRecord>;
  listForDevice(deviceId: string, limit: number): Promise<readonly AuditEventRecord[]>;
  listRecent(limit: number): Promise<readonly AuditEventRecord[]>;
}

export interface Repositories {
  readonly users: UserRepository;
  readonly authSessions: AuthSessionRepository;
  readonly devices: DeviceRepository;
  readonly deviceTokens: DeviceTokenRepository;
  readonly pairingSessions: PairingSessionRepository;
  readonly pairingNonces: PairingNonceRepository;
  readonly deviceCapabilities: DeviceCapabilityRepository;
  readonly remoteSessions: RemoteSessionRepository;
  readonly audit: AuditRepository;
}
