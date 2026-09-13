import { randomUUID } from 'node:crypto';

import { eq, sql } from 'drizzle-orm';

import type { Db } from '../client.js';
import { users } from '../schema.js';
import type { CreateUserInput, UserRecord, UserRepository } from './types.js';

export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

type Row = typeof users.$inferSelect;

function toRecord(row: Row): UserRecord {
  return {
    id: row.id,
    email: row.email,
    emailNormalized: row.emailNormalized,
    passwordHash: row.passwordHash,
    displayName: row.displayName,
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
    disabledAt: row.disabledAt,
  };
}

export class SqliteUserRepository implements UserRepository {
  constructor(private readonly db: Db) {}

  async findById(id: string): Promise<UserRecord | undefined> {
    const row = this.db.select().from(users).where(eq(users.id, id)).get();
    return row === undefined ? undefined : toRecord(row);
  }

  async findByEmail(email: string): Promise<UserRecord | undefined> {
    const row = this.db
      .select()
      .from(users)
      .where(eq(users.emailNormalized, normalizeEmail(email)))
      .get();
    return row === undefined ? undefined : toRecord(row);
  }

  async create(input: CreateUserInput): Promise<UserRecord> {
    const now = Date.now();
    const row = this.db
      .insert(users)
      .values({
        id: randomUUID(),
        email: input.email.trim(),
        emailNormalized: normalizeEmail(input.email),
        passwordHash: input.passwordHash,
        displayName: input.displayName ?? null,
        createdAt: now,
        updatedAt: now,
        disabledAt: null,
      })
      .returning()
      .get();
    return toRecord(row);
  }

  async updatePasswordHash(id: string, passwordHash: string): Promise<void> {
    this.db
      .update(users)
      .set({ passwordHash, updatedAt: Date.now() })
      .where(eq(users.id, id))
      .run();
  }

  async count(): Promise<number> {
    const row = this.db.select({ value: sql<number>`count(*)` }).from(users).get();
    return row?.value ?? 0;
  }
}
