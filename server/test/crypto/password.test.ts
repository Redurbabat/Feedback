import { describe, expect, it } from 'vitest';

import { hashPassword, verifyPassword } from '../../src/crypto/password.js';
import { randomPassword } from '../helpers/harness.js';

// Reduced cost keeps the suite fast; the format and the verification path are
// identical to the production parameters.
const TEST_PARAMETERS = {
  cost: 4096,
  blockSize: 8,
  parallelism: 1,
  keyLength: 32,
  saltBytes: 16,
} as const;

describe('password hashing', () => {
  it('produces a self describing record and never stores the plaintext', async () => {
    const password = randomPassword();
    const record = await hashPassword(password, TEST_PARAMETERS);

    expect(record.startsWith('scrypt$N=4096,r=8,p=1$')).toBe(true);
    expect(record.split('$')).toHaveLength(4);
    expect(record).not.toContain(password);
  });

  it('uses a fresh salt for every hash', async () => {
    const password = randomPassword();
    const first = await hashPassword(password, TEST_PARAMETERS);
    const second = await hashPassword(password, TEST_PARAMETERS);
    expect(first).not.toBe(second);
    expect(await verifyPassword(password, first)).toBe(true);
    expect(await verifyPassword(password, second)).toBe(true);
  });

  it('accepts the correct password and rejects wrong ones', async () => {
    const password = randomPassword();
    const record = await hashPassword(password, TEST_PARAMETERS);

    expect(await verifyPassword(password, record)).toBe(true);
    expect(await verifyPassword(`${password}x`, record)).toBe(false);
    expect(await verifyPassword(password.slice(0, -1), record)).toBe(false);
    expect(await verifyPassword('', record)).toBe(false);
  });

  it('refuses to hash a password below the minimum length', async () => {
    await expect(hashPassword('kurz', TEST_PARAMETERS)).rejects.toThrow(/12 Zeichen/);
  });

  it('returns false for malformed or foreign records instead of throwing', async () => {
    const password = randomPassword();
    expect(await verifyPassword(password, '')).toBe(false);
    expect(await verifyPassword(password, 'scrypt$N=4096,r=8,p=1$onlythreeparts')).toBe(false);
    expect(await verifyPassword(password, 'argon2$N=4096,r=8,p=1$c2FsdA$aGFzaA')).toBe(false);
    expect(await verifyPassword(password, 'scrypt$N=3,r=8,p=1$c2FsdA$aGFzaA')).toBe(false);
    expect(await verifyPassword(password, 'scrypt$bogus$c2FsdA$aGFzaA')).toBe(false);
  });

  it('detects a flipped bit in the stored hash', async () => {
    const password = randomPassword();
    const record = await hashPassword(password, TEST_PARAMETERS);
    const parts = record.split('$');
    const encodedHash = parts.at(3);
    expect(encodedHash).toBeDefined();
    if (!encodedHash) {
      throw new Error('Expected encoded password hash');
    }

    const hash = Buffer.from(encodedHash, 'base64url');
    expect(hash.length).toBeGreaterThan(0);
    hash.writeUInt8(hash.readUInt8(0) ^ 0x01, 0);
    parts[3] = hash.toString('base64url');

    expect(await verifyPassword(password, parts.join('$'))).toBe(false);
  });
});
