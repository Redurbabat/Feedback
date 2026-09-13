import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import type { Database as SqliteDatabase } from 'better-sqlite3';

/**
 * Minimal forward-only migrator over the hand written SQL files in
 * `server/drizzle/`. Runs on every server start and via `npm run migrate`.
 */

const MIGRATIONS_TABLE = '_feedback_migrations';

export function migrationsDirectory(): string {
  // src/db/migrate.ts -> server/drizzle, dist/db/migrate.js -> server/drizzle
  return resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', 'drizzle');
}

export interface MigrationResult {
  readonly applied: readonly string[];
  readonly skipped: readonly string[];
}

export function runMigrations(
  sqlite: SqliteDatabase,
  directory: string = migrationsDirectory(),
): MigrationResult {
  sqlite.exec(
    `CREATE TABLE IF NOT EXISTS ${MIGRATIONS_TABLE} (
       name       TEXT PRIMARY KEY NOT NULL,
       applied_at INTEGER NOT NULL
     )`,
  );

  const files = readdirSync(directory)
    .filter((name) => name.endsWith('.sql'))
    .sort((a, b) => a.localeCompare(b, 'en'));

  const alreadyApplied = new Set(
    sqlite
      .prepare(`SELECT name FROM ${MIGRATIONS_TABLE}`)
      .all()
      .map((row) => (row as { name: string }).name),
  );

  const applied: string[] = [];
  const skipped: string[] = [];
  const insert = sqlite.prepare(
    `INSERT INTO ${MIGRATIONS_TABLE} (name, applied_at) VALUES (?, ?)`,
  );

  for (const file of files) {
    if (alreadyApplied.has(file)) {
      skipped.push(file);
      continue;
    }
    const sql = readFileSync(join(directory, file), 'utf8');
    const apply = sqlite.transaction(() => {
      sqlite.exec(sql);
      insert.run(file, Date.now());
    });
    apply();
    applied.push(file);
  }

  return { applied, skipped };
}
