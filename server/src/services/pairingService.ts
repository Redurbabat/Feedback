import {
  CLOCK_SKEW_MS,
  IMPLEMENTED_CAPABILITIES_V1,
  NONCE_RETENTION_MS,
  PAIRING_MAX_LOOKUP_ATTEMPTS,
  PAIRING_POLL_INTERVAL_MS,
  PAIRING_TTL_MS,
  SECRET_BYTES,
} from '../constants.js';
import {
  canonicalPairingClaim,
  canonicalPairingStart,
  parseAndVerifyIdentity,
  parseSpki,
  verifySignature,
} from '../crypto/deviceIdentity.js';
import {
  randomDisplayCode,
  randomToken,
  sha256Hex,
  timingSafeEqualString,
  verifyHashedSecret,
} from '../crypto/tokens.js';
import type {
  PairingSessionRecord,
  PairingStatus,
  Repositories,
} from '../db/repositories/types.js';
import { ProtocolError, invalidMessage } from '../errors.js';
import type { AuditLogger } from './audit.js';
import type { Clock } from './clock.js';

/**
 * Pairing according to PROTOCOL.md sections 5 and 6.
 *
 * Security properties enforced here:
 *  - signature, deviceId and fingerprint are verified against the public key,
 *  - issuedAt has to lie within CLOCK_SKEW_MS of server time,
 *  - the start nonce is single use within NONCE_RETENTION_MS,
 *  - ticket, deviceSecret and display code are persisted as SHA-256 only,
 *  - approve/reject need the same proof as lookup; a pairingId alone is not
 *    an authorisation,
 *  - claim is possible exactly once,
 *  - failed lookups are indistinguishable between "unknown" and "expired".
 */

export interface DeviceMetadataInput {
  readonly deviceId: string;
  readonly publicKey: string;
  readonly fingerprint: string;
  readonly deviceName: string;
  readonly platform: string;
  readonly osVersion: string;
  readonly sdkInt: number;
  readonly appVersion: string;
}

export interface StartPairingInput {
  readonly device: DeviceMetadataInput;
  readonly nonce: string;
  readonly issuedAt: number;
  readonly signature: string;
}

export interface StartPairingResult {
  readonly pairingId: string;
  readonly ticket: string;
  readonly deviceSecret: string;
  readonly displayCode: string;
  readonly qrPayload: string;
  readonly expiresAt: string;
  readonly pollIntervalMs: number;
}

export interface PairingProof {
  readonly ticket?: string | undefined;
  readonly displayCode?: string | undefined;
}

export interface PairingSummary {
  readonly pairingId: string;
  readonly status: PairingStatus;
  readonly device: {
    readonly deviceId: string;
    readonly fingerprint: string;
    readonly deviceName: string;
    readonly platform: string;
    readonly osVersion: string;
    readonly sdkInt: number | null;
    readonly appVersion: string;
  };
  readonly knownDevice: boolean;
  readonly createdAt: string;
  readonly expiresAt: string;
}

export interface PairingStatusResult {
  readonly status: PairingStatus;
  readonly expiresAt: string;
}

export interface ClaimPairingResult {
  readonly deviceToken: string;
  readonly device: {
    readonly id: string;
    readonly deviceId: string;
    readonly name: string;
    readonly pairedAt: string;
  };
  readonly capabilities: {
    readonly granted: readonly string[];
    readonly requested: readonly string[];
  };
  readonly serverTime: string;
}

interface StoredMetadata {
  readonly deviceName: string;
  readonly platform: string;
  readonly osVersion: string;
  readonly sdkInt: number | null;
  readonly appVersion: string;
}

export interface PairingServiceDeps {
  readonly repositories: Repositories;
  readonly clock: Clock;
  readonly audit: AuditLogger;
}

/** Deliberately identical answer for unknown, expired and burnt-out sessions. */
function pairingNotFound(): ProtocolError {
  return new ProtocolError('NOT_FOUND', 'Kopplung nicht gefunden oder nicht mehr gueltig');
}

function toIso(epochMillis: number): string {
  return new Date(epochMillis).toISOString();
}

