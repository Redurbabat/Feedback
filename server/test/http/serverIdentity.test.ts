import { randomBytes } from 'node:crypto';

import { describe, expect, it } from 'vitest';

import { parseSpki, verifySignature } from '../../src/crypto/deviceIdentity.js';
import { serverIdentityPayload } from '../../src/crypto/serverIdentity.js';
import {
  buildClaimBody,
  controlHeaders,
  createHarness,
  createTestDevice,
  createTestUser,
  login,
  randomPassword,
  startPairing,
} from '../helpers/harness.js';
import type { Harness, TestDevice } from '../helpers/harness.js';

/**
 * The route that makes "which server is this" answerable by something other than an address.
 *
 * Everywhere else the device authenticates first: the deviceToken is a Bearer header on the very
 * first request, the WebSocket upgrade included. So the proof has to be obtainable BEFORE that,
 * without any credential - which is exactly what these tests pin, alongside the part that makes
 * the proof worth anything: it is bound to the asking device and to a nonce it chose.
 */

const DEVICE_ID = 'fb-0123456789abcdef01234567';

function nonce(): string {
  return randomBytes(24).toString('base64url');
}

async function ask(
  harness: Harness,
  body: Record<string, unknown>,
  headers: Record<string, string> = {},
) {
  return harness.app.inject({
    method: 'POST',
    url: '/api/v1/agent/server-identity',
    payload: body,
    headers,
  });
}

interface Proof {
  readonly serverPublicKey: string;
  readonly issuedAt: number;
  readonly signature: string;
}

describe('the server proving which server it is', () => {
  it('answers without any credential at all', async () => {
    const harness = await createHarness();
    try {
      // No Bearer token anywhere: a device that has not verified the server must not have to
      // send one to find out whether it should.
      const response = await ask(harness, { deviceId: DEVICE_ID, nonce: nonce() });
      expect(response.statusCode).toBe(200);
    } finally {
      await harness.close();
    }
  });

  it('signs the nonce and the device that asked, verifiably with the key it returns', async () => {
    const harness = await createHarness();
    try {
      const asked = nonce();
      const proof = (await ask(harness, { deviceId: DEVICE_ID, nonce: asked })).json() as Proof;

      const payload = serverIdentityPayload({
        deviceId: DEVICE_ID,
        nonceBase64Url: asked,
        publicKeyBase64: proof.serverPublicKey,
        issuedAtEpochMillis: proof.issuedAt,
      });
      expect(verifySignature(payload, proof.signature, parseSpki(proof.serverPublicKey))).toBe(true);
    } finally {
      await harness.close();
    }
  });

  /** A proof collected once must not work a second time, nor at another device. */
  it('does not verify against another nonce or another device', async () => {
    const harness = await createHarness();
    try {
      const asked = nonce();
      const proof = (await ask(harness, { deviceId: DEVICE_ID, nonce: asked })).json() as Proof;
      const key = parseSpki(proof.serverPublicKey);

      const replayed = serverIdentityPayload({
        deviceId: DEVICE_ID,
        nonceBase64Url: nonce(),
        publicKeyBase64: proof.serverPublicKey,
        issuedAtEpochMillis: proof.issuedAt,
      });
      const elsewhere = serverIdentityPayload({
        deviceId: 'fb-ffffffffffffffffffffffff',
        nonceBase64Url: asked,
        publicKeyBase64: proof.serverPublicKey,
        issuedAtEpochMillis: proof.issuedAt,
      });

      expect(verifySignature(replayed, proof.signature, key)).toBe(false);
      expect(verifySignature(elsewhere, proof.signature, key)).toBe(false);
    } finally {
      await harness.close();
    }
  });

  /**
   * The route says who the server is, not who it knows. A different answer for a paired and an
   * unpaired device would turn it into an oracle for "is this phone registered here".
   */
  it('answers a device it has never seen exactly like one it knows', async () => {
    const harness = await createHarness();
    try {
      const unknown = (await ask(harness, { deviceId: DEVICE_ID, nonce: nonce() })).json() as Proof;
      const other = (await ask(harness, {
        deviceId: 'fb-aaaaaaaaaaaaaaaaaaaaaaaa',
        nonce: nonce(),
      })).json() as Proof;

      expect(Object.keys(other).sort()).toEqual(Object.keys(unknown).sort());
      expect(other.serverPublicKey).toBe(unknown.serverPublicKey);
    } finally {
      await harness.close();
    }
  });

  it('refuses a malformed request rather than signing whatever it was given', async () => {
    const harness = await createHarness();
    try {
      for (const body of [
        {},
        { deviceId: DEVICE_ID },
        { nonce: nonce() },
        { deviceId: 'nicht-fb', nonce: nonce() },
        { deviceId: DEVICE_ID, nonce: 'zu kurz' },
        { deviceId: DEVICE_ID, nonce: nonce(), extra: 'was auch immer' },
      ]) {
        const response = await ask(harness, body);
        expect(JSON.stringify(body)).toBeTruthy();
        expect(response.statusCode).toBe(400);
      }
    } finally {
      await harness.close();
    }
  });

  /**
   * Trust on first use only works if the key the device stores at pairing is the same one it is
   * later shown. Two sources for one value is exactly where they drift apart.
   */
  it('hands the pairing claim the same key the proof uses', async () => {
    const harness = await createHarness();
    try {
      const email = 'tofu@example.test';
      const password = randomPassword();
      await createTestUser(harness, email, password);
      const session = await login(harness, email, password);

      const device: TestDevice = createTestDevice();
      const started = await startPairing(harness, device);

      const approved = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/pairing/${started.pairingId}/approve`,
        headers: controlHeaders(session),
        payload: { ticket: started.ticket },
      });
      expect(approved.statusCode).toBe(200);

      const claimed = await harness.app.inject({
        method: 'POST',
        url: `/api/v1/pairing/${started.pairingId}/claim`,
        payload: buildClaimBody(
          device,
          started.pairingId,
          started.deviceSecret,
          harness.clock.now(),
        ),
      });
      expect(claimed.statusCode).toBe(200);

      const registration = claimed.json() as { serverPublicKey?: string };
      const proof = (await ask(harness, { deviceId: DEVICE_ID, nonce: nonce() })).json() as Proof;

      expect(registration.serverPublicKey).toBe(proof.serverPublicKey);
    } finally {
      await harness.close();
    }
  });
});
