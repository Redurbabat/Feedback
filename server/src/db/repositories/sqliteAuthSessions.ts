import { randomUUID } from 'node:crypto';

import { and, eq, isNull, lt } from 'drizzle-orm';

import type { Db } from '../client.js';
import { authSessions } from '../schema.js';
import type {
  AuthSessionRecord,
  AuthSessionRepository,
  CreateAuthSessionInput,
} from './types.js';

type Row = typeof authSessions.$inferSelect;

function toRecord(row: Row): AuthSessionRecord {
  return {
    id: row.id,
    userId: row.userId,
    tokenHash: row.tokenHash,
    createdAt: row.createdAt,
    expiresAt: row.expiresAt,
    lastSeenAt: row.lastSeenAt,
    revokedAt: row.revokedAt,
    rotatedFrom: row.rotatedFrom,
  };
}

export class SqliteAuthSessionRepository implements AuthSessionRepository {
  constructor(private readonly db: Db) {}

  async create(input: CreateAuthSessionInput): Promise<AuthSessionRecord> {
    const now = Date.now();
    const row = this.db
      .insert(authSessions)
      .values({
        id: randomUUID(),
        userId: input.userId,
        tokenHash: input.tokenHash,
        createdAt: now,
        expiresAt: input.expiresAt,
        lastSeenAt: now,
        revokedAt: null,
        rotatedFrom: input.rotatedFrom ?? null,
      })
      .returning()
      .get();
    return toRecord(row);
  }

  async findByTokenHash(tokenHash: string): Promise<AuthSessionRecord | undefined> {
    const row = this.db
      .select()
      .from(authSessions)
      .where(eq(authSessions.tokenHash, tokenHash))
      .get();
    return row === undefined ? undefined : toRecord(row);
  }

  async touch(id: string, at: number): Promise<void> {
    this.db.update(authSessions).set({ lastSeenAt: at }).where(eq(authSessions.id, id)).run();
  }

  async revoke(id: string, at: number): Promise<void> {
    this.db
      .update(authSessions)
      .set({ revokedAt: at })
      .where(and(eq(authSessions.id, id), isNull(authSessions.revokedAt)))
      .run();
  }

  async revokeAllForUser(userId: string, at: number): Promise<void> {
    this.db
      .update(authSessions)
      .set({ revokedAt: at })
      .where(and(eq(authSessions.userId, userId), isNull(authSessions.revokedAt)))
      .run();
  }

  async deleteExpired(before: number): Promise<number> {
    const result = this.db.delete(authSessions).where(lt(authSessions.expiresAt, before)).run();
    return result.changes;
  }
}
