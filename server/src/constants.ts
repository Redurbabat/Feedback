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

/**
 * How long a Control Center session stays elevated after confirming the password.
 *
 * Short on purpose. It is the window in which granting a capability needs no further proof, so it
 * is also the window a stolen session would have to land in (THREAT_MODEL 4.5).
 */
export const CONTROL_ELEVATION_TTL_MS = 300_000;
/**
 * How long a device token stays valid without being rotated (THREAT_MODEL 4.13).
 *
 * Every rotation issues a fresh one with a fresh window, so a device that connects keeps working
 * indefinitely. The limit bites only for a device that has not been seen for three months - and
 * that is the case where a token still lying around somewhere should stop working.
 */
export const DEVICE_TOKEN_TTL_MS = 7_776_000_000;

/**
 * How old a device token may get before the next connection replaces it.
 *
 * Shorter than the lifetime by a wide margin, so a device that connects even occasionally always
 * rotates long before its token could expire. This is also the upper bound on how long a stolen
 * copy stays useful once the real device connects again.
 */
export const DEVICE_TOKEN_ROTATE_AFTER_MS = 604_800_000;

export const NONCE_RETENTION_MS = 900_000;
export const REMOTE_SESSION_TTL_MS = 60_000;
export const AGENT_REQUEST_TIMEOUT_MS = 10_000;
export const HEARTBEAT_INTERVAL_MS = 30_000;
export const HEARTBEAT_MISS_LIMIT = 3;
export const MAX_FRAME_BYTES = 65_536;
export const MAX_JSON_BODY_BYTES = 16_384;
export const DEVICE_NAME_MAX = 64;
export const PUBLIC_KEY_MAX_BASE64 = 512;

/**
 * `files.read` limits from section 11.
 *
 * These are declared here even though the server does not serve files yet: the
 * protocol fixes them, the device already enforces them, and a constant that is
 * only written down on one side is exactly how the two drift apart.
 * `tools/validators/check-protocol-constants.mjs` compares all three.
 */
export const FILES_SESSION_TTL_MS = 300_000;
export const FILE_CHUNK_BYTES = 32_768;
export const FILE_TRANSFER_WINDOW = 4;
export const FILE_MAX_CONCURRENT_TRANSFERS = 2;
export const FILE_TRANSFER_IDLE_TIMEOUT_MS = 30_000;
/** Upper bound. Configurable downwards per deployment; the device enforces its own limit too. */
export const FILE_MAX_DOWNLOAD_BYTES = 268_435_456;
export const FILE_MAX_LIST_ENTRIES = 200;
export const FILE_NAME_MAX = 255;

/**
 * `screen.view` limits from section 11.
 *
 * The window is deliberately smaller than `FILE_TRANSFER_WINDOW`: for a file a large
 * window costs memory, for a live picture it costs delay. A viewer that is three
 * frames behind is still watching what is happening; one that is thirty frames
 * behind is watching the past.
 */
export const SCREEN_SESSION_TTL_MS = 600_000;
export const SCREEN_CONSENT_TIMEOUT_MS = 60_000;
export const SCREEN_CHUNK_BYTES = 32_768;
export const SCREEN_FRAME_WINDOW = 3;
export const SCREEN_MAX_FRAME_BYTES = 1_048_576;
export const SCREEN_MAX_CONCURRENT_STREAMS = 1;
export const SCREEN_STREAM_IDLE_TIMEOUT_MS = 15_000;
export const SCREEN_MAX_DIMENSION = 1_280;
export const SCREEN_MAX_FPS = 15;
export const SCREEN_MAX_BITRATE_KBPS = 2_500;

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
 *
 * This list is the single switch that makes a capability live: `grantedCapabilities`
 * filters server side grants against it, so a capability missing here is never
 * reported as granted no matter what the owner set in the control center.
 */
export const IMPLEMENTED_CAPABILITIES_V1: readonly CapabilityV1[] = [
  'system.info',
  'files.read',
  'media.photos.read',
  'media.videos.read',
  'screen.view',
];

/**
 * The capabilities that can govern a shared area (protocol section 8.3.1).
 *
 * Listed explicitly rather than derived from "everything implemented", so adding a
 * new implemented capability does not silently make it a way to read shares.
 */
export const SHARE_CAPABILITIES_V1: readonly CapabilityV1[] = [
  'files.read',
  'media.photos.read',
  'media.videos.read',
];

export function isCapabilityV1(value: string): value is CapabilityV1 {
  return (CAPABILITIES_V1 as readonly string[]).includes(value);
}

/** Canonical signature payload prefixes (section 4). */
export const CANONICAL_PAIRING_START_HEADER = 'feedback-pairing-start-v1';
export const CANONICAL_PAIRING_CLAIM_HEADER = 'feedback-pairing-claim-v1';
export const CANONICAL_SERVER_IDENTITY_HEADER = 'feedback-server-identity-v1';
