import { createHash, generateKeyPairSync, randomBytes, sign as cryptoSign } from 'node:crypto';

import { describe, expect, it } from 'vitest';

import {
  canonicalPairingClaim,
  canonicalPairingStart,
  deriveDeviceId,
  formatFingerprint,
  parseAndVerifyIdentity,
  parseSpki,
  verifySignature,
} from '../../src/crypto/deviceIdentity.js';
import { ProtocolError } from '../../src/errors.js';
import { createTestDevice } from '../helpers/harness.js';

describe('device identity derivation', () => {
  it('derives deviceId and fingerprint exactly as specified in PROTOCOL.md section 3', () => {
    const device = createTestDevice();
    const spki = Buffer.from(device.publicKeyBase64, 'base64');
    const hex = createHash('sha256').update(spki).digest('hex');

    expect(deriveDeviceId(spki)).toBe(`fb-${hex.slice(0, 24)}`);
    expect(deriveDeviceId(spki)).toMatch(/^fb-[0-9a-f]{24}$/);

    const fingerprint = formatFingerprint(spki);
    expect(fingerprint.split(':')).toHaveLength(16);
    expect(fingerprint.replaceAll(':', '')).toBe(hex);
  });

  it('rejects a public key on the wrong curve', () => {
    const { publicKey } = generateKeyPairSync('ec', { namedCurve: 'secp384r1' });
    const spki = publicKey.export({ format: 'der', type: 'spki' }).toString('base64');

    expect(() => parseSpki(spki)).toThrow(ProtocolError);
    try {
      parseSpki(spki);
    } catch (error) {
      expect((error as ProtocolError).code).toBe('INVALID_MESSAGE');
    }
  });

  it('rejects a non-EC key', () => {
    const { publicKey } = generateKeyPairSync('ed25519');
    const spki = publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
    expect(() => parseSpki(spki)).toThrow(/EC-Schluessel|SPKI/);
  });

  it('rejects malformed base64 and oversized input', () => {
    expect(() => parseSpki('not base64!!')).toThrow(ProtocolError);
    expect(() => parseSpki('A'.repeat(600))).toThrow(/Laenge/);
    expect(() => parseSpki('')).toThrow(/Laenge/);
  });

  it('rejects a deviceId that does not belong to the key', () => {
    const device = createTestDevice();
    expect(() =>
      parseAndVerifyIdentity({
        publicKeyBase64: device.publicKeyBase64,
        deviceId: 'fb-000000000000000000000000',
        fingerprint: device.fingerprint,
      }),
    ).toThrow(/deviceId/);
  });

  it('rejects a fingerprint that does not belong to the key', () => {
    const device = createTestDevice();
    const wrong = device.fingerprint.replace(/^.{4}/, 'ffff');
    expect(() =>
      parseAndVerifyIdentity({
        publicKeyBase64: device.publicKeyBase64,
        deviceId: device.deviceId,
        fingerprint: wrong,
      }),
    ).toThrow(/fingerprint/);
  });
});

describe('signature verification', () => {
  const device = createTestDevice();
  const key = parseSpki(device.publicKeyBase64);

  it('accepts a signature produced by the matching private key', () => {
    const payload = 'feedback-test-payload';
    expect(verifySignature(payload, device.sign(payload), key)).toBe(true);
  });

  it('rejects a signature over a different payload', () => {
    const signature = device.sign('payload-a');
    expect(verifySignature('payload-b', signature, key)).toBe(false);
  });

  it('rejects a signature from a different device key', () => {
    const other = createTestDevice();
    const payload = 'feedback-test-payload';
    expect(verifySignature(payload, other.sign(payload), key)).toBe(false);
  });

  it('rejects a tampered signature without throwing', () => {
    const payload = 'feedback-test-payload';
    const signature = Buffer.from(device.sign(payload), 'base64url');
    expect(signature.length).toBeGreaterThan(0);
    const lastIndex = signature.length - 1;
    signature.writeUInt8(signature.readUInt8(lastIndex) ^ 0xff, lastIndex);
    expect(verifySignature(payload, signature.toString('base64url'), key)).toBe(false);
  });

  it('rejects signature material that is not base64url', () => {
    expect(verifySignature('payload', 'not+base64url/', key)).toBe(false);
    expect(verifySignature('payload', '', key)).toBe(false);
  });

  it('rejects a raw (P1363) signature because DER is mandated', () => {
    const payload = 'feedback-test-payload';
    const raw = cryptoSign('sha256', Buffer.from(payload, 'utf8'), {
      key: device.privateKey,
      dsaEncoding: 'ieee-p1363',
    }).toString('base64url');
    expect(verifySignature(payload, raw, key)).toBe(false);
  });
});

describe('canonical payloads', () => {
  it('builds the pairing start payload line by line without a trailing newline', () => {
    const canonical = canonicalPairingStart({
      deviceId: 'fb-a1b2c3d4e5f60718293a4b5c',
      publicKeyBase64: 'QUJD',
      fingerprint: 'a1b2:c3d4',
      deviceName: 'Galaxy S24',
      platform: 'android',
      osVersion: '16',
      sdkInt: 36,
      appVersion: '0.2.0',
      nonce: 'bm9uY2U',
      issuedAt: 1757760000000,
    });

    expect(canonical).toBe(
      [
        'feedback-pairing-start-v1',
        'fb-a1b2c3d4e5f60718293a4b5c',
        'QUJD',
        'a1b2:c3d4',
        'Galaxy S24',
        'android',
        '16',
        '36',
        '0.2.0',
        'bm9uY2U',
        '1757760000000',
      ].join('\n'),
    );
    expect(canonical.endsWith('\n')).toBe(false);
    expect(canonical.split('\n')).toHaveLength(11);
  });

  it('builds the pairing claim payload with exactly five lines', () => {
    const canonical = canonicalPairingClaim({
      pairingId: '0f3a4c5e-1111-4222-8333-444455556666',
      deviceId: 'fb-a1b2c3d4e5f60718293a4b5c',
      deviceSecret: 'c2VjcmV0',
      issuedAt: 1757760100000,
    });

    expect(canonical.split('\n')).toEqual([
      'feedback-pairing-claim-v1',
      '0f3a4c5e-1111-4222-8333-444455556666',
      'fb-a1b2c3d4e5f60718293a4b5c',
      'c2VjcmV0',
      '1757760100000',
    ]);
  });

  it('rejects a field that contains a line break', () => {
    expect(() =>
      canonicalPairingStart({
        deviceId: 'fb-a1b2c3d4e5f60718293a4b5c',
        publicKeyBase64: 'QUJD',
        fingerprint: 'a1b2:c3d4',
        deviceName: 'Evil\nandroid\n16',
        platform: 'android',
        osVersion: '16',
        sdkInt: 36,
        appVersion: '0.2.0',
        nonce: 'bm9uY2U',
        issuedAt: 1757760000000,
      }),
    ).toThrow(/Zeilenumbruch/);
  });

  it('a payload that differs only in field boundaries produces a different signature input', () => {
    const base = {
      deviceId: 'fb-a1b2c3d4e5f60718293a4b5c',
      publicKeyBase64: 'QUJD',
      fingerprint: 'a1b2:c3d4',
      platform: 'android',
      osVersion: '16',
      sdkInt: 36,
      appVersion: '0.2.0',
      nonce: randomBytes(18).toString('base64url'),
      issuedAt: 1757760000000,
    };
    const first = canonicalPairingStart({ ...base, deviceName: 'A B' });
    const second = canonicalPairingStart({ ...base, deviceName: 'A  B' });
    expect(first).not.toBe(second);
  });
});
