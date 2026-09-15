import { chmodSync, copyFileSync, existsSync, mkdirSync, statSync } from 'node:fs';
import path from 'node:path';

import Database from 'better-sqlite3';

import { loadConfig, loadEnvFile } from '../config.js';
import { openDatabase } from '../db/client.js';

/**
 * `npm run backup -- --out <verzeichnis>`
 *
 * Two files, because since the server has an identity of its own the database alone is not a
 * backup. `identity.pem` is what every paired device recognises this server by; a restore without
 * it produces a server that each of them correctly treats as a stranger, and every pairing is
 * gone. So the two are written together, named together, and the one case where that matters most
 * - devices in the database, no key on disk - is an error and not a warning.
 *
 * The database is copied through SQLite's online backup API rather than by copying the file. A
 * plain copy of a database with a live writer can be torn: the server is meant to keep running
 * while this happens.
 */

interface Arguments {
  readonly out: string;
}

function parseArguments(argv: readonly string[]): Arguments {
  let out: string | undefined;
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index] === '--out') {
      out = argv[index + 1];
      index += 1;
    }
  }
  if (out === undefined || out.length === 0) {
    throw new Error('Aufruf: npm run backup -- --out <verzeichnis>');
  }
  return { out };
}

/** `2026-09-15T16-40-00` - sortable, and safe in a file name on every platform. */
function stamp(at: Date): string {
  return at.toISOString().replace(/\.\d{3}Z$/, '').replace(/:/g, '-');
}

async function main(): Promise<void> {
  loadEnvFile();
  const config = loadConfig();
  const { out } = parseArguments(process.argv.slice(2));

  if (config.databaseFile === ':memory:') {
    throw new Error('Eine In-Memory-Datenbank laesst sich nicht sichern.');
  }
  if (!existsSync(config.databaseFile)) {
    throw new Error(`Datenbank nicht gefunden: ${config.databaseFile}`);
  }

  mkdirSync(out, { recursive: true });
  const prefix = path.join(out, `feedback-${stamp(new Date())}`);
  const databaseTarget = `${prefix}.db`;
  const keyTarget = `${prefix}.identity.pem`;
  for (const target of [databaseTarget, keyTarget]) {
    if (existsSync(target)) {
      throw new Error(`Zieldatei existiert bereits: ${target}`);
    }
  }

  const source = openDatabase(config.databaseFile);
  const devices = (
    source.sqlite.prepare('SELECT COUNT(*) AS count FROM devices').get() as { count: number }
  ).count;

  await source.sqlite.backup(databaseTarget);
  source.sqlite.close();
  chmodSync(databaseTarget, 0o600);

  // Opened again and actually read. A backup nobody has looked at is a guess, and this is the
  // file someone reaches for on the worst day.
  const verify = new Database(databaseTarget, { readonly: true });
  const integrity = (
    verify.prepare('PRAGMA integrity_check').get() as { integrity_check: string }
  ).integrity_check;
  const restoredDevices = (
    verify.prepare('SELECT COUNT(*) AS count FROM devices').get() as { count: number }
  ).count;
  verify.close();
  if (integrity !== 'ok') {
    throw new Error(`Die gesicherte Datenbank ist beschaedigt: ${integrity}`);
  }
  if (restoredDevices !== devices) {
    throw new Error(
      `Die Sicherung enthaelt ${restoredDevices} Geraete, die Quelle ${devices}.`,
    );
  }

  const hasKey = existsSync(config.serverKeyFile);
  if (hasKey) {
    copyFileSync(config.serverKeyFile, keyTarget);
    chmodSync(keyTarget, 0o600);
  }

  console.log(`Datenbank gesichert:   ${databaseTarget} (${devices} Geraete, integrity_check ok)`);
  if (hasKey) {
    console.log(`Identitaet gesichert:  ${keyTarget} (${statSync(keyTarget).size} Bytes)`);
    console.log('');
    console.log('Zum Wiederherstellen BEIDE Dateien zurueckspielen:');
    console.log(`  cp ${databaseTarget} ${config.databaseFile}`);
    console.log(`  cp ${keyTarget} ${config.serverKeyFile}`);
    console.log('Nur die Datenbank zurueckzuspielen ergibt einen Server, den jedes gekoppelte');
    console.log('Geraet fuer einen fremden haelt - zu Recht.');
    return;
  }

  console.error('');
  console.error(`Kein Identitaetsschluessel unter ${config.serverKeyFile}.`);
  if (devices === 0) {
    console.error('Es ist noch kein Geraet gekoppelt, also geht dabei nichts verloren. Der');
    console.error('Schluessel entsteht beim ersten Start; danach gehoert er in jede Sicherung.');
    return;
  }
  // Devices in the database and no key on disk: restoring this backup would look like a
  // substituted server to every one of them. Failing is the only answer that gets read.
  console.error(`Es sind aber ${devices} Geraete gekoppelt. Diese Sicherung allein kann sie NICHT`);
  console.error('wiederherstellen: ohne den Schluessel lehnt jedes von ihnen den Server ab.');
  console.error('Siehe docs/deployment/BETRIEB.md 3.7.');
  process.exitCode = 1;
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
