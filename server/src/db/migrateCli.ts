import { loadConfig } from '../config.js';
import { openDatabase } from './client.js';
import { runMigrations } from './migrate.js';

/** `npm run migrate` - applies pending SQL migrations and exits. */
function main(): void {
  const config = loadConfig();
  const handle = openDatabase(config.databaseFile);
  try {
    const result = runMigrations(handle.sqlite);
    if (result.applied.length === 0) {
      console.log(`Keine ausstehenden Migrationen (${result.skipped.length} bereits angewendet).`);
    } else {
      console.log(`Angewendete Migrationen: ${result.applied.join(', ')}`);
    }
  } finally {
    handle.close();
  }
}

try {
  main();
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
}
