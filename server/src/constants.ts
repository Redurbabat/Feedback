/**
 * Protocol constants.
 *
 * Single source of truth is `protocol/PROTOCOL.md` (v1). Every value here maps
 * 1:1 to section 11 "Limits" of that document. Changing a value here without
 * changing the protocol document is a bug.
 */

export const PROTOCOL_VERSION = 1 as const;

export const PAIRING_TTL_MS = 300_000;
export const PAIRING_MAX_LOOKUP_ATTEMPTS = 5;
export const CLOCK_SKEW_MS = 120_000;
export const NONCE_RETENTION_MS = 900_000;
export const REMOTE_SESSION_TTL_MS = 60_000;
export const HEARTBEAT_INTERVAL_MS = 30_000;
export const HEARTBEAT_MISS_LIMIT = 3;
export const MAX_FRAME_BYTES = 65_536;
export const MAX_JSON_BODY_BYTES = 16_384;
export const DEVICE_NAME_MAX = 64;
export const PUBLIC_KEY_MAX_BASE64 = 512;

/** Poll interval suggested to the device while a pairing session is pending. */
export const PAIRING_POLL_INTERVAL_MS = 2_000;

/** Length in bytes of ticket / deviceSecret / deviceToken material. */
export const SECRET_BYTES = 32;

/** Capability catalogue of protocol v1 (section 8.1). */
export const CAPABILITIES_V1 = [
  'system.info',
  'files.read',
  'media.photos.read',
  'media.videos.read',
  'screen.view',
  'screen.control',
  'clipboard.read',
  'clipboard.write',
] as const;

export type CapabilityV1 = (typeof CAPABILITIES_V1)[number];

/**
 * Capabilities that actually have an implementation behind them in v1.
 * Everything else is declared, deny-by-default and answers UNSUPPORTED.
 */
export const IMPLEMENTED_CAPABILITIES_V1: readonly CapabilityV1[] = ['system.info'];

export function isCapabilityV1(value: string): value is CapabilityV1 {
  return (CAPABILITIES_V1 as readonly string[]).includes(value);
}

/** Canonical signature payload prefixes (section 4). */
export const CANONICAL_PAIRING_START_HEADER = 'feedback-pairing-start-v1';
export const CANONICAL_PAIRING_CLAIM_HEADER = 'feedback-pairing-claim-v1';