function parseMetadata(raw: string): StoredMetadata {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) {
      throw new Error('metadata is not an object');
    }
    const record = parsed as Record<string, unknown>;
    return {
      deviceName: typeof record['deviceName'] === 'string' ? record['deviceName'] : '',
      platform: typeof record['platform'] === 'string' ? record['platform'] : '',
      osVersion: typeof record['osVersion'] === 'string' ? record['osVersion'] : '',
      sdkInt: typeof record['sdkInt'] === 'number' ? record['sdkInt'] : null,
      appVersion: typeof record['appVersion'] === 'string' ? record['appVersion'] : '',
    };
  } catch {
    return { deviceName: '', platform: '', osVersion: '', sdkInt: null, appVersion: '' };
  }
}

export class PairingService {
  constructor(private readonly deps: PairingServiceDeps) {}

  /** POST /api/v1/pairing/start */
  async start(input: StartPairingInput): Promise<StartPairingResult> {
    const now = this.deps.clock.now();
    const { device } = input;

    // 1. Key, deviceId and fingerprint must belong together.
    const identity = parseAndVerifyIdentity({
      publicKeyBase64: device.publicKey,
      deviceId: device.deviceId,
      fingerprint: device.fingerprint,
    });

    // 2. Signature over the canonical payload of section 4.1.
    const canonical = canonicalPairingStart({
      deviceId: device.deviceId,
      publicKeyBase64: device.publicKey,
      fingerprint: device.fingerprint,
      deviceName: device.deviceName,
      platform: device.platform,
      osVersion: device.osVersion,
      sdkInt: device.sdkInt,
      appVersion: device.appVersion,
      nonce: input.nonce,
      issuedAt: input.issuedAt,
    });
    if (!verifySignature(canonical, input.signature, identity.key)) {
      await this.deps.audit.record({
        eventType: 'pairing.start',
        result: 'failure',
        deviceId: device.deviceId,
        detail: { reason: 'signature_invalid' },
      });
      throw invalidMessage('Signatur ist ungueltig');
    }

    // 3. Freshness window.
    if (Math.abs(now - input.issuedAt) > CLOCK_SKEW_MS) {
      await this.deps.audit.record({
        eventType: 'pairing.start',
        result: 'failure',
        deviceId: device.deviceId,
        detail: { reason: 'issued_at_out_of_window' },
      });
      throw invalidMessage('issuedAt liegt ausserhalb des erlaubten Zeitfensters');
    }

    // 4. Replay protection.
    await this.deps.repositories.pairingNonces.deleteExpired(now);
    const nonceStored = await this.deps.repositories.pairingNonces.tryInsert(
      device.deviceId,
      sha256Hex(input.nonce),
      now + NONCE_RETENTION_MS,
    );
    if (!nonceStored) {
      await this.deps.audit.record({
        eventType: 'pairing.start',
        result: 'failure',
        deviceId: device.deviceId,
        detail: { reason: 'nonce_replay' },
      });
      throw invalidMessage('Nonce wurde bereits verwendet');
    }

    // 5. A known device must keep its public key (PROTOCOL.md 5.4).
    const existing = await this.deps.repositories.devices.findByDeviceId(device.deviceId);
    if (existing !== undefined && existing.publicKey !== device.publicKey) {
      await this.deps.audit.record({
        eventType: 'pairing.start',
        result: 'denied',
        deviceId: device.deviceId,
        detail: { reason: 'public_key_mismatch' },
      });
      throw invalidMessage('Public Key weicht von der bestehenden Registrierung ab');
    }

    // 6. Create the session. Secrets are returned once and stored hashed only.
    await this.deps.repositories.pairingSessions.expireStale(now);
    const ticket = randomToken(SECRET_BYTES);
    const deviceSecret = randomToken(SECRET_BYTES);
    const displayCode = await this.pickUnusedDisplayCode(now);

    const metadata: StoredMetadata = {
      deviceName: device.deviceName,
      platform: device.platform,
      osVersion: device.osVersion,
      sdkInt: device.sdkInt,
      appVersion: device.appVersion,
    };

    const session = await this.deps.repositories.pairingSessions.create({
      ticketHash: sha256Hex(ticket),
      displayCodeHash: sha256Hex(displayCode),
      deviceId: device.deviceId,
      publicKey: device.publicKey,
      fingerprint: device.fingerprint,
      metadata: JSON.stringify(metadata),
      deviceSecretHash: sha256Hex(deviceSecret),
      expiresAt: now + PAIRING_TTL_MS,
    });

    await this.deps.audit.record({
      eventType: 'pairing.start',
      result: 'success',
      deviceId: device.deviceId,
      sessionId: session.id,
      detail: { knownDevice: existing !== undefined },
    });

    return {
      pairingId: session.id,
      ticket,
      deviceSecret,
      displayCode,
      qrPayload: `feedback://pair?v=1&ticket=${ticket}`,
      expiresAt: toIso(session.expiresAt),
      pollIntervalMs: PAIRING_POLL_INTERVAL_MS,
    };
  }

