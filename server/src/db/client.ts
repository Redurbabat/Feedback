import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

import Database from 'better-sqlite3';
import type { Database as SqliteDatabase } from 'better-sqlite3';
import { drizzle } from 'drizzle-orm/better-sqlite3';
import type { BetterSQLite3Database } from 'drizzle-orm/better-sqlite3';

import * as schema from './schema.js';

export type Db = BetterSQLite3Database<typeof schema>;

export interface DatabaseHandle {
  readonly db: Db;
  readonly sqlite: SqliteDatabase;
  close(): void;
}

const IN_MEMORY = ':memory:';

/**
 * Opens the SQLite database used by the MVP.
 *
 * PostgreSQL is the production target; the swap stays local because all data
 * access goes through the repository layer in `src/db/repositories`.
 */
export function openDatabase(databaseFile: string): DatabaseHandle {
  const isMemory = databaseFile === IN_MEMORY || databaseFile.startsWith('file::memory:');
  let location = databaseFile;

  if (!isMemory) {
    location = resolve(databaseFile);
    mkdirSync(dirname(location), { recursive: true });
  }

  const sqlite = new Database(location);
  sqlite.pragma('foreign_keys = ON');
  sqlite.pragma('busy_timeout = 5000');
  if (!isMemory) {
    sqlite.pragma('journal_mode = WAL');
    sqlite.pragma('synchronous = NORMAL');
  }

  const db = drizzle(sqlite, { schema });

  return {
    db,
    sqlite,
    close(): void {
      sqlite.close();
    },
  };
}
