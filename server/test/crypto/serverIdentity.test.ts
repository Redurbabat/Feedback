import { chmodSync, mkdtempSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { parseSpki, verifySignature } from '../../src/crypto/deviceIdentity.js';
import {
  loadOrCreateServerIdentity,
  serverIdentityPayload,
} from '../../src/crypto/serverIdentity.js';

function keyFile(): string {
  return path.join(mkdtempSync(path.join(tmpdir(), 'feedback-serverkey-')), 'identity.pem');
}

function payloadFor(publicKeyBase64: string, overrides: Partial<{
  deviceId: string;
  nonceBase64Url: string;
  issuedAtEpochMillis: number;
}> = {}): string {
  return serverIdentityPayload({
    deviceId: overrides.deviceId ?? 'fb-0123456789abcdef01234567',
    nonceBase64Url: overrides.nonceBase64Url ?? 'bm9uY2UtZWlucw',
    publicKeyBase64,
    issuedAtEpochMillis: overrides.issuedAtEpochMillis ?? 1_700_000_000_000,
  });
}

describe('server identity', () => {
  it('produces a key the device side can parse and verify', () => {
    const identity = loadOrCreateServerIdentity(keyFile());
    // Parsed with the device parser on purpose: one scheme in this protocol, not two.
    const key = parseSpki(identity.publicKeyBase64);
    const payload = payloadFor(identity.publicKeyBase64);

    expect(verifySignature(payload, identity.sign(payload), key)).toBe(true);
  });

  it('does not verify a payload it did not sign', () => {
    const identity = loadOrCreateServerIdentity(keyFile());
    const key = parseSpki(identity.publicKeyBase64);
    const signature = identity.sign(payloadFor(identity.publicKeyBase64));

    for (const other of [
      payloadFor(identity.publicKeyBase64, { deviceId: 'fb-ffffffffffffffffffffffff' }),
      payloadFor(identity.publicKeyBase64, { nonceBase64Url: 'bm9uY2UtendlaQ' }),
      payloadFor(identity.publicKeyBase64, { issuedAtEpochMillis: 1_700_000_000_001 }),
    ]) {
      expect(verifySignature(other, signature, key)).toBe(false);
    }
  });

  /**
   * The whole point of the file: the identity has to be the same one tomorrow. A server that
   * generates a new key on every start would look to every paired device exactly like a
   * substituted server.
   */
  it('keeps the same identity across restarts', () => {
    const file = keyFile();
    const first = loadOrCreateServerIdentity(file);
    const second = loadOrCreateServerIdentity(file);

    expect(second.publicKeyBase64).toBe(first.publicKeyBase64);
  });

  it('gives two servers two identities', () => {
    expect(loadOrCreateServerIdentity(keyFile()).publicKeyBase64).not.toBe(
      loadOrCreateServerIdentity(keyFile()).publicKeyBase64,
    );
  });

  it('creates the key file readable by nobody else', () => {
    const file = keyFile();
    loadOrCreateServerIdentity(file);
    expect(statSync(file).mode & 0o777).toBe(0o600);
  });

  /**
   * Never silently replaced. Generating a fresh key over an unreadable one would lock out every
   * paired device without saying so - refusing to start is the answer the owner can act on.
   */
  it('refuses to start rather than replace a key file it cannot read', () => {
    const file = keyFile();
    writeFileSync(file, 'das ist kein schluessel', { mode: 0o600 });

    expect(() => loadOrCreateServerIdentity(file)).toThrow();
    expect(readFileSync(file, 'utf8')).toBe('das ist kein schluessel');
  });

  it('does not rewrite a key file that is already there', () => {
    const file = keyFile();
    loadOrCreateServerIdentity(file);
    const before = readFileSync(file, 'utf8');
    chmodSync(file, 0o600);

    loadOrCreateServerIdentity(file);
    expect(readFileSync(file, 'utf8')).toBe(before);
  });
});
