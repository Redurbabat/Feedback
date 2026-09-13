import { describe, expect, it } from 'vitest';

import {
  decodeBase64Strict,
  decodeBase64UrlStrict,
  isBase64Url,
  randomDisplayCode,
  randomToken,
  sha256Hex,
  timingSafeEqualString,
  verifyHashedSecret,
} from '../../src/crypto/tokens.js';

describe('randomToken', () => {
  it('produces base64url without padding and the requested entropy', () => {
    const token = randomToken(32);
    expect(isBase64Url(token)).toBe(true);
    expect(token).not.toContain('=');
    expect(Buffer.from(token, 'base64url')).toHaveLength(32);
  });

  it('does not repeat', () => {
    const tokens = new Set(Array.from({ length: 500 }, () => randomToken(32)));
    expect(tokens.size).toBe(500);
  });

  it('refuses implausible lengths', () => {
    expect(() => randomToken(8)).toThrow(RangeError);
    expect(() => randomToken(128)).toThrow(RangeError);
  });
});

describe('hashing and comparison', () => {
  it('hashes to lowercase hex of length 64', () => {
    expect(sha256Hex('feedback')).toMatch(/^[0-9a-f]{64}$/);
  });

  it('verifies a secret against its stored digest', () => {
    const secret = randomToken(32);
    const stored = sha256Hex(secret);
    expect(verifyHashedSecret(secret, stored)).toBe(true);
    expect(verifyHashedSecret(randomToken(32), stored)).toBe(false);
    expect(verifyHashedSecret('', stored)).toBe(false);
  });

  it('compares strings of differing length without throwing', () => {
    expect(timingSafeEqualString('abc', 'abcd')).toBe(false);
    expect(timingSafeEqualString('abc', 'abc')).toBe(true);
    expect(timingSafeEqualString('', '')).toBe(true);
  });
});

describe('strict decoding', () => {
  it('accepts canonical encodings and rejects everything else', () => {
    expect(decodeBase64UrlStrict('QUJD')?.toString('utf8')).toBe('ABC');
    expect(decodeBase64UrlStrict('QUJD=')).toBeUndefined();
    expect(decodeBase64UrlStrict('QU JD')).toBeUndefined();
    expect(decodeBase64UrlStrict('+/==')).toBeUndefined();
    expect(decodeBase64UrlStrict('')).toBeUndefined();

    expect(decodeBase64Strict('QUJD')?.toString('utf8')).toBe('ABC');
    expect(decodeBase64Strict('QUJ')).toBeUndefined();
    expect(decodeBase64Strict('QQ==')?.toString('utf8')).toBe('A');
    expect(decodeBase64Strict('QQ')).toBeUndefined();
  });
});

describe('display code', () => {
  it('always returns six digits', () => {
    for (let index = 0; index < 200; index += 1) {
      expect(randomDisplayCode()).toMatch(/^[0-9]{6}$/);
    }
  });

  it('is spread over the whole range including leading zeros', () => {
    const samples = Array.from({ length: 20_000 }, () => randomDisplayCode());
    const numeric = samples.map((code) => Number(code));

    expect(Math.min(...numeric)).toBeLessThan(100_000);
    expect(Math.max(...numeric)).toBeGreaterThan(900_000);
    expect(new Set(samples).size).toBeGreaterThan(19_000);
  });

  it('is close to uniform across the decimal buckets (no modulo bias)', () => {
    const buckets = new Array<number>(10).fill(0);
    const samples = 60_000;
    for (let index = 0; index < samples; index += 1) {
      const code = randomDisplayCode();
      const leading = Number(code[0]);
      buckets[leading] = (buckets[leading] ?? 0) + 1;
    }
    const expected = samples / 10;
    for (const count of buckets) {
      // Generous bound: a modulo bias over 2^32 would be far smaller than this,
      // but a broken implementation (e.g. only 3 random digits) fails hard.
      expect(count).toBeGreaterThan(expected * 0.9);
      expect(count).toBeLessThan(expected * 1.1);
    }
  });
});