  /** POST /api/v1/pairing/lookup - Control Center resolves a pending pairing. */
  async lookup(proof: PairingProof, actor: { userId: string }): Promise<PairingSummary> {
    const now = this.deps.clock.now();
    const session = await this.resolveByProof(proof, now);

    if (this.effectiveStatus(session, now) !== 'pending') {
      await this.deps.audit.record({
        eventType: 'pairing.lookup',
        result: 'failure',
        userId: actor.userId,
        sessionId: session.id,
        detail: { reason: 'not_pending' },
      });
      throw pairingNotFound();
    }

    const metadata = parseMetadata(session.metadata);
    const known = await this.deps.repositories.devices.findByDeviceId(session.deviceId);

    await this.deps.audit.record({
      eventType: 'pairing.lookup',
      result: 'success',
      userId: actor.userId,
      deviceId: session.deviceId,
      sessionId: session.id,
      detail: { proof: proof.ticket !== undefined ? 'ticket' : 'display_code' },
    });

    return {
      pairingId: session.id,
      status: 'pending',
      device: {
        deviceId: session.deviceId,
        fingerprint: session.fingerprint,
        deviceName: metadata.deviceName,
        platform: metadata.platform,
        osVersion: metadata.osVersion,
        sdkInt: metadata.sdkInt,
        appVersion: metadata.appVersion,
      },
      knownDevice: known !== undefined,
      createdAt: toIso(session.createdAt),
      expiresAt: toIso(session.expiresAt),
    };
  }

  /** POST /api/v1/pairing/{id}/approve */
  async approve(
    pairingId: string,
    proof: PairingProof,
    actor: { userId: string },
  ): Promise<PairingSummary> {
    return this.decide(pairingId, proof, actor, 'approve');
  }

  /** POST /api/v1/pairing/{id}/reject */
  async reject(
    pairingId: string,
    proof: PairingProof,
    actor: { userId: string },
  ): Promise<PairingSummary> {
    return this.decide(pairingId, proof, actor, 'reject');
  }

  /** POST /api/v1/pairing/{id}/status - polled by the device. */
  async status(pairingId: string, deviceSecret: string): Promise<PairingStatusResult> {
    const now = this.deps.clock.now();
    const session = await this.requireSessionWithSecret(pairingId, deviceSecret);
    return {
      status: this.effectiveStatus(session, now),
      expiresAt: toIso(session.expiresAt),
    };
  }

