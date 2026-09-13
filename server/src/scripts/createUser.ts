import { createInterface } from 'node:readline/promises';

import { loadConfig } from '../config.js';
import { hashPassword } from '../crypto/password.js';
import { openDatabase } from '../db/client.js';
import { runMigrations } from '../db/migrate.js';
import { createSqliteRepositories } from '../db/repositories/index.js';

/**
 * `npm run user:create -- --email <address> [--name <display name>]`
 *
 * The password is taken from FEEDBACK_USER_PASSWORD or read interactively. It
 * is never written to the log, to the process title or to the database in
 * clear text.
 */

interface Arguments {
  readonly email: string;
  readonly displayName: string | undefined;
}

function parseArguments(argv: readonly string[]): Arguments {
  let email: string | undefined;
  let displayName: string | undefined;

  for (let index = 0; index < argv.length; index += 1) {
    const current = argv[index];
    if (current === '--email') {
      email = argv[index + 1];
      index += 1;
    } else if (current === '--name') {
      displayName = argv[index + 1];
      index += 1;
    } else if (current === '--password' || current === '-p') {
      throw new Error(
        'Das Passwort darf nicht als Kommandozeilenargument uebergeben werden. ' +
          'Nutze die Umgebungsvariable FEEDBACK_USER_PASSWORD oder die interaktive Eingabe.',
      );
    }
  }

  if (email === undefined || email.trim().length === 0) {
    throw new Error('Aufruf: npm run user:create -- --email <adresse> [--name <anzeigename>]');
  }
  return { email: email.trim(), displayName };
}

async function readPassword(): Promise<string> {
  const fromEnv = process.env['FEEDBACK_USER_PASSWORD'];
  if (fromEnv !== undefined && fromEnv.length > 0) {
    return fromEnv;
  }
  if (!process.stdin.isTTY) {
    throw new Error(
      'Kein Passwort vorhanden. Setze FEEDBACK_USER_PASSWORD oder starte den Befehl interaktiv.',
    );
  }
  const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: true });
  try {
    const first = await rl.question('Passwort (mindestens 12 Zeichen): ');
    const second = await rl.question('Passwort wiederholen: ');
    if (first !== second) {
      throw new Error('Die Passwoerter stimmen nicht ueberein.');
    }
    return first;
  } finally {
    rl.close();
  }
}

async function main(): Promise<void> {
  const args = parseArguments(process.argv.slice(2));
  const config = loadConfig();
  const handle = openDatabase(config.databaseFile);

  try {
    runMigrations(handle.sqlite);
    const repositories = createSqliteRepositories(handle.db);

    const existing = await repositories.users.findByEmail(args.email);
    if (existing !== undefined) {
      throw new Error('Es existiert bereits ein Benutzer mit dieser E-Mail-Adresse.');
    }

    const password = await readPassword();
    const passwordHash = await hashPassword(password);
    const user = await repositories.users.create({
      email: args.email,
      passwordHash,
      displayName: args.displayName,
    });

    console.log(`Benutzer angelegt: ${user.email} (id ${user.id})`);
  } finally {
    handle.close();
  }
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
