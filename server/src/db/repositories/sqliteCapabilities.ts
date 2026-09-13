import { randomUUID } from 'node:crypto';

import { and, asc, eq, isNull } from 'drizzle-orm';

import type { Db } from '../client.js';
import { deviceCapabilities, remoteSessions } from '../schema.js';
import type {
  DeviceCapabilityRecord,
  DeviceCapabilityRepository,
  RemoteSessionRecord,
  RemoteSessionRepository,
} from './types.js';

type CapabilityRow = typeof deviceCapabilities.$inferSelect;
type RemoteSessionRow = typeof remoteSessions.$inferSelect;

function toCapability(row: CapabilityRow): DeviceCapabilityRecord {
  return {
    id: row.id,
    deviceId: row.deviceId,
    capability: row.capability,
    granted: row.granted === 1,
    grantedBy: row.grantedBy,
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
  };
}

/**
 * Capability lists are stored as a JSON array of strings. A row that cannot be
 * parsed yields an empty list, which is the deny-by-default outcome.
 */
function parseCapabilityList(value: string): readonly string[] {
  try {
    const parsed: unknown = JSON.parse(value);
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed.filter((entry): entry is string => typeof entry === 'string');
  } catch {
    return [];
  }
}

function toRemoteSession(row: RemoteSessionRow): RemoteSessionRecord {
  return {
    id: row.id,
    ownerId: row.ownerId,
    deviceId: row.deviceId,
    createdAt: row.createdAt,
    expiresAt: row.expiresAt,
    revokedAt: row.revokedAt,
    requestedCapabilities: parseCapabilityList(row.requestedCapabilities),
    approvedCapabilities: parseCapabilityList(row.approvedCapabilities),
  };
}

export class SqliteDeviceCapabilityRepository implements DeviceCapabilityRepository {
  constructor(private readonly db: Db) {}

  async listForDevice(deviceId: string): Promise<readonly DeviceCapabilityRecord[]> {
    return this.db
      .select()
      .from(deviceCapabilities)
      .where(eq(deviceCapabilities.deviceId, deviceId))
      .orderBy(asc(deviceCapabilities.capability))
      .all()
      .map(toCapability);
  }

  async setGranted(input: {
    deviceId: string;
    capability: string;
    granted: boolean;
    grantedBy: string | null;
  }): Promise<DeviceCapabilityRecord> {
    const now = Date.now();
    const row = this.db
      .insert(deviceCapabilities)
      .values({
        id: randomUUID(),
        deviceId: input.deviceId,
        capability: input.capability,
        granted: input.granted ? 1 : 0,
        grantedBy: input.grantedBy,
        createdAt: now,
        updatedAt: now,
      })
      .onConflictDoUpdate({
        target: [deviceCapabilities.deviceId, deviceCapabilities.capability],
        set: {
          granted: input.granted ? 1 : 0,
          grantedBy: input.grantedBy,
          updatedAt: now,
        },
      })
      .returning()
      .get();
    return toCapability(row);
  }

  async clearForDevice(deviceId: string): Promise<number> {
    const result = this.db
      .delete(deviceCapabilities)
      .where(eq(deviceCapabilities.deviceId, deviceId))
      .run();
    return result.changes;
  }
}

export class SqliteRemoteSessionRepository implements RemoteSessionRepository {
  constructor(private readonly db: Db) {}

  async create(input: {
    ownerId: string;
    deviceId: string;
    expiresAt: number;
    requestedCapabilities: readonly string[];
    approvedCapabilities: readonly string[];
  }): Promise<RemoteSessionRecord> {
    const row = this.db
      .insert(remoteSessions)
      .values({
        id: randomUUID(),
        ownerId: input.ownerId,
        deviceId: input.deviceId,
        createdAt: Date.now(),
        expiresAt: input.expiresAt,
        revokedAt: null,
        requestedCapabilities: JSON.stringify([...input.requestedCapabilities]),
        approvedCapabilities: JSON.stringify([...input.approvedCapabilities]),
      })
      .returning()
      .get();
    return toRemoteSession(row);
  }

  async findById(id: string): Promise<RemoteSessionRecord | undefined> {
    const row = this.db.select().from(remoteSessions).where(eq(remoteSessions.id, id)).get();
    return row === undefined ? undefined : toRemoteSession(row);
  }

  async revoke(id: string, at: number): Promise<void> {
    this.db
      .update(remoteSessions)
      .set({ revokedAt: at })
      .where(and(eq(remoteSessions.id, id), isNull(remoteSessions.revokedAt)))
      .run();
  }

  async revokeAllForDevice(deviceId: string, at: number): Promise<number> {
    const result = this.db
      .update(remoteSessions)
      .set({ revokedAt: at })
      .where(and(eq(remoteSessions.deviceId, deviceId), isNull(remoteSessions.revokedAt)))
      .run();
    return result.changes;
  }
}