  /** POST /api/v1/pairing/{id}/claim - single use device token handout. */
  async claim(
    pairingId: string,
    input: { deviceSecret: string; issuedAt: number; signature: string },
  ): Promise<ClaimPairingResult> {
    const now = this.deps.clock.now();
    const session = await this.requireSessionWithSecret(pairingId, input.deviceSecret);
    const status = this.effectiveStatus(session, now);

    if (status === 'expired') {
      await this.deps.repositories.pairingSessions.markExpired(session.id);
      throw new ProtocolError('PAIRING_EXPIRED', 'Kopplung ist abgelaufen');
    }
    if (status === 'consumed' || status === 'rejected') {
      await this.deps.audit.record({
        eventType: 'pairing.claim',
        result: 'denied',
        deviceId: session.deviceId,
        sessionId: session.id,
        detail: { reason: status },
      });
      throw new ProtocolError('PAIRING_ALREADY_USED', 'Kopplung wurde bereits abgeschlossen');
    }
    if (status === 'pending') {
      throw new ProtocolError('FORBIDDEN', 'Kopplung wurde noch nicht bestaetigt');
    }

    if (Math.abs(now - input.issuedAt) > CLOCK_SKEW_MS) {
      throw invalidMessage('issuedAt liegt ausserhalb des erlaubten Zeitfensters');
    }

    const key = parseSpki(session.publicKey);
    const canonical = canonicalPairingClaim({
      pairingId: session.id,
      deviceId: session.deviceId,
      deviceSecret: input.deviceSecret,
      issuedAt: input.issuedAt,
    });
    if (!verifySignature(canonical, input.signature, key)) {
      await this.deps.audit.record({
        eventType: 'pairing.claim',
        result: 'failure',
        deviceId: session.deviceId,
        sessionId: session.id,
        detail: { reason: 'signature_invalid' },
      });
      throw invalidMessage('Signatur ist ungueltig');
    }

    const ownerId = session.ownerId;
    if (ownerId === null) {
      // An approved session always carries the approving owner. A row without
      // one is inconsistent and must not produce a device token.
      throw new ProtocolError('INTERNAL', 'Kopplung hat keinen Besitzer');
    }

    const existing = await this.deps.repositories.devices.findByDeviceId(session.deviceId);
    if (existing !== undefined && existing.publicKey !== session.publicKey) {
      throw invalidMessage('Public Key weicht von der bestehenden Registrierung ab');
    }

    const metadata = parseMetadata(session.metadata);
    const device = await this.deps.repositories.devices.upsert({
      ownerId,
      deviceId: session.deviceId,
      publicKey: session.publicKey,
      fingerprint: session.fingerprint,
      name: metadata.deviceName,
      platform: metadata.platform,
      osVersion: metadata.osVersion,
      sdkInt: metadata.sdkInt,
      appVersion: metadata.appVersion,
    });

    // Re-pairing invalidates every previously issued token and open session.
    await this.deps.repositories.deviceTokens.revokeAllForDevice(device.id, now);
    await this.deps.repositories.remoteSessions.revokeAllForDevice(device.id, now);

    const deviceToken = randomToken(SECRET_BYTES);
    await this.deps.repositories.deviceTokens.create({
      deviceId: device.id,
      tokenHash: sha256Hex(deviceToken),
    });

    await this.deps.repositories.pairingSessions.markConsumed(session.id, now);

    const capabilities = await this.deps.repositories.deviceCapabilities.listForDevice(device.id);
    const granted = capabilities
      .filter((entry) => entry.granted)
      .map((entry) => entry.capability)
      .filter((capability) =>
        (IMPLEMENTED_CAPABILITIES_V1 as readonly string[]).includes(capability),
      );

    await this.deps.audit.record({
      eventType: 'pairing.claim',
      result: 'success',
      userId: ownerId,
      deviceId: session.deviceId,
      sessionId: session.id,
      detail: { repaired: existing !== undefined },
    });

    return {
      deviceToken,
      device: {
        id: device.id,
        deviceId: device.deviceId,
        name: device.name,
        pairedAt: toIso(device.updatedAt),
      },
      capabilities: {
        granted,
        // Pairing itself never requests capabilities; they are granted
        // separately by the Control Center (PROTOCOL.md section 8).
        requested: [],
      },
      serverTime: toIso(now),
    };
  }

  private async decide(
    pairingId: string,
    proof: PairingProof,
    actor: { userId: string },
    decision: 'approve' | 'reject',
  ): Promise<PairingSummary> {
    const now = this.deps.clock.now();
    const session = await this.resolveByProof(proof, now);

    // The proof must belong to exactly the addressed pairing session.
    if (!timingSafeEqualString(session.id, pairingId)) {
      await this.deps.audit.record({
        eventType: decision === 'approve' ? 'pairing.approve' : 'pairing.reject',
        result: 'denied',
        userId: actor.userId,
        sessionId: pairingId,
        detail: { reason: 'proof_pairing_mismatch' },
      });
      throw pairingNotFound();
    }

    const status = this.effectiveStatus(session, now);
    if (status === 'expired') {
      await this.deps.repositories.pairingSessions.markExpired(session.id);
      throw new ProtocolError('PAIRING_EXPIRED', 'Kopplung ist abgelaufen');
    }
    if (status !== 'pending') {
      throw new ProtocolError('PAIRING_ALREADY_USED', 'Kopplung wurde bereits entschieden');
    }

    if (decision === 'approve') {
      await this.deps.repositories.pairingSessions.approve(session.id, actor.userId, now);
    } else {
      await this.deps.repositories.pairingSessions.reject(session.id, actor.userId, now);
    }

    const updated = await this.deps.repositories.pairingSessions.findById(session.id);
    if (updated === undefined) {
      throw new ProtocolError('INTERNAL', 'Kopplung konnte nicht gelesen werden');
    }

    await this.deps.audit.record({
      eventType: decision === 'approve' ? 'pairing.approve' : 'pairing.reject',
      result: 'success',
      userId: actor.userId,
      deviceId: session.deviceId,
      sessionId: session.id,
    });

    const metadata = parseMetadata(updated.metadata);
    const known = await this.deps.repositories.devices.findByDeviceId(updated.deviceId);
    return {
      pairingId: updated.id,
      status: updated.status,
      device: {
        deviceId: updated.deviceId,
        fingerprint: updated.fingerprint,
        deviceName: metadata.deviceName,
        platform: metadata.platform,
        osVersion: metadata.osVersion,
        sdkInt: metadata.sdkInt,
        appVersion: metadata.appVersion,
      },
      knownDevice: known !== undefined,
      createdAt: toIso(updated.createdAt),
      expiresAt: toIso(updated.expiresAt),
    };
  }

