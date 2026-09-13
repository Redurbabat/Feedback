import { randomUUID } from 'node:crypto';

import { and, asc, eq, gt, isNull, or } from 'drizzle-orm';

import type { Db } from '../client.js';
import { deviceTokens, devices } from '../schema.js';
import type {
  DeviceRecord,
  DeviceRepository,
  DeviceTokenRecord,
  DeviceTokenRepository,
  UpsertDeviceInput,
} from './types.js';

type DeviceRow = typeof devices.$inferSelect;
type TokenRow = typeof deviceTokens.$inferSelect;

function toDevice(row: DeviceRow): DeviceRecord {
  return {
    id: row.id,
    ownerId: row.ownerId,
    deviceId: row.deviceId,
    publicKey: row.publicKey,
    fingerprint: row.fingerprint,
    name: row.name,
    platform: row.platform,
    osVersion: row.osVersion,
    sdkInt: row.sdkInt,
    appVersion: row.appVersion,
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
    lastSeenAt: row.lastSeenAt,
    revokedAt: row.revokedAt,
  };
}

function toToken(row: TokenRow): DeviceTokenRecord {
  return {
    id: row.id,
    deviceId: row.deviceId,
    tokenHash: row.tokenHash,
    createdAt: row.createdAt,
    expiresAt: row.expiresAt,
    lastUsedAt: row.lastUsedAt,
    revokedAt: row.revokedAt,
  };
}

export class SqliteDeviceRepository implements DeviceRepository {
  constructor(private readonly db: Db) {}

  async findById(id: string): Promise<DeviceRecord | undefined> {
    const row = this.db.select().from(devices).where(eq(devices.id, id)).get();
    return row === undefined ? undefined : toDevice(row);
  }

  async findByDeviceId(deviceId: string): Promise<DeviceRecord | undefined> {
    const row = this.db.select().from(devices).where(eq(devices.deviceId, deviceId)).get();
    return row === undefined ? undefined : toDevice(row);
  }

  async listByOwner(ownerId: string): Promise<readonly DeviceRecord[]> {
    return this.db
      .select()
      .from(devices)
      .where(eq(devices.ownerId, ownerId))
      .orderBy(asc(devices.createdAt))
      .all()
      .map(toDevice);
  }

  async upsert(input: UpsertDeviceInput): Promise<DeviceRecord> {
    const now = Date.now();
    const row = this.db
      .insert(devices)
      .values({
        id: randomUUID(),
        ownerId: input.ownerId,
        deviceId: input.deviceId,
        publicKey: input.publicKey,
        fingerprint: input.fingerprint,
        name: input.name,
        platform: input.platform,
        osVersion: input.osVersion,
        sdkInt: input.sdkInt,
        appVersion: input.appVersion,
        createdAt: now,
        updatedAt: now,
        lastSeenAt: null,
        revokedAt: null,
      })
      .onConflictDoUpdate({
        target: devices.deviceId,
        set: {
          ownerId: input.ownerId,
          name: input.name,
          platform: input.platform,
          osVersion: input.osVersion,
          sdkInt: input.sdkInt,
          appVersion: input.appVersion,
          updatedAt: now,
          // Re-pairing lifts a previous revocation (PROTOCOL.md 5.4).
          revokedAt: null,
        },
      })
      .returning()
      .get();
    return toDevice(row);
  }

  async revoke(id: string, at: number): Promise<void> {
    this.db
      .update(devices)
      .set({ revokedAt: at, updatedAt: at })
      .where(and(eq(devices.id, id), isNull(devices.revokedAt)))
      .run();
  }

  async touchLastSeen(id: string, at: number): Promise<void> {
    this.db.update(devices).set({ lastSeenAt: at }).where(eq(devices.id, id)).run();
  }
}

export class SqliteDeviceTokenRepository implements DeviceTokenRepository {
  constructor(private readonly db: Db) {}

  async create(input: {
    deviceId: string;
    tokenHash: string;
    expiresAt?: number | undefined;
  }): Promise<DeviceTokenRecord> {
    const row = this.db
      .insert(deviceTokens)
      .values({
        id: randomUUID(),
        deviceId: input.deviceId,
        tokenHash: input.tokenHash,
        createdAt: Date.now(),
        expiresAt: input.expiresAt ?? null,
        lastUsedAt: null,
        revokedAt: null,
      })
      .returning()
      .get();
    return toToken(row);
  }

  async findActiveByTokenHash(
    tokenHash: string,
    now: number,
  ): Promise<DeviceTokenRecord | undefined> {
    const row = this.db
      .select()
      .from(deviceTokens)
      .where(
        and(
          eq(deviceTokens.tokenHash, tokenHash),
          isNull(deviceTokens.revokedAt),
          or(isNull(deviceTokens.expiresAt), gt(deviceTokens.expiresAt, now)),
        ),
      )
      .get();
    return row === undefined ? undefined : toToken(row);
  }

  async revokeAllForDevice(deviceId: string, at: number): Promise<number> {
    const result = this.db
      .update(deviceTokens)
      .set({ revokedAt: at })
      .where(and(eq(deviceTokens.deviceId, deviceId), isNull(deviceTokens.revokedAt)))
      .run();
    return result.changes;
  }

  async touch(id: string, at: number): Promise<void> {
    this.db.update(deviceTokens).set({ lastUsedAt: at }).where(eq(deviceTokens.id, id)).run();
  }
}
