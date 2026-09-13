#!/usr/bin/env node
/* ============================================================================
 * check-protocol-constants - guards the contract across the three implementations.
 *
 * protocol/PROTOCOL.md is the source of truth. Android, the server and the control
 * web each restate parts of it in their own language, and those restatements drift
 * silently: a limit changed in one place still compiles everywhere else, and the
 * first sign of trouble is a device that cannot pair.
 *
 * This validator fails loudly when it cannot do its job. A guard that quietly
 * passes because its input moved is worse than no guard at all - it reads as
 * "checked" in CI while checking nothing.
 *
 * Run: node tools/validators/check-protocol-constants.mjs
 * Exit: 0 clean, 1 findings or unusable input.
 * ========================================================================== */

import { readFileSync, existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

const FILES = {
  protocol: 'protocol/PROTOCOL.md',
  kotlinConstants: 'android/app/src/main/java/com/redurbabat/feedback/protocol/ProtocolConstants.kt',
  kotlinCapability: 'android/app/src/main/java/com/redurbabat/feedback/protocol/Capability.kt',
  kotlinError: 'android/app/src/main/java/com/redurbabat/feedback/protocol/ProtocolError.kt',
  serverConstants: 'server/src/constants.ts',
  serverErrors: 'server/src/errors.ts',
};

/**
 * Divergences that are known, deliberate and written down somewhere a human reads.
 * Anything not listed here is a finding. Removing an entry must make the check pass,
 * never fail - otherwise the list becomes a place to hide problems.
 */
const KNOWN_GAPS = [
  {
    id: 'server-implemented-capabilities',
    reason:
      'screen.view: die Serverhaelfte (ScreenStreamHub, SSE-Bruecke, vier Endpunkte) ist fertig, ' +
      'die Android-Seite noch nicht. Der Server darf die Capability deshalb kennen, das Geraet ' +
      'meldet sie noch nicht als implementiert. Faellt weg, sobald Capability.kt SCREEN_VIEW auf ' +
      'true setzt - siehe docs/roadmap/OPEN_WORK.md Milestone 5.',
  },
];

const problems = [];
const notes = [];

function fail(message) {
  problems.push(message);
}

function read(key) {
  const rel = FILES[key];
  const abs = path.join(ROOT, rel);
  if (!existsSync(abs)) {
    // Hard stop: a missing input is the exact case where a weak guard goes green.
    console.error(`FEHLER: ${rel} fehlt. Der Validator kann seine Aufgabe nicht erfuellen.`);
    process.exit(1);
  }
  return readFileSync(abs, 'utf8');
}

/** Numbers may be written 300000, 300_000 or 300_000L depending on the language. */
function parseNumber(raw) {
  const cleaned = String(raw).trim().replace(/_/g, '').replace(/[Ll]$/, '');
  if (!/^-?\d+$/.test(cleaned)) return null;
  return Number(cleaned);
}

function section(markdown, heading, nextHeadingPrefix) {
  const start = markdown.indexOf(heading);
  if (start === -1) return null;
  const rest = markdown.slice(start + heading.length);
  const end = rest.indexOf(nextHeadingPrefix);
  return end === -1 ? rest : rest.slice(0, end);
}

// --------------------------------------------------------------- protocol ---

const protocolText = read('protocol');

// Section 11: | `NAME` | 123 |
const limitsSection = section(protocolText, '\n## 11. Limits', '\nRate Limits');
if (!limitsSection) {
  console.error('FEHLER: Abschnitt "## 11. Limits" nicht in protocol/PROTOCOL.md gefunden.');
  process.exit(1);
}
const protocolLimits = new Map();
for (const match of limitsSection.matchAll(/^\|\s*`([A-Z0-9_]+)`\s*\|\s*([0-9_]+)\s*\|\s*$/gm)) {
  const value = parseNumber(match[2]);
  if (value === null) {
    fail(`protocol/PROTOCOL.md: Limit ${match[1]} hat keinen lesbaren Zahlenwert.`);
    continue;
  }
  protocolLimits.set(match[1], value);
}
if (protocolLimits.size === 0) {
  console.error('FEHLER: Abschnitt 11 enthaelt keine lesbaren Limits. Tabellenformat geaendert?');
  process.exit(1);
}

// Section 8.1: fenced list of capability wire names
const capabilitySection = section(protocolText, '\n### 8.1 Liste v1', '\nKeine implizite');
const capabilityBlock = capabilitySection && capabilitySection.match(/```text\n([\s\S]*?)```/);
if (!capabilityBlock) {
  console.error('FEHLER: Capability-Liste aus Abschnitt 8.1 nicht lesbar.');
  process.exit(1);
}
const protocolCapabilities = capabilityBlock[1]
  .split('\n')
  .map((line) => line.trim())
  .filter(Boolean);

// Section 10: | `CODE` | text | http |
const errorSection = section(protocolText, '\n## 10. Fehlercodes', '\n## 11.');
if (!errorSection) {
  console.error('FEHLER: Abschnitt "## 10. Fehlercodes" nicht gefunden.');
  process.exit(1);
}
const protocolErrors = [
  ...new Set([...errorSection.matchAll(/^\|\s*`([A-Z_]+)`\s*\|/gm)].map((m) => m[1])),
];
if (protocolErrors.length === 0) {
  console.error('FEHLER: Abschnitt 10 enthaelt keine lesbaren Fehlercodes.');
  process.exit(1);
}

// ---------------------------------------------------------------- android ---

const kotlinConstantsText = read('kotlinConstants');
const kotlinLimits = new Map();
for (const match of kotlinConstantsText.matchAll(
  /const\s+val\s+([A-Z0-9_]+)\s*[:=][^=\n]*?=?\s*([0-9_]+[Ll]?)\s*$/gm,
)) {
  const value = parseNumber(match[2]);
  if (value !== null) kotlinLimits.set(match[1], value);
}

const kotlinCapabilityText = read('kotlinCapability');
const kotlinCapabilities = [
  ...kotlinCapabilityText.matchAll(/^\s*[A-Z_]+\("([a-z.]+)",\s*(true|false)\)/gm),
].map((m) => ({ name: m[1], implemented: m[2] === 'true' }));

const kotlinErrorText = read('kotlinError');
const kotlinErrors = [
  ...new Set([...kotlinErrorText.matchAll(/^\s*[A-Z_]+\("([A-Z_]+)"/gm)].map((m) => m[1])),
];

// ----------------------------------------------------------------- server ---

const serverConstantsText = read('serverConstants');
const serverLimits = new Map();
for (const match of serverConstantsText.matchAll(
  /export\s+const\s+([A-Z0-9_]+)\s*=\s*([0-9_]+)\s*(?:as\s+const)?\s*;/gm,
)) {
  const value = parseNumber(match[2]);
  if (value !== null) serverLimits.set(match[1], value);
}

function stringArray(text, name) {
  const match = text.match(new RegExp(`${name}[^=]*=\\s*\\[([\\s\\S]*?)\\]`));
  if (!match) return null;
  return [...match[1].matchAll(/'([^']+)'/g)].map((m) => m[1]);
}

const serverCapabilities = stringArray(serverConstantsText, 'CAPABILITIES_V1');
const serverImplemented = stringArray(serverConstantsText, 'IMPLEMENTED_CAPABILITIES_V1');
const serverErrors = stringArray(read('serverErrors'), 'ERROR_CODES');

if (!serverCapabilities) {
  console.error('FEHLER: CAPABILITIES_V1 nicht aus server/src/constants.ts lesbar.');
  process.exit(1);
}
if (!serverErrors) {
  console.error('FEHLER: ERROR_CODES nicht aus server/src/errors.ts lesbar.');
  process.exit(1);
}

// ------------------------------------------------------------- comparisons ---

for (const [name, expected] of protocolLimits) {
  for (const [label, table] of [
    ['ProtocolConstants.kt', kotlinLimits],
    ['server/src/constants.ts', serverLimits],
  ]) {
    if (!table.has(name)) {
      fail(`${label}: Limit ${name} fehlt (protocol/PROTOCOL.md Abschnitt 11 = ${expected}).`);
    } else if (table.get(name) !== expected) {
      fail(
        `${label}: ${name} ist ${table.get(name)}, protocol/PROTOCOL.md Abschnitt 11 sagt ${expected}.`,
      );
    }
  }
}

const kotlinNames = kotlinCapabilities.map((c) => c.name);
if (kotlinNames.join(',') !== protocolCapabilities.join(',')) {
  fail(
    `Capability.kt weicht von Abschnitt 8.1 ab.\n    Protokoll: ${protocolCapabilities.join(', ')}\n    Kotlin:    ${kotlinNames.join(', ')}`,
  );
}
if (serverCapabilities.join(',') !== protocolCapabilities.join(',')) {
  fail(
    `server/src/constants.ts CAPABILITIES_V1 weicht von Abschnitt 8.1 ab.\n    Protokoll: ${protocolCapabilities.join(', ')}\n    Server:    ${serverCapabilities.join(', ')}`,
  );
}

for (const [label, list] of [
  ['ProtocolError.kt', kotlinErrors],
  ['server/src/errors.ts', serverErrors],
]) {
  const missing = protocolErrors.filter((code) => !list.includes(code));
  const extra = list.filter((code) => !protocolErrors.includes(code));
  if (missing.length > 0) fail(`${label}: Fehlercodes fehlen: ${missing.join(', ')}.`);
  if (extra.length > 0) fail(`${label}: unbekannte Fehlercodes: ${extra.join(', ')}.`);
}

// Implemented capabilities: a real per-component fact, so a difference is reported
// rather than assumed wrong - but it has to be declared.
const kotlinImplemented = kotlinCapabilities.filter((c) => c.implemented).map((c) => c.name);
if (serverImplemented && kotlinImplemented.join(',') !== serverImplemented.join(',')) {
  const declared = KNOWN_GAPS.find((gap) => gap.id === 'server-implemented-capabilities');
  const message =
    `Implementierte Capabilities unterscheiden sich.\n    Android: ${kotlinImplemented.join(', ')}\n    Server:  ${serverImplemented.join(', ')}`;
  if (declared) {
    notes.push(`${message}\n    Bekannt und dokumentiert: ${declared.reason}`);
  } else {
    fail(message);
  }
}

// ----------------------------------------------------------------- report ---

console.log(
  `Geprueft: ${protocolLimits.size} Limits, ${protocolCapabilities.length} Capabilities, ` +
    `${protocolErrors.length} Fehlercodes gegen ${Object.keys(FILES).length} Dateien.`,
);

for (const note of notes) {
  console.log(`\nHINWEIS: ${note}`);
}

if (problems.length > 0) {
  console.error(`\n${problems.length} Abweichung(en) gefunden:\n`);
  for (const problem of problems) {
    console.error(`  - ${problem}`);
  }
  console.error(
    '\nprotocol/PROTOCOL.md ist die Wahrheit. Weicht eine Implementierung ab, ist die\n' +
      'Implementierung falsch - es sei denn, das Protokoll wurde bewusst geaendert.',
  );
  process.exit(1);
}

console.log('\nKeine undokumentierte Abweichung.');