  /**
   * Resolves a pairing session from a ticket or display code and books the
   * attempt against the session. Once PAIRING_MAX_LOOKUP_ATTEMPTS is exceeded
   * the session is invalidated server side (PROTOCOL.md section 5.3).
   */
  private async resolveByProof(
    proof: PairingProof,
    now: number,
  ): Promise<PairingSessionRecord> {
    let session: PairingSessionRecord | undefined;
    if (proof.ticket !== undefined) {
      session = await this.deps.repositories.pairingSessions.findByTicketHash(
        sha256Hex(proof.ticket),
      );
    } else if (proof.displayCode !== undefined) {
      session = await this.deps.repositories.pairingSessions.findPendingByDisplayCodeHash(
        sha256Hex(proof.displayCode),
        now,
      );
    }

    if (session === undefined) {
      throw pairingNotFound();
    }

    const attempts = await this.deps.repositories.pairingSessions.incrementLookupAttempts(
      session.id,
    );
    if (attempts > PAIRING_MAX_LOOKUP_ATTEMPTS) {
      await this.deps.repositories.pairingSessions.markExpired(session.id);
      await this.deps.audit.record({
        eventType: 'pairing.lookup',
        result: 'denied',
        sessionId: session.id,
        detail: { reason: 'too_many_attempts', attempts },
      });
      throw pairingNotFound();
    }

    return { ...session, lookupAttempts: attempts };
  }

  /**
   * Loads a session by id and verifies the device secret. A wrong secret counts
   * against the per-session attempt budget, because the caller addresses one
   * specific session.
   */
  private async requireSessionWithSecret(
    pairingId: string,
    deviceSecret: string,
  ): Promise<PairingSessionRecord> {
    const session = await this.deps.repositories.pairingSessions.findById(pairingId);
    if (session === undefined) {
      throw pairingNotFound();
    }
    if (!verifyHashedSecret(deviceSecret, session.deviceSecretHash)) {
      const attempts = await this.deps.repositories.pairingSessions.incrementLookupAttempts(
        session.id,
      );
      if (attempts > PAIRING_MAX_LOOKUP_ATTEMPTS) {
        await this.deps.repositories.pairingSessions.markExpired(session.id);
      }
      throw new ProtocolError('UNAUTHORIZED', 'deviceSecret ist ungueltig');
    }
    return session;
  }

  private effectiveStatus(session: PairingSessionRecord, now: number): PairingStatus {
    if (session.status === 'pending' && session.expiresAt <= now) {
      return 'expired';
    }
    return session.status;
  }

  /**
   * Picks a display code that no other pending session currently uses, so a
   * lookup by code can never be ambiguous.
   */
  private async pickUnusedDisplayCode(now: number): Promise<string> {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const candidate = randomDisplayCode();
      const inUse = await this.deps.repositories.pairingSessions.countPendingByDisplayCodeHash(
        sha256Hex(candidate),
        now,
      );
      if (inUse === 0) {
        return candidate;
      }
    }
    throw new ProtocolError(
      'RATE_LIMITED',
      'Derzeit sind zu viele Kopplungen offen, bitte spaeter erneut versuchen',
      { retryAfterSeconds: 30 },
    );
  }
}
