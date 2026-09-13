import { createHash, createPublicKey, verify as cryptoVerify } from 'node:crypto';
import type { KeyObject } from 'node:crypto';

import {
  CANONICAL_PAIRING_CLAIM_HEADER,
  CANONICAL_PAIRING_START_HEADER,
  PUBLIC_KEY_MAX_BASE64,
} from '../constants.js';
import { invalidMessage } from '../errors.js';
import { decodeBase64Strict, decodeBase64UrlStrict } from './tokens.js';

/**
 * Device identity derivation and signature verification.
 *
 * Implements PROTOCOL.md sections 3 and 4 exactly:
 *   fingerprintHex = hex(sha256(spkiDer))
 *   fingerprint    = fingerprintHex in groups of four, separated by ":"
 *   deviceId       = "fb-" + fingerprintHex[0..23]
 *
 * The server never sees or stores private key material.
 */

const P256_CURVE_NAMES = new Set(['prime256v1', 'secp256r1', 'P-256']);

export const DEVICE_ID_PATTERN = /^fb-[0-9a-f]{24}$/;
export const FINGERPRINT_PATTERN = /^[0-9a-f]{4}(?::[0-9a-f]{4}){15}$/;

export interface DeviceIdentity {
  readonly key: KeyObject;
  readonly spkiDer: Buffer;
  readonly deviceId: string;
  readonly fingerprint: string;
}

/**
 * Parses a base64 encoded X.509/SubjectPublicKeyInfo DER blob.
 * Accepts EC P-256 keys only; anything else throws INVALID_MESSAGE.
 */
export function parseSpki(publicKeyBase64: string): KeyObject {
  if (publicKeyBase64.length === 0 || publicKeyBase64.length > PUBLIC_KEY_MAX_BASE64) {
    throw invalidMessage('Public Key hat eine unzulaessige Laenge');
  }
  const der = decodeBase64Strict(publicKeyBase64);
  if (der === undefined) {
    throw invalidMessage('Public Key ist kein gueltiges Base64');
  }

  let key: KeyObject;
  try {
    key = createPublicKey({ key: der, format: 'der', type: 'spki' });
  } catch (cause) {
    throw invalidMessage('Public Key ist kein gueltiger SPKI-Schluessel', { cause });
  }

  if (key.asymmetricKeyType !== 'ec') {
    throw invalidMessage('Public Key ist kein EC-Schluessel');
  }
  const namedCurve = key.asymmetricKeyDetails?.namedCurve;
  if (namedCurve === undefined || !P256_CURVE_NAMES.has(namedCurve)) {
    throw invalidMessage('Public Key verwendet nicht die Kurve P-256');
  }
  return key;
}

/** Re-encodes a parsed key to its canonical SPKI DER representation. */
export function spkiDerOf(key: KeyObject): Buffer {
  return key.export({ format: 'der', type: 'spki' });
}

function fingerprintHex(spkiDer: Uint8Array): string {
  return createHash('sha256').update(spkiDer).digest('hex');
}

/** deviceId = "fb-" + first 24 hex characters of sha256(spkiDer). */
export function deriveDeviceId(spkiDer: Uint8Array): string {
  return `fb-${fingerprintHex(spkiDer).slice(0, 24)}`;
}

/** fingerprint = sha256(spkiDer) hex in 16 groups of four, joined with ":". */
export function formatFingerprint(spkiDer: Uint8Array): string {
  const hex = fingerprintHex(spkiDer);
  const groups: string[] = [];
  for (let index = 0; index < hex.length; index += 4) {
    groups.push(hex.slice(index, index + 4));
  }
  return groups.join(':');
}

/**
 * Parses the key and checks that the claimed deviceId and fingerprint are
 * actually derived from it (PROTOCOL.md section 3, mandatory checks 1-3).
 */
export function parseAndVerifyIdentity(input: {
  publicKeyBase64: string;
  deviceId: string;
  fingerprint: string;
}): DeviceIdentity {
  const key = parseSpki(input.publicKeyBase64);
  const spkiDer = spkiDerOf(key);
  const deviceId = deriveDeviceId(spkiDer);
  const fingerprint = formatFingerprint(spkiDer);

  if (input.deviceId !== deviceId) {
    throw invalidMessage('deviceId ist nicht aus dem Public Key abgeleitet');
  }
  if (input.fingerprint !== fingerprint) {
    throw invalidMessage('fingerprint ist nicht aus dem Public Key abgeleitet');
  }
  return { key, spkiDer, deviceId, fingerprint };
}

/**
 * Verifies an ECDSA (SHA-256, ASN.1/DER) signature over a canonical payload.
 * Returns false for malformed signatures instead of throwing.
 */
export function verifySignature(
  canonicalPayload: string,
  signatureBase64Url: string,
  key: KeyObject,
): boolean {
  const signature = decodeBase64UrlStrict(signatureBase64Url);
  if (signature === undefined) {
    return false;
  }
  try {
    return cryptoVerify(
      'sha256',
      Buffer.from(canonicalPayload, 'utf8'),
      { key, dsaEncoding: 'der' },
      signature,
    );
  } catch {
    return false;
  }
}

/**
 * Joins canonical payload lines with "\n" and without a trailing newline.
 * A field containing "\n" is rejected with INVALID_MESSAGE (section 4).
 */
function canonical(lines: readonly string[]): string {
  for (const line of lines) {
    if (line.includes('\n')) {
      throw invalidMessage('Signaturfeld enthaelt einen Zeilenumbruch');
    }
  }
  return lines.join('\n');
}

export interface PairingStartCanonicalInput {
  readonly deviceId: string;
  readonly publicKeyBase64: string;
  readonly fingerprint: string;
  readonly deviceName: string;
  readonly platform: string;
  readonly osVersion: string;
  readonly sdkInt: number;
  readonly appVersion: string;
  readonly nonce: string;
  readonly issuedAt: number;
}

/** Canonical payload `feedback-pairing-start-v1` (PROTOCOL.md section 4.1). */
export function canonicalPairingStart(input: PairingStartCanonicalInput): string {
  return canonical([
    CANONICAL_PAIRING_START_HEADER,
    input.deviceId,
    input.publicKeyBase64,
    input.fingerprint,
    input.deviceName,
    input.platform,
    input.osVersion,
    String(input.sdkInt),
    input.appVersion,
    input.nonce,
    String(input.issuedAt),
  ]);
}

export interface PairingClaimCanonicalInput {
  readonly pairingId: string;
  readonly deviceId: string;
  readonly deviceSecret: string;
  readonly issuedAt: number;
}

/** Canonical payload `feedback-pairing-claim-v1` (PROTOCOL.md section 4.2). */
export function canonicalPairingClaim(input: PairingClaimCanonicalInput): string {
  return canonical([
    CANONICAL_PAIRING_CLAIM_HEADER,
    input.pairingId,
    input.deviceId,
    input.deviceSecret,
    String(input.issuedAt),
  ]);
}
