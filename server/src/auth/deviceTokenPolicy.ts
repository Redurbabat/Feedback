import { DEVICE_TOKEN_ROTATE_AFTER_MS } from '../constants.js';
import type { DeviceTokenRecord } from '../db/repositories/types.js';

/**
 * When a device token is still the device's, and when two parties are holding the same chain
 * (THREAT_MODEL 4.13).
 *
 * Separate from the database and from the HTTP layer because this is the whole of the rule, and
 * because the interesting cases are the ones that are awkward to produce against a live server:
 * a rotation whose answer never arrived, and a stolen copy used after the real device moved on.
 */

export type DeviceTokenDecision =
  /** The presenting party is, as far as this server can tell, the device. */
  | { readonly kind: 'accept' }
  /** Revoked, expired, or a chain that does not lead anywhere. Nothing further is implied. */
  | { readonly kind: 'reject'; readonly reason: 'revoked' | 'expired' | 'broken_chain' }
  /**
   * A superseded token used after its successor had already been used.
   *
   * The device that picked the successor up and whoever is presenting the predecessor cannot both
   * be the device. Which one is which is exactly what the server cannot tell - so the pairing ends
   * rather than the server guessing.
   */
  | { readonly kind: 'reuse_detected' };

export function decideDeviceToken(
  token: DeviceTokenRecord,
  /** The token named by `token.replacedBy`, or undefined when there is none to look up. */
  successor: DeviceTokenRecord | undefined,
  now: number,
): DeviceTokenDecision {
  if (token.revokedAt !== null) {
    return { kind: 'reject', reason: 'revoked' };
  }
  if (token.expiresAt !== null && token.expiresAt <= now) {
    return { kind: 'reject', reason: 'expired' };
  }
  if (token.replacedAt === null) {
    return { kind: 'accept' };
  }

  /*
   * A superseded token stays valid while its successor is untouched, and there is deliberately no
   * time limit on that. The answer carrying a new token can be lost - on a dropped connection, on
   * a process that dies between the response and the write to storage - and a device that never
   * saw the new token has nothing else to present. A grace period measured in minutes or days
   * would lock out exactly the device it is supposed to protect, at exactly the wrong moment.
   *
   * What ends the predecessor is not time but evidence: the successor being used means somebody
   * did receive it.
   */
  if (successor === undefined) {
    return { kind: 'reject', reason: 'broken_chain' };
  }
  if (successor.revokedAt !== null) {
    return { kind: 'reject', reason: 'revoked' };
  }
  if (successor.lastUsedAt !== null) {
    return { kind: 'reuse_detected' };
  }
  return { kind: 'accept' };
}

/**
 * Whether the next connection should replace this token.
 *
 * Asked on the server rather than on the device, so the answer does not depend on a phone's
 * clock - one that is wrong by a week would otherwise either rotate on every reconnect or never
 * rotate at all.
 */
export function rotationDue(token: DeviceTokenRecord, now: number): boolean {
  return now - token.createdAt >= DEVICE_TOKEN_ROTATE_AFTER_MS;
}
