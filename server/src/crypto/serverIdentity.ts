import {
  createPrivateKey,
  createPublicKey,
  generateKeyPairSync,
  sign as cryptoSign,
} from 'node:crypto';
import type { KeyObject } from 'node:crypto';
import { chmodSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

import { CANONICAL_SERVER_IDENTITY_HEADER } from '../constants.js';

/**
 * The server's own identity, so that "which server is this" stops being answered by an address.
 *
 * Until this existed the device proved itself and the server proved nothing: the deviceToken went
 * out as a Bearer header in the WebSocket upgrade, before a single byte came back. Whoever
 * inherited the address inherited the trust with it - the attacker who slips a setup link in
 * front of the owner (THREAT_MODEL 4.20), and the next registrant of a domain that lapsed (4.21).
 * A key seen at pairing cannot be inherited.
 *
 * P-256 and ECDSA over SHA-256 with DER signatures, exactly like the device side, so there is one
 * signature scheme in this protocol and not two.
 *
 * The private key lives in its own file and deliberately not in the database: `drizzle/0001_init.sql`
 * states that secrets are stored exclusively as SHA-256 digests, and a raw private key would make
 * that sentence false. The cost is that a database backup alone does not restore the identity -
 * and a restored server that has lost its key looks to every paired device exactly like a
 * substituted one, which is the whole point. `BETRIEB.md` says to back up both.
 */

export interface ServerIdentity {
  /** SPKI DER, base64 - the same encoding a device uses for its own public key. */
  readonly publicKeyBase64: string;

  /** ECDSA over SHA-256, DER, base64url - the encoding `verifySignature` expects. */
  sign(canonicalPayload: string): string;
}

/**
 * The payload the server signs to prove it is the server the device paired with.
 *
 * `deviceId` binds the proof to the device that asked, so an answer collected for one device
 * cannot be replayed at another. `nonce` is chosen by the device and makes it unreplayable at the
 * same one. The public key is part of the payload so that a signature can never be read as being
 * about a different key than the one it came with.
 */
export function serverIdentityPayload(input: {
  deviceId: string;
  nonceBase64Url: string;
  publicKeyBase64: string;
  issuedAtEpochMillis: number;
}): string {
  return [
    CANONICAL_SERVER_IDENTITY_HEADER,
    input.deviceId,
    input.nonceBase64Url,
    input.publicKeyBase64,
    String(input.issuedAtEpochMillis),
  ].join('\n');
}

function publicKeyBase64Of(privateKey: KeyObject): string {
  const spki = createPublicKey(privateKey).export({ format: 'der', type: 'spki' });
  return Buffer.from(spki).toString('base64');
}

function identityFrom(privateKey: KeyObject): ServerIdentity {
  const publicKeyBase64 = publicKeyBase64Of(privateKey);
  return {
    publicKeyBase64,
    sign(canonicalPayload: string): string {
      const signature = cryptoSign(
        'sha256',
        Buffer.from(canonicalPayload, 'utf8'),
        { key: privateKey, dsaEncoding: 'der' },
      );
      return signature.toString('base64url');
    },
  };
}

/**
 * Reads the key file, or creates it on first start.
 *
 * Created with `wx` so that two starts racing cannot both write: the loser reads what the winner
 * wrote instead of quietly replacing it, which would give the server a second identity and lock
 * out every device paired with the first.
 */
export function loadOrCreateServerIdentity(keyFile: string): ServerIdentity {
  const existing = readKeyFile(keyFile);
  if (existing !== undefined) {
    return identityFrom(existing);
  }

  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const pem = privateKey.export({ format: 'pem', type: 'pkcs8' }).toString();
  mkdirSync(dirname(keyFile), { recursive: true });
  try {
    writeFileSync(keyFile, pem, { encoding: 'utf8', flag: 'wx', mode: 0o600 });
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'EEXIST') {
      throw error;
    }
    const raced = readKeyFile(keyFile);
    if (raced === undefined) {
      throw error;
    }
    return identityFrom(raced);
  }
  // `mode` only applies when the file is created; a restrictive umask cannot widen it, but an
  // inherited permissive one could have been narrowed further, so this is asserted rather than
  // assumed.
  chmodSync(keyFile, 0o600);
  return identityFrom(privateKey);
}

function readKeyFile(keyFile: string): KeyObject | undefined {
  let pem: string;
  try {
    pem = readFileSync(keyFile, 'utf8');
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') {
      return undefined;
    }
    throw error;
  }
  // A key file that exists but cannot be read is never replaced. Generating a new one here would
  // silently change the server's identity and lock out every paired device; failing to start is
  // the answer the owner can act on.
  return createPrivateKey({ key: pem, format: 'pem', type: 'pkcs8' });
}
