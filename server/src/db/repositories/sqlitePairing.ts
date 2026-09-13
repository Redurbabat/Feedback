import { randomUUID } from 'node:crypto';

import { and, eq, gt, lt, sql } from 'drizzle-orm';

import type { Db } from '../client.js';
import { pairingNonces, pairingSessions } from '../schema.js';
import type {
  CreatePairingSessionInput,
  PairingNonceRepository,
  PairingSessionRecord,
  PairingSessionRepository,
  PairingStatus,
} from './types.js';

type Row = typeof pairingSessions.$inferSelect;

const KNOWN_STATUS: readonly PairingStatus[] = [
  'pending',
  'approved',
  'rejected',
  'expired',
  'consumed',
];

function toStatus(value: string): PairingStatus {
  // A row with an unknown status is treated as expired instead of being
  // trusted; deny-by-default also applies to stored data.
  return (KNOWN_STATUS as readonly string[]).includes(value)
    ? (value as PairingStatus)
    : 'expired';
}

function toRecord(row: Row): PairingSessionRecord {
  return {
    id: row.id,
    ticketHash: row.ticketHash,
    displayCodeHash: row.displayCodeHash,
    deviceId: row.deviceId,
    publicKey: row.publicKey,
    fingerprint: row.fingerprint,
    metadata: row.metadata,
    deviceSecretHash: row.deviceSecretHash,
    status: toStatus(row.status),
    lookupAttempts: row.lookupAttempts,
    createdAt: row.createdAt,
    expiresAt: row.expiresAt,
    approvedAt: row.approvedAt,
    rejectedAt: row.rejectedAt,
    consumedAt: row.consumedAt,
    ownerId: row.ownerId,
  };
}

export class SqlitePairingSessionRepository implements PairingSessionRepository {
  constructor(private readonly db: Db) {}

  async create(input: CreatePairingSessionInput): Promise<PairingSessionRecord> {
    const row = this.db
      .insert(pairingSessions)
      .values({
        id: randomUUID(),
        ticketHash: input.ticketHash,
        displayCodeHash: input.displayCodeHash,
        deviceId: input.deviceId,
        publicKey: input.publicKey,
        fingerprint: input.fingerprint,
        metadata: input.metadata,
        deviceSecretHash: input.deviceSecretHash,
        status: 'pending',
        lookupAttempts: 0,
        createdAt: Date.now(),
        expiresAt: input.expiresAt,
        approvedAt: null,
        rejectedAt: null,
        consumedAt: null,
        ownerId: null,
      })
      .returning()
      .get();
    return toRecord(row);
  }

  async findById(id: string): Promise<PairingSessionRecord | undefined> {
    const row = this.db.select().from(pairingSessions).where(eq(pairingSessions.id, id)).get();
    return row === undefined ? undefined : toRecord(row);
  }

  async findByTicketHash(ticketHash: string): Promise<PairingSessionRecord | undefined> {
    const row = this.db
      .select()
      .from(pairingSessions)
      .where(eq(pairingSessions.ticketHash, ticketHash))
      .get();
    return row === undefined ? undefined : toRecord(row);
  }

  async findPendingByDisplayCodeHash(
    displayCodeHash: string,
    now: number,
  ): Promise<PairingSessionRecord | undefined> {
    const rows = this.db
      .select()
      .from(pairingSessions)
      .where(
        and(
          eq(pairingSessions.displayCodeHash, displayCodeHash),
          eq(pairingSessions.status, 'pending'),
          gt(pairingSessions.expiresAt, now),
        ),
      )
      .limit(2)
      .all();
    // An ambiguous code must never resolve to a device.
    return rows.length === 1 && rows[0] !== undefined ? toRecord(rows[0]) : undefined;
  }

  async countPendingByDisplayCodeHash(displayCodeHash: string, now: number): Promise<number> {
    const row = this.db
      .select({ value: sql<number>`count(*)` })
      .from(pairingSessions)
      .where(
        and(
          eq(pairingSessions.displayCodeHash, displayCodeHash),
          eq(pairingSessions.status, 'pending'),
          gt(pairingSessions.expiresAt, now),
        ),
      )
      .get();
    return row?.value ?? 0;
  }

  async incrementLookupAttempts(id: string): Promise<number> {
    const row = this.db
      .update(pairingSessions)
      .set({ lookupAttempts: sql`${pairingSessions.lookupAttempts} + 1` })
      .where(eq(pairingSessions.id, id))
      .returning({ lookupAttempts: pairingSessions.lookupAttempts })
      .get();
    return row?.lookupAttempts ?? 0;
  }

  async approve(id: string, ownerId: string, at: number): Promise<void> {
    this.db
      .update(pairingSessions)
      .set({ status: 'approved', approvedAt: at, ownerId })
      .where(and(eq(pairingSessions.id, id), eq(pairingSessions.status, 'pending')))
      .run();
  }

  async reject(id: string, ownerId: string, at: number): Promise<void> {
    this.db
      .update(pairingSessions)
      .set({ status: 'rejected', rejectedAt: at, ownerId })
      .where(and(eq(pairingSessions.id, id), eq(pairingSessions.status, 'pending')))
      .run();
  }

  async markExpired(id: string): Promise<void> {
    this.db
      .update(pairingSessions)
      .set({ status: 'expired' })
      .where(eq(pairingSessions.id, id))
      .run();
  }

  async markConsumed(id: string, at: number): Promise<void> {
    this.db
      .update(pairingSessions)
      .set({ status: 'consumed', consumedAt: at })
      .where(and(eq(pairingSessions.id, id), eq(pairingSessions.status, 'approved')))
      .run();
  }

  async expireStale(now: number): Promise<number> {
    const result = this.db
      .update(pairingSessions)
      .set({ status: 'expired' })
      .where(and(eq(pairingSessions.status, 'pending'), lt(pairingSessions.expiresAt, now)))
      .run();
    return result.changes;
  }
}

export class SqlitePairingNonceRepository implements PairingNonceRepository {
  constructor(private readonly db: Db) {}

  async tryInsert(deviceId: string, nonceHash: string, expiresAt: number): Promise<boolean> {
    const rows = this.db
      .insert(pairingNonces)
      .values({
        id: randomUUID(),
        deviceId,
        nonceHash,
        createdAt: Date.now(),
        expiresAt,
      })
      .onConflictDoNothing({ target: [pairingNonces.deviceId, pairingNonces.nonceHash] })
      .returning({ id: pairingNonces.id })
      .all();
    return rows.length === 1;
  }

  async deleteExpired(before: number): Promise<number> {
    const result = this.db.delete(pairingNonces).where(lt(pairingNonces.expiresAt, before)).run();
    return result.changes;
  }
}
