#!/usr/bin/env node
/* ============================================================================
 * check-protocol-fixtures - makes sure the shared examples stay complete.
 *
 * protocol/fixtures/ is the only artefact both implementations read. Its value
 * depends entirely on it covering the protocol: a message type with no fixture is
 * a message type nobody checks across the two sides, and that is exactly where
 * drift starts.
 *
 * So this validator compares the fixtures against the message table in
 * PROTOCOL.md section 7.1 and fails when a type has no example, when a fixture
 * names a type the protocol does not define, or when a fixture claims a direction
 * the protocol does not allow.
 *
 * Like its sibling, it fails loudly when it cannot do its job.
 *
 * Run: node tools/validators/check-protocol-fixtures.mjs
 * Exit: 0 clean, 1 findings or unusable input.
 * ========================================================================== */

import { readFileSync, existsSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const PROTOCOL = 'protocol/PROTOCOL.md';
const FIXTURE_DIR = 'protocol/fixtures';

/**
 * Prefixes that must be covered by fixtures.
 *
 * Deliberately a list rather than "everything": presence and pairing messages are
 * covered elsewhere, and demanding a fixture for them right now would only invite
 * empty placeholder files. Adding a prefix here is how coverage grows.
 */
const COVERED_PREFIXES = ['screen.', 'files.'];

const problems = [];

function fatal(message) {
  console.error(`FEHLER: ${message}`);
  process.exit(1);
}

const protocolPath = path.join(ROOT, PROTOCOL);
if (!existsSync(protocolPath)) {
  fatal(`${PROTOCOL} fehlt. Der Validator kann seine Aufgabe nicht erfuellen.`);
}
const protocolText = readFileSync(protocolPath, 'utf8');

// Section 7.1: | `type` | Richtung | Payload |
const tableStart = protocolText.indexOf('### 7.1 Nachrichtentypen v1');
if (tableStart === -1) {
  fatal('Abschnitt "### 7.1 Nachrichtentypen v1" nicht gefunden.');
}
const tableText = protocolText.slice(tableStart, protocolText.indexOf('\n## 8.'));
const declared = new Map();
for (const match of tableText.matchAll(/^\|\s*`([a-z.]+)`\s*\|\s*([^|]+?)\s*\|/gm)) {
  const [, type, direction] = match;
  declared.set(type, normalizeDirection(direction));
}
if (declared.size === 0) {
  fatal('Abschnitt 7.1 enthaelt keine lesbaren Nachrichtentypen. Tabellenformat geaendert?');
}

function normalizeDirection(value) {
  if (value.includes('beide')) return 'both';
  if (value.includes('Server') && value.indexOf('Server') < value.indexOf('Geraet')) {
    return 'server-to-device';
  }
  return 'device-to-server';
}

const fixtureDir = path.join(ROOT, FIXTURE_DIR);
if (!existsSync(fixtureDir)) {
  fatal(`${FIXTURE_DIR} fehlt.`);
}
const files = readdirSync(fixtureDir).filter((name) => name.endsWith('.json'));
if (files.length === 0) {
  fatal(`${FIXTURE_DIR} enthaelt keine Fixtures.`);
}

const seen = new Map();
let accepted = 0;
let rejected = 0;

for (const file of files) {
  const raw = readFileSync(path.join(fixtureDir, file), 'utf8');
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (error) {
    fatal(`${FIXTURE_DIR}/${file} ist kein gueltiges JSON: ${error.message}`);
  }
  for (const group of ['accepted', 'rejected']) {
    const entries = parsed[group];
    if (!Array.isArray(entries)) {
      fatal(`${FIXTURE_DIR}/${file}: "${group}" fehlt oder ist keine Liste.`);
    }
    for (const entry of entries) {
      if (typeof entry.type !== 'string' || typeof entry.direction !== 'string') {
        problems.push(`${file}: Eintrag "${entry.name ?? '?'}" hat keinen type oder keine direction.`);
        continue;
      }
      if (group === 'rejected' && typeof entry.why !== 'string') {
        // A negative fixture without a reason is a puzzle for the next reader.
        problems.push(`${file}: abgelehnter Eintrag "${entry.name}" sagt nicht, warum.`);
      }
      const allowed = declared.get(entry.type);
      if (allowed === undefined) {
        problems.push(`${file}: "${entry.type}" steht nicht in Abschnitt 7.1.`);
        continue;
      }
      if (allowed !== 'both' && allowed !== entry.direction) {
        problems.push(
          `${file}: "${entry.name}" behauptet ${entry.direction}, Abschnitt 7.1 sagt ${allowed}.`,
        );
      }
      if (group === 'accepted') {
        accepted += 1;
        seen.set(entry.type, (seen.get(entry.type) ?? 0) + 1);
      } else {
        rejected += 1;
      }
    }
  }
}

for (const [type] of declared) {
  if (!COVERED_PREFIXES.some((prefix) => type.startsWith(prefix))) {
    continue;
  }
  if (!seen.has(type)) {
    problems.push(`Kein akzeptiertes Fixture fuer "${type}" (Abschnitt 7.1 deklariert ihn).`);
  }
}

console.log(
  `Geprueft: ${accepted} akzeptierte und ${rejected} abgelehnte Fixtures gegen ` +
    `${declared.size} Nachrichtentypen aus ${PROTOCOL}.`,
);

if (problems.length > 0) {
  console.error('\nBefunde:');
  for (const problem of problems) {
    console.error(`  - ${problem}`);
  }
  process.exit(1);
}

console.log('\nJeder abgedeckte Nachrichtentyp hat ein Beispiel.');
