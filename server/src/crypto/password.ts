import { randomBytes, scrypt as scryptCallback, timingSafeEqual } from 'node:crypto';
import type { ScryptOptions } from 'node:crypto';

function scrypt(
  password: string,
  salt: Buffer,
  keyLength: number,
  options: ScryptOptions,
): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    scryptCallback(password, salt, keyLength, options, (error, derivedKey) => {
      if (error !== null) {
        reject(error);
        return;
      }
      resolve(derivedKey);
    });
  });
}

/**
 * Password hashing with scrypt (node:crypto, no external dependency).
 *
 * Stored format (all parameters are part of the record so they can be raised
 * later without invalidating existing hashes):
 *
 *   scrypt$N=<cost>,r=<blockSize>,p=<parallelism>$<saltBase64Url>$<hashBase64Url>
 */

export interface ScryptParameters {
  readonly cost: number;
  readonly blockSize: number;
  readonly parallelism: number;
  readonly keyLength: number;
  readonly saltBytes: number;
}

/**
 * N = 2^15 / r = 8 / p = 1 is the current default. Memory use is
 * 128 * N * r bytes (~32 MiB) per hash operation.
 */
export const DEFAULT_SCRYPT_PARAMETERS: ScryptParameters = {
  cost: 32_768,
  blockSize: 8,
  parallelism: 1,
  keyLength: 32,
  saltBytes: 16,
};

const ALGORITHM_LABEL = 'scrypt';

function maxmemFor(parameters: Pick<ScryptParameters, 'cost' | 'blockSize' | 'parallelism'>): number {
  // node's default maxmem (32 MiB) is too small for N = 2^15, so it is derived
  // from the parameters with a safety factor of two.
  return 256 * parameters.cost * parameters.blockSize * Math.max(parameters.parallelism, 1);
}

async function deriveKey(
  password: string,
  salt: Buffer,
  parameters: ScryptParameters,
): Promise<Buffer> {
  return scrypt(password.normalize('NFKC'), salt, parameters.keyLength, {
    N: parameters.cost,
    r: parameters.blockSize,
    p: parameters.parallelism,
    maxmem: maxmemFor(parameters),
  });
}

/** Creates a new password record. The plaintext never leaves this function. */
export async function hashPassword(
  password: string,
  parameters: ScryptParameters = DEFAULT_SCRYPT_PARAMETERS,
): Promise<string> {
  if (password.length < 12) {
    throw new Error('Passwort muss mindestens 12 Zeichen lang sein');
  }
  const salt = randomBytes(parameters.saltBytes);
  const derived = await deriveKey(password, salt, parameters);
  return [
    ALGORITHM_LABEL,
    `N=${parameters.cost},r=${parameters.blockSize},p=${parameters.parallelism}`,
    salt.toString('base64url'),
    derived.toString('base64url'),
  ].join('$');
}

interface ParsedRecord {
  readonly parameters: ScryptParameters;
  readonly salt: Buffer;
  readonly hash: Buffer;
}

function parseRecord(record: string): ParsedRecord | undefined {
  const parts = record.split('$');
  if (parts.length !== 4) {
    return undefined;
  }
  const [label, parameterPart, saltPart, hashPart] = parts as [string, string, string, string];
  if (label !== ALGORITHM_LABEL) {
    return undefined;
  }
  const match = /^N=(\d+),r=(\d+),p=(\d+)$/.exec(parameterPart);
  if (match === null) {
    return undefined;
  }
  const cost = Number(match[1]);
  const blockSize = Number(match[2]);
  const parallelism = Number(match[3]);
  if (!Number.isInteger(cost) || cost < 1024 || (cost & (cost - 1)) !== 0) {
    return undefined;
  }
  if (!Number.isInteger(blockSize) || blockSize < 1 || blockSize > 64) {
    return undefined;
  }
  if (!Number.isInteger(parallelism) || parallelism < 1 || parallelism > 16) {
    return undefined;
  }
  const salt = Buffer.from(saltPart, 'base64url');
  const hash = Buffer.from(hashPart, 'base64url');
  if (salt.length === 0 || hash.length === 0) {
    return undefined;
  }
  return {
    parameters: {
      cost,
      blockSize,
      parallelism,
      keyLength: hash.length,
      saltBytes: salt.length,
    },
    salt,
    hash,
  };
}

/**
 * Verifies a password against a stored record in constant time with respect to
 * the hash content. An unparseable record verifies to false, it never throws.
 */
export async function verifyPassword(password: string, record: string): Promise<boolean> {
  const parsed = parseRecord(record);
  if (parsed === undefined) {
    return false;
  }
  let derived: Buffer;
  try {
    derived = await deriveKey(password, parsed.salt, parsed.parameters);
  } catch {
    return false;
  }
  if (derived.length !== parsed.hash.length) {
    return false;
  }
  return timingSafeEqual(derived, parsed.hash);
}

/**
 * Burns roughly the same amount of work as {@link verifyPassword} without
 * having a record. Used on unknown accounts so that login timing does not
 * disclose whether an e-mail address exists.
 */
export async function burnPasswordWork(
  password: string,
  parameters: ScryptParameters = DEFAULT_SCRYPT_PARAMETERS,
): Promise<void> {
  const salt = randomBytes(parameters.saltBytes);
  await deriveKey(password, salt, parameters);
}
