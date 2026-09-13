import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';
import type { ZodTypeAny } from 'zod';

import {
  downloadCancelSchema,
  downloadChunkSchema,
  downloadCompleteSchema,
  listResponseSchema,
  metadataResponseSchema,
  screenConsentSchema,
  screenFrameSchema,
  screenStartedSchema,
  screenStopSchema,
  sharesResponseSchema,
} from '../../src/protocol/wire.js';

/**
 * The server half of the cross-implementation contract test.
 *
 * `protocol/fixtures/` is read by this file AND by the Android unit tests. Neither
 * implementation can quietly rename a field, widen a limit or accept a new state
 * without the other one failing - which is the one thing three separate
 * implementations of the same protocol cannot do for themselves.
 *
 * The schemas here are the ones the agent route actually uses, not a copy.
 */

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');

interface Fixture {
  readonly name: string;
  readonly type: string;
  readonly direction: string;
  readonly why?: string;
  readonly payload: Record<string, unknown>;
}

interface FixtureFile {
  readonly accepted: Fixture[];
  readonly rejected: Fixture[];
}

function load(file: string): FixtureFile {
  const raw = readFileSync(path.join(ROOT, 'protocol', 'fixtures', file), 'utf8');
  return JSON.parse(raw) as FixtureFile;
}

/** Only the types the device sends have a parsing schema on this side. */
const SCHEMAS: Record<string, ZodTypeAny | undefined> = {
  'screen.consent': screenConsentSchema,
  'screen.started': screenStartedSchema,
  'screen.frame': screenFrameSchema,
  'screen.stop': screenStopSchema,
  'files.shares.response': sharesResponseSchema,
  'files.list.response': listResponseSchema,
  'files.metadata.response': metadataResponseSchema,
  'files.download.chunk': downloadChunkSchema,
  'files.download.complete': downloadCompleteSchema,
  'files.download.cancel': downloadCancelSchema,
};

function suite(file: string, minimumAccepted: number, minimumRejected: number): void {
  const fixtures = load(file);

  describe(file, () => {
    it('carries examples at all, so a green run means something', () => {
      // A fixture file that silently emptied itself would make every test below pass.
      expect(fixtures.accepted.length).toBeGreaterThanOrEqual(minimumAccepted);
      expect(fixtures.rejected.length).toBeGreaterThanOrEqual(minimumRejected);
    });

    const inbound = fixtures.accepted.filter(
      (fixture) => fixture.direction === 'device-to-server' && SCHEMAS[fixture.type] !== undefined,
    );

    for (const fixture of inbound) {
      it(`accepts ${fixture.type}: ${fixture.name}`, () => {
        const schema = SCHEMAS[fixture.type];
        const result = schema?.safeParse(fixture.payload);
        expect(result?.success, JSON.stringify(result?.error?.issues)).toBe(true);
      });
    }

    for (const fixture of fixtures.rejected.filter(
      (entry) => entry.direction === 'device-to-server' && SCHEMAS[entry.type] !== undefined,
    )) {
      it(`refuses ${fixture.type}: ${fixture.name}`, () => {
        const schema = SCHEMAS[fixture.type];
        expect(schema?.safeParse(fixture.payload).success, fixture.why).toBe(false);
      });
    }
  });
}

suite('screen-v1.json', 6, 4);
suite('files-v1.json', 10, 6);

describe('fixture coverage', () => {
  it('has an inbound example for every device message type the server parses', () => {
    const covered = new Set(
      [...load('screen-v1.json').accepted, ...load('files-v1.json').accepted]
        .filter((fixture) => fixture.direction === 'device-to-server')
        .map((fixture) => fixture.type),
    );
    // The point of the whole exercise: a schema without an example is a schema the
    // other implementation is not held to.
    for (const type of Object.keys(SCHEMAS)) {
      expect(covered.has(type), `kein Fixture fuer ${type}`).toBe(true);
    }
  });
});
