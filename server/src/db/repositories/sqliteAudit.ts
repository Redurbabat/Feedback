import { randomUUID } from 'node:crypto';

import { desc, eq } from 'drizzle-orm';

import type { Db } from '../client.js';
import { auditEvents } from '../schema.js';
import type {
  AuditEventRecord,
  AuditRepository,
  AuditResult,
  RecordAuditInput,
} from './types.js';

type Row = typeof auditEvents.$inferSelect;

const KNOWN_RESULTS: readonly AuditResult[] = ['success', 'failure', 'denied'];

function toResult(value: string): AuditResult {
  return (KNOWN_RESULTS as readonly string[]).includes(value)
    ? (value as AuditResult)
    : 'failure';
}

function toRecord(row: Row): AuditEventRecord {
  return {
    id: row.id,
    userId: row.userId,
    deviceId: row.deviceId,
    sessionId: row.sessionId,
    eventType: row.eventType,
    result: toResult(row.result),
    detail: row.detail,
    createdAt: row.createdAt,
  };
}

export class SqliteAuditRepository implements AuditRepository {
  constructor(private readonly db: Db) {}

  async record(input: RecordAuditInput): Promise<AuditEventRecord> {
    const row = this.db
      .insert(auditEvents)
      .values({
        id: randomUUID(),
        userId: input.userId ?? null,
        deviceId: input.deviceId ?? null,
        sessionId: input.sessionId ?? null,
        eventType: input.eventType,
        result: input.result,
        detail: input.detail === undefined ? null : JSON.stringify(input.detail),
        createdAt: Date.now(),
      })
      .returning()
      .get();
    return toRecord(row);
  }

  async listForDevice(deviceId: string, limit: number): Promise<readonly AuditEventRecord[]> {
    return this.db
      .select()
      .from(auditEvents)
      .where(eq(auditEvents.deviceId, deviceId))
      .orderBy(desc(auditEvents.createdAt))
      .limit(limit)
      .all()
      .map(toRecord);
  }

  async listRecent(limit: number): Promise<readonly AuditEventRecord[]> {
    return this.db
      .select()
      .from(auditEvents)
      .orderBy(desc(auditEvents.createdAt))
      .limit(limit)
      .all()
      .map(toRecord);
  }
}
