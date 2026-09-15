import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { parseSpki, verifySignature } from '../../src/crypto/deviceIdentity.js';
import { serverIdentityPayload } from '../../src/crypto/serverIdentity.js';

/**
 * The same file `ServerIdentityFixtureTest.kt` reads.
 *
 * Neither side can quietly change the canonical payload, the curve, the digest or the signature
 * encoding without this failing on one of them. Testing each side against itself would never
 * notice - both would simply agree with themselves.
 */

interface Case {
  readonly reason?: string;
  readonly description: string;
  readonly pinnedPublicKey: string;
  readonly offeredPublicKey: string;
  readonly signature: string;
}

interface Fixtures {
  readonly canonicalPayload: string;
  readonly challenge: { deviceId: string; nonce: string; issuedAt: number };
  readonly accepted: Case;
  readonly rejected: readonly Case[];
}

function load(): Fixtures {
  const here = path.dirname(fileURLToPath(import.meta.url));
  const file = path.resolve(here, '..', '..', '..', 'protocol', 'fixtures', 'server-identity-v1.json');
  return JSON.parse(readFileSync(file, 'utf8')) as Fixtures;
}

/** Exactly what the device does: compare the offered key first, then verify against the PINNED one. */
function accepts(fixtures: Fixtures, subject: Case): boolean {
  if (subject.offeredPublicKey !== subject.pinnedPublicKey) {
    return false;
  }
  const payload = serverIdentityPayload({
    deviceId: fixtures.challenge.deviceId,
    nonceBase64Url: fixtures.challenge.nonce,
    publicKeyBase64: subject.offeredPublicKey,
    issuedAtEpochMillis: fixtures.challenge.issuedAt,
  });
  return verifySignature(payload, subject.signature, parseSpki(subject.pinnedPublicKey));
}

describe('server identity fixtures', () => {
  it('builds the canonical payload the file records', () => {
    const fixtures = load();
    expect(
      serverIdentityPayload({
        deviceId: fixtures.challenge.deviceId,
        nonceBase64Url: fixtures.challenge.nonce,
        publicKeyBase64: fixtures.accepted.offeredPublicKey,
        issuedAtEpochMillis: fixtures.challenge.issuedAt,
      }),
    ).toBe(fixtures.canonicalPayload);
  });

  it('accepts the paired server', () => {
    const fixtures = load();
    expect(accepts(fixtures, fixtures.accepted)).toBe(true);
  });

  it('refuses every case the file marks as refused', () => {
    const fixtures = load();
    // Without this the file could lose its refused cases and the test would still pass.
    expect(fixtures.rejected.length).toBe(2);
    expect(fixtures.rejected.map((entry) => entry.reason).sort()).toEqual([
      'bad_signature',
      'key_changed',
    ]);

    for (const subject of fixtures.rejected) {
      expect(accepts(fixtures, subject), subject.description).toBe(false);
    }
  });

  /**
   * The mistake this fixture exists to catch: verifying against the key that came with the
   * answer. That reads as correct code and says yes to any impostor.
   */
  it('would accept the impostor if the offered key were trusted - which is why it is not', () => {
    const fixtures = load();
    const impostor = fixtures.rejected.find((entry) => entry.reason === 'key_changed');
    expect(impostor).toBeDefined();

    const payload = serverIdentityPayload({
      deviceId: fixtures.challenge.deviceId,
      nonceBase64Url: fixtures.challenge.nonce,
      publicKeyBase64: impostor!.offeredPublicKey,
      issuedAtEpochMillis: fixtures.challenge.issuedAt,
    });
    expect(
      verifySignature(payload, impostor!.signature, parseSpki(impostor!.offeredPublicKey)),
    ).toBe(true);
    expect(accepts(fixtures, impostor!)).toBe(false);
  });
});
