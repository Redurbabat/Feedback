import { ApiError } from './api.ts';

/**
 * The second step in front of a capability grant, on the browser side (THREAT_MODEL 4.5).
 *
 * The server refuses a grant from a session that has not confirmed the password recently and
 * answers REAUTH_REQUIRED. What the Control Center does with that answer is decided here, away
 * from the DOM, because two of the rules are easy to get wrong in a click handler and neither
 * failure would be visible in the UI:
 *
 *  - the retry must carry the exact set that was refused, not a set derived again from a device
 *    view that may have been refreshed in the meantime;
 *  - a refusal on a *revocation* must never open a password prompt. Revoking is the direction
 *    that stays cheap on purpose, and a prompt there would teach the owner to type their password
 *    while closing something down - the habit this whole mechanism exists to protect.
 */

/** A capability change that was refused and can be repeated unchanged after the confirmation. */
export interface PendingGrant {
  readonly deviceId: string;
  readonly deviceName: string;
  /** The capability the owner just switched on, for the sentence above the password field. */
  readonly capability: string;
  /** The complete set the server refused, repeated verbatim on the retry. */
  readonly grantedCapabilities: readonly string[];
}

export type CapabilityRefusal =
  | { readonly kind: 'ask_for_password'; readonly pending: PendingGrant }
  | { readonly kind: 'report' };

export interface CapabilityAttempt {
  readonly deviceId: string;
  readonly deviceName: string;
  readonly capability: string;
  /** False when the change only takes something away. */
  readonly grants: boolean;
  readonly grantedCapabilities: readonly string[];
}

export function refusalFor(error: unknown, attempt: CapabilityAttempt): CapabilityRefusal {
  if (!(error instanceof ApiError) || error.code !== 'REAUTH_REQUIRED') {
    return { kind: 'report' };
  }
  if (!attempt.grants) {
    return { kind: 'report' };
  }
  return {
    kind: 'ask_for_password',
    pending: {
      deviceId: attempt.deviceId,
      deviceName: attempt.deviceName,
      capability: attempt.capability,
      grantedCapabilities: [...attempt.grantedCapabilities],
    },
  };
}

/**
 * What the Control Center does when the confirmation itself was refused.
 *
 * The two answers need opposite reactions and both arrive as an error, so they are told apart
 * here rather than in the click handler: a wrong password leaves the prompt standing, an expired
 * session sends the owner back to the login screen. Getting this backwards would log somebody out
 * over a typo - or, worse, keep asking for a password that no session is left to accept.
 */
export type ConfirmationRefusal = 'wrong_password' | 'report';

export function confirmationRefusalFor(error: unknown): ConfirmationRefusal {
  if (error instanceof ApiError && error.code === 'REAUTH_REQUIRED') {
    return 'wrong_password';
  }
  return 'report';
}

/**
 * The set to send for a single toggle.
 *
 * The endpoint replaces the whole set, so switching one capability on must not drop another that
 * happens to be granted.
 */
export function nextCapabilities(
  current: readonly string[],
  capability: string,
  enabled: boolean,
): string[] {
  const next = new Set(current);
  if (enabled) {
    next.add(capability);
  } else {
    next.delete(capability);
  }
  return [...next];
}
