import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';

import { SECRET_BYTES } from '../constants.js';

/**
 * Token and code generation.
 *
 * Secrets (ticket, deviceSecret, deviceToken, session token) are generated with
 * the CSPRNG and are persisted by the server only as SHA-256 hex digests
 * (PROTOCOL.md section 1).
 */

const BASE64URL_PATTERN = /^[A-Za-z0-9_-]+$/;

/** Generates `bytes` random bytes and encodes them as base64url without padding. */
export function randomToken(bytes: number = SECRET_BYTES): string {
  if (!Number.isInteger(bytes) || bytes < 16 || bytes > 64) {
    throw new RangeError('Tokenlaenge muss zwischen 16 und 64 Bytes liegen');
  }
  return randomBytes(bytes).toString('base64url');
}

/** SHA-256 of the UTF-8 representation of `value`, lowercase hex. */
export function sha256Hex(value: string): string {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

/** SHA-256 over raw bytes, lowercase hex. */
export function sha256HexBytes(value: Uint8Array): string {
  return createHash('sha256').update(value).digest('hex');
}

/**
 * Constant-time comparison of two strings of identical byte length.
 * Returns false for differing lengths without leaking the position.
 */
export function timingSafeEqualString(a: string, b: string): boolean {
  const left = Buffer.from(a, 'utf8');
  const right = Buffer.from(b, 'utf8');
  if (left.length !== right.length) {
    // Still perform a comparison so that the code path has no early-exit
    // asymmetry that depends on the secret content itself.
    timingSafeEqual(left, left);
    return false;
  }
  return timingSafeEqual(left, right);
}

/**
 * Constant-time check of a presented secret against a stored SHA-256 hex digest.
 * The digests always have the same length, so no length information leaks.
 */
export function verifyHashedSecret(presented: string, storedSha256Hex: string): boolean {
  return timingSafeEqualString(sha256Hex(presented), storedSha256Hex);
}

/** True if `value` is non-empty base64url without padding. */
export function isBase64Url(value: string): boolean {
  return BASE64URL_PATTERN.test(value);
}

/**
 * Decodes base64url strictly: the input must round-trip, otherwise the value
 * contained characters outside the alphabet or invalid padding.
 */
export function decodeBase64UrlStrict(value: string): Buffer | undefined {
  if (!isBase64Url(value)) {
    return undefined;
  }
  const decoded = Buffer.from(value, 'base64url');
  if (decoded.length === 0 || decoded.toString('base64url') !== value) {
    return undefined;
  }
  return decoded;
}

/**
 * Decodes standard base64 (with padding) strictly, rejecting any input that
 * does not re-encode to exactly the same string.
 */
export function decodeBase64Strict(value: string): Buffer | undefined {
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(value) || value.length % 4 !== 0) {
    return undefined;
  }
  const decoded = Buffer.from(value, 'base64');
  if (decoded.length === 0 || decoded.toString('base64') !== value) {
    return undefined;
  }
  return decoded;
}

const DISPLAY_CODE_DIGITS = 6;
const DISPLAY_CODE_RANGE = 10 ** DISPLAY_CODE_DIGITS;
// Largest multiple of the range that fits into an unsigned 32 bit integer.
// Values at or above this bound are rejected, which removes the modulo bias.
const DISPLAY_CODE_REJECTION_BOUND =
  Math.floor(0x1_0000_0000 / DISPLAY_CODE_RANGE) * DISPLAY_CODE_RANGE;

/**
 * Six digit display code, uniformly distributed over 000000..999999.
 *
 * Uses rejection sampling on 32 random bits; a plain `% 1000000` would favour
 * the lower part of the range. The code is a convenience lookup only and never
 * a security anchor (PROTOCOL.md section 5.2).
 */
export function randomDisplayCode(): string {
  for (;;) {
    const sample = randomBytes(4).readUInt32BE(0);
    if (sample < DISPLAY_CODE_REJECTION_BOUND) {
      return String(sample % DISPLAY_CODE_RANGE).padStart(DISPLAY_CODE_DIGITS, '0');
    }
  }
}

export function isDisplayCode(value: string): boolean {
  return /^[0-9]{6}$/.test(value);
}
